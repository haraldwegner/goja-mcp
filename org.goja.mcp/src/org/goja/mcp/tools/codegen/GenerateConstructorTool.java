package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.goja.core.IJdtService;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.refactoring.SourceCommit;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AbstractTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 13 (v1.7.0) — Ring 2 codegen: {@code generate_constructor}.
 *
 * <p>Adds a constructor to a target type that initializes the given fields.
 * Supports visibility selection (public/protected/private/package) and an
 * optional {@code super()} chaining call.</p>
 *
 * <p>Implementation goes through {@link ASTRewrite} directly rather than
 * JDT-UI's {@code GenerateConstructorOperation} (the latter lives in
 * {@code org.eclipse.jdt.ui}, which is not on our target platform).</p>
 */
public class GenerateConstructorTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateConstructorTool.class);

    private final org.goja.mcp.refactoring.RefactoringChangeCache changeCache;

    public GenerateConstructorTool(Supplier<IJdtService> serviceSupplier,
                                  RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "generate_constructor";
    }

    @Override
    public String getDescription() {
        return """
            Generate a constructor on a target class that initializes the given
            fields. Avoids small mistakes agents often make hand-writing
            constructors (visibility, this.field = field, modifiers, super()).

            USAGE:
              generate_constructor(filePath="...", line=10, column=14,
                                   fields=["name", "id"])
              generate_constructor(filePath="...", line=10, column=14,
                                   fields=["name"], visibility="protected",
                                   callSuper="true")

            Inputs:
            - filePath / line / column — caret position inside the target class.
            - fields — array of existing field names to initialize.
            - visibility — public (default) | protected | private | package.
            - callSuper — auto (default; no super call) | true | false.

            Result:
              { operation, filePath, methodName, generatedSource, modifiedFiles }

            Errors: INVALID_PARAMETER if any named field is not a field of the
            target class, or the caret doesn't resolve to a class.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of(
            "type", "string",
            "description", "Path to the .java file containing the target class."));
        properties.put("line", Map.of(
            "type", "integer",
            "description", "0-based line number of the caret inside the target class."));
        properties.put("column", Map.of(
            "type", "integer",
            "description", "0-based column number of the caret inside the target class."));
        properties.put("fields", Map.of(
            "type", "array",
            "items", Map.of("type", "string"),
            "description", "Field names to initialize. Each must already declare on the target class."));
        properties.put("visibility", Map.of(
            "type", "string",
            "enum", List.of("public", "protected", "private", "package"),
            "description", "Visibility modifier; default 'public'."));
        properties.put("callSuper", Map.of(
            "type", "string",
            "enum", List.of("auto", "true", "false"),
            "description", "Whether to emit a super() call. Default 'auto' (no super)."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "fields"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse missing = requireParam(arguments, "filePath");
        if (missing != null) return missing;
        missing = requireParam(arguments, "fields");
        if (missing != null) return missing;

        String filePathRaw = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", 0);
        int column = getIntParam(arguments, "column", 0);
        String visibility = getStringParam(arguments, "visibility", "public");
        String callSuper = getStringParam(arguments, "callSuper", "auto");

        JsonNode fieldsNode = arguments.get("fields");
        if (!fieldsNode.isArray() || fieldsNode.isEmpty()) {
            return ToolResponse.invalidParameter("fields",
                "fields must be a non-empty array of field names.");
        }
        List<String> fieldNames = new ArrayList<>();
        for (JsonNode n : fieldsNode) {
            String s = n.asText();
            if (s != null && !s.isBlank()) fieldNames.add(s);
        }
        if (fieldNames.isEmpty()) {
            return ToolResponse.invalidParameter("fields",
                "fields must contain at least one non-empty name.");
        }

        try {
            Path filePath = Path.of(filePathRaw);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            IType type = walkUpToType(element);
            if (type == null) {
                return ToolResponse.invalidParameter("filePath/line/column",
                    "Caret does not resolve to a class. Got: "
                        + (element == null ? "null" : element.getClass().getSimpleName()));
            }
            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return ToolResponse.invalidParameter("filePath",
                    "Target type has no compilation unit (binary or unresolved).");
            }

            // Validate every requested field exists on the target type.
            List<FieldInfo> fieldInfos = new ArrayList<>();
            for (String name : fieldNames) {
                IField field = type.getField(name);
                if (field == null || !field.exists()) {
                    return ToolResponse.invalidParameter("fields",
                        "Field '" + name + "' is not declared on " + type.getElementName() + ".");
                }
                String typeSig = field.getTypeSignature();
                String typeName = Signature.toString(typeSig);
                fieldInfos.add(new FieldInfo(name, typeName));
            }

            // Parse the CU into an AST so we can ASTRewrite a constructor in.
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(true);
            CompilationUnit astRoot = (CompilationUnit) parser.createAST(new NullProgressMonitor());
            AbstractTypeDeclaration targetDecl = findTypeDeclaration(astRoot, type.getElementName());
            if (!(targetDecl instanceof TypeDeclaration typeDecl)) {
                return ToolResponse.invalidParameter("filePath/line/column",
                    "Target is not a regular class; codegen on enums/interfaces/records is not supported.");
            }

            AST ast = astRoot.getAST();
            ASTRewrite rewrite = ASTRewrite.create(ast);

            MethodDeclaration ctor = ast.newMethodDeclaration();
            ctor.setConstructor(true);
            ctor.setName(ast.newSimpleName(typeDecl.getName().getIdentifier()));
            applyVisibility(ast, ctor, visibility);

            for (FieldInfo fi : fieldInfos) {
                SingleVariableDeclaration param = ast.newSingleVariableDeclaration();
                param.setType(buildType(ast, fi.typeName));
                param.setName(ast.newSimpleName(fi.name));
                @SuppressWarnings("unchecked")
                List<SingleVariableDeclaration> params = ctor.parameters();
                params.add(param);
            }

            Block body = ast.newBlock();
            @SuppressWarnings("unchecked")
            List<Object> stmts = body.statements();
            if ("true".equalsIgnoreCase(callSuper)) {
                SuperConstructorInvocation sup = ast.newSuperConstructorInvocation();
                stmts.add(sup);
            }
            for (FieldInfo fi : fieldInfos) {
                Assignment assign = ast.newAssignment();
                FieldAccess lhs = ast.newFieldAccess();
                lhs.setExpression(ast.newThisExpression());
                lhs.setName(ast.newSimpleName(fi.name));
                assign.setLeftHandSide(lhs);
                assign.setRightHandSide(ast.newSimpleName(fi.name));
                ExpressionStatement stmt = ast.newExpressionStatement(assign);
                stmts.add(stmt);
            }
            ctor.setBody(body);

            ListRewrite bodyRewrite = rewrite.getListRewrite(typeDecl,
                TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
            bodyRewrite.insertLast(ctor, null);

            // Apply the edits back to the CU's source.
            TextEdit edits = rewrite.rewriteAST();
            String original = cu.getSource();
            Document doc = new Document(original);
            edits.apply(doc);
            String newSource = doc.get();

            boolean autoApply = getBooleanParam(arguments, "auto_apply", true);
            if (!autoApply) {
                SourceCommit.Staged staged = SourceCommit.stageFullReplace(
                    cu, newSource, "generate_constructor", changeCache, service);
                Map<String, Object> stagedData = new LinkedHashMap<>();
                stagedData.put("operation", "generate_constructor");
                stagedData.put("applied", false);
                stagedData.put("changeId", staged.changeId());
                stagedData.put("diff", staged.diff());
                stagedData.put("filePath", staged.filePath());
                return ToolResponse.success(stagedData, ResponseMeta.builder()
                    .suggestedNextTools(List.of(
                        "apply_refactoring with this changeId to commit the staged change",
                        "inspect_refactoring with this changeId to re-examine the diff"))
                    .build());
            }

            SourceCommit.Committed committed = SourceCommit.commitWithUndo(
                cu, newSource, "generate_constructor", changeCache, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "generate_constructor");
            data.put("filePath", service.getPathUtils().formatPath(
                cu.getResource().getLocation().toFile().toPath()));
            data.put("methodName", typeDecl.getName().getIdentifier());
            data.put("fieldsInitialized", fieldNames);
            data.put("generatedSource", newSource);

            data.put("applied", true);
            data.put("filesModified", List.of(committed.filePath()));
            data.put("diff", committed.diff());
            data.put("undoChangeId", committed.undoChangeId());

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(1)
                .returnedCount(1)
                .build());
        } catch (Exception e) {
            log.warn("generate_constructor failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private static IType walkUpToType(IJavaElement element) {
        IJavaElement cursor = element;
        while (cursor != null) {
            if (cursor instanceof IType t) return t;
            cursor = cursor.getParent();
        }
        return null;
    }

    private static AbstractTypeDeclaration findTypeDeclaration(CompilationUnit unit, String simpleName) {
        for (Object t : unit.types()) {
            if (t instanceof AbstractTypeDeclaration decl
                && simpleName.equals(decl.getName().getIdentifier())) {
                return decl;
            }
        }
        return null;
    }

    private static void applyVisibility(AST ast, MethodDeclaration method, String visibility) {
        @SuppressWarnings("unchecked")
        List<Object> mods = method.modifiers();
        switch (visibility == null ? "public" : visibility.toLowerCase()) {
            case "public" -> mods.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
            case "protected" -> mods.add(ast.newModifier(Modifier.ModifierKeyword.PROTECTED_KEYWORD));
            case "private" -> mods.add(ast.newModifier(Modifier.ModifierKeyword.PRIVATE_KEYWORD));
            case "package" -> { /* no modifier */ }
            default -> mods.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        }
    }

    private record FieldInfo(String name, String typeName) {}

    /**
     * Build a JDT-DOM {@link Type} from a Java source type name. Handles
     * primitives ({@code int}, {@code boolean}, etc.), arrays
     * ({@code String[]}), and qualified or simple reference types
     * ({@code java.util.List}, {@code String}). Generic types
     * ({@code List<String>}) fall back to a parsed simple name and may
     * lose type-argument information; callers that need full generic
     * fidelity should use a parsed AST type directly.
     */
    private static Type buildType(AST ast, String typeName) {
        String t = typeName.trim();
        // Array suffix: String[] / int[][]
        int bracketIdx = t.indexOf('[');
        if (bracketIdx > 0) {
            int dims = 0;
            for (int i = bracketIdx; i < t.length(); i++) {
                if (t.charAt(i) == '[') dims++;
            }
            Type elementType = buildType(ast, t.substring(0, bracketIdx).trim());
            return ast.newArrayType(elementType, dims);
        }
        // Primitives
        switch (t) {
            case "boolean" -> { return ast.newPrimitiveType(PrimitiveType.BOOLEAN); }
            case "byte"    -> { return ast.newPrimitiveType(PrimitiveType.BYTE); }
            case "char"    -> { return ast.newPrimitiveType(PrimitiveType.CHAR); }
            case "double"  -> { return ast.newPrimitiveType(PrimitiveType.DOUBLE); }
            case "float"   -> { return ast.newPrimitiveType(PrimitiveType.FLOAT); }
            case "int"     -> { return ast.newPrimitiveType(PrimitiveType.INT); }
            case "long"    -> { return ast.newPrimitiveType(PrimitiveType.LONG); }
            case "short"   -> { return ast.newPrimitiveType(PrimitiveType.SHORT); }
            case "void"    -> { return ast.newPrimitiveType(PrimitiveType.VOID); }
            default -> {}
        }
        // Drop generic suffix to keep ast.newName(...) happy. Full generics
        // require parsing the type literal; out of scope for v1.7.0 codegen.
        int genericIdx = t.indexOf('<');
        if (genericIdx > 0) {
            t = t.substring(0, genericIdx);
        }
        return ast.newSimpleType(ast.newName(t));
    }
}
