package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
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
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
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
 * Sprint 13 (v1.7.0) — Ring 2 codegen: {@code generate_getters_setters}.
 *
 * <p>Adds JavaBean-style accessor pairs for the given fields. Multi-field
 * (this is the gap vs. {@code encapsulate_field}, which is single-field
 * and rewrites call sites). Boolean fields use {@code isField()} instead
 * of {@code getField()} per the Bean convention.</p>
 *
 * <p>Skips an accessor when a method with the same name already exists on
 * the target class (conflict reported in {@code warnings[]}); doesn't
 * try to be clever about overloads.</p>
 */
public class GenerateGettersSettersTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateGettersSettersTool.class);

    private final org.goja.mcp.refactoring.RefactoringChangeCache changeCache;

    public GenerateGettersSettersTool(Supplier<IJdtService> serviceSupplier,
                                     RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "generate_getters_setters";
    }

    @Override
    public String getDescription() {
        return """
            Generate JavaBean getter/setter accessors for the given fields.
            Multi-field; complements encapsulate_field (which is single-field
            and rewrites call sites).

            For an IMMUTABLE data carrier, prefer a Java record over generated
            boilerplate — find candidates with find_modernization(kind="class_to_record").
            Records give accessors + equals/hashCode/toString with zero code. Use
            this tool for mutable JavaBeans (setters / inheritance) where a record
            doesn't fit.

            USAGE:
              generate_getters_setters(filePath="...", line=10, column=14,
                                       fields=["name", "id"])
              generate_getters_setters(filePath="...", line=10, column=14,
                                       fields=["name"], kind="getters")

            Inputs:
            - filePath / line / column — caret position inside the target class.
            - fields — array of existing field names.
            - kind — "both" (default) | "getters" | "setters".
            - visibility — public (default) | protected | private | package.

            Result:
              { operation, filePath, methodsAdded[], warnings[],
                generatedSource, modifiedFiles }

            Existing methods with conflicting names are skipped (recorded in
            warnings[]); never overwritten.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string"));
        properties.put("line", Map.of("type", "integer"));
        properties.put("column", Map.of("type", "integer"));
        properties.put("fields", Map.of(
            "type", "array",
            "items", Map.of("type", "string")));
        properties.put("kind", Map.of(
            "type", "string",
            "enum", List.of("both", "getters", "setters")));
        properties.put("visibility", Map.of(
            "type", "string",
            "enum", List.of("public", "protected", "private", "package")));
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
        String kind = getStringParam(arguments, "kind", "both");
        String visibility = getStringParam(arguments, "visibility", "public");

        boolean wantGetters = "both".equalsIgnoreCase(kind) || "getters".equalsIgnoreCase(kind);
        boolean wantSetters = "both".equalsIgnoreCase(kind) || "setters".equalsIgnoreCase(kind);
        if (!wantGetters && !wantSetters) {
            return ToolResponse.invalidParameter("kind",
                "kind must be one of 'both', 'getters', 'setters'; got '" + kind + "'");
        }

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

        try {
            Path filePath = Path.of(filePathRaw);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            IType type = walkUpToType(element);
            if (type == null) {
                return ToolResponse.invalidParameter("filePath/line/column",
                    "Caret does not resolve to a class.");
            }
            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return ToolResponse.invalidParameter("filePath",
                    "Target type has no compilation unit.");
            }

            List<FieldInfo> fieldInfos = new ArrayList<>();
            for (String name : fieldNames) {
                IField field = type.getField(name);
                if (field == null || !field.exists()) {
                    return ToolResponse.invalidParameter("fields",
                        "Field '" + name + "' is not declared on " + type.getElementName() + ".");
                }
                String typeName = Signature.toString(field.getTypeSignature());
                fieldInfos.add(new FieldInfo(name, typeName));
            }

            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(true);
            CompilationUnit astRoot = (CompilationUnit) parser.createAST(new NullProgressMonitor());
            AbstractTypeDeclaration targetDecl = findTypeDeclaration(astRoot, type.getElementName());
            if (!(targetDecl instanceof TypeDeclaration typeDecl)) {
                return ToolResponse.invalidParameter("filePath/line/column",
                    "Target is not a regular class.");
            }

            AST ast = astRoot.getAST();
            ASTRewrite rewrite = ASTRewrite.create(ast);
            ListRewrite bodyRewrite = rewrite.getListRewrite(typeDecl,
                TypeDeclaration.BODY_DECLARATIONS_PROPERTY);

            List<String> methodsAdded = new ArrayList<>();
            List<String> warnings = new ArrayList<>();

            for (FieldInfo fi : fieldInfos) {
                String getterName = getterName(fi.name, fi.typeName);
                String setterName = "set" + capitalize(fi.name);

                if (wantGetters) {
                    if (methodAlreadyExists(type, getterName, 0)) {
                        warnings.add("Skipped getter '" + getterName + "' — already exists");
                    } else {
                        bodyRewrite.insertLast(buildGetter(ast, fi, getterName, visibility), null);
                        methodsAdded.add(getterName);
                    }
                }
                if (wantSetters) {
                    if (methodAlreadyExists(type, setterName, 1)) {
                        warnings.add("Skipped setter '" + setterName + "' — already exists");
                    } else {
                        bodyRewrite.insertLast(buildSetter(ast, fi, setterName, visibility), null);
                        methodsAdded.add(setterName);
                    }
                }
            }

            TextEdit edits = rewrite.rewriteAST();
            String original = cu.getSource();
            Document doc = new Document(original);
            edits.apply(doc);
            String newSource = doc.get();

            boolean autoApply = getBooleanParam(arguments, "auto_apply", true);
            if (!autoApply) {
                SourceCommit.Staged staged = SourceCommit.stageFullReplace(
                    cu, newSource, "generate_getters_setters", changeCache, service);
                Map<String, Object> stagedData = new LinkedHashMap<>();
                stagedData.put("operation", "generate_getters_setters");
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
                cu, newSource, "generate_getters_setters", changeCache, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "generate_getters_setters");
            data.put("filePath", service.getPathUtils().formatPath(
                cu.getResource().getLocation().toFile().toPath()));
            data.put("methodsAdded", methodsAdded);
            data.put("warnings", warnings);
            data.put("generatedSource", newSource);

            data.put("applied", true);
            data.put("filesModified", List.of(committed.filePath()));
            data.put("diff", committed.diff());
            data.put("undoChangeId", committed.undoChangeId());

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(methodsAdded.size())
                .returnedCount(methodsAdded.size())
                .build());
        } catch (Exception e) {
            log.warn("generate_getters_setters failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private static MethodDeclaration buildGetter(AST ast, FieldInfo fi, String getterName, String visibility) {
        MethodDeclaration getter = ast.newMethodDeclaration();
        getter.setName(ast.newSimpleName(getterName));
        getter.setReturnType2(buildType(ast, fi.typeName));
        applyVisibility(ast, getter, visibility);

        Block body = ast.newBlock();
        @SuppressWarnings("unchecked")
        List<Object> stmts = body.statements();
        FieldAccess access = ast.newFieldAccess();
        access.setExpression(ast.newThisExpression());
        access.setName(ast.newSimpleName(fi.name));
        ReturnStatement ret = ast.newReturnStatement();
        ret.setExpression(access);
        stmts.add(ret);
        getter.setBody(body);
        return getter;
    }

    private static MethodDeclaration buildSetter(AST ast, FieldInfo fi, String setterName, String visibility) {
        MethodDeclaration setter = ast.newMethodDeclaration();
        setter.setName(ast.newSimpleName(setterName));
        setter.setReturnType2(ast.newPrimitiveType(PrimitiveType.VOID));
        applyVisibility(ast, setter, visibility);

        SingleVariableDeclaration param = ast.newSingleVariableDeclaration();
        param.setType(buildType(ast, fi.typeName));
        param.setName(ast.newSimpleName(fi.name));
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = setter.parameters();
        params.add(param);

        Block body = ast.newBlock();
        @SuppressWarnings("unchecked")
        List<Object> stmts = body.statements();
        Assignment assign = ast.newAssignment();
        FieldAccess lhs = ast.newFieldAccess();
        lhs.setExpression(ast.newThisExpression());
        lhs.setName(ast.newSimpleName(fi.name));
        assign.setLeftHandSide(lhs);
        assign.setRightHandSide(ast.newSimpleName(fi.name));
        stmts.add(ast.newExpressionStatement(assign));
        setter.setBody(body);
        return setter;
    }

    private static String getterName(String fieldName, String typeName) {
        if ("boolean".equals(typeName) || "Boolean".equals(typeName)) {
            // Bean convention: avoid double "is" prefix.
            if (fieldName.startsWith("is") && fieldName.length() > 2
                && Character.isUpperCase(fieldName.charAt(2))) {
                return fieldName;
            }
            return "is" + capitalize(fieldName);
        }
        return "get" + capitalize(fieldName);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static boolean methodAlreadyExists(IType type, String name, int paramCount) {
        try {
            for (IMethod method : type.getMethods()) {
                if (name.equals(method.getElementName())
                    && method.getNumberOfParameters() == paramCount) {
                    return true;
                }
            }
        } catch (Exception ignore) {}
        return false;
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

    private static Type buildType(AST ast, String typeName) {
        String t = typeName.trim();
        int bracketIdx = t.indexOf('[');
        if (bracketIdx > 0) {
            int dims = 0;
            for (int i = bracketIdx; i < t.length(); i++) {
                if (t.charAt(i) == '[') dims++;
            }
            return ast.newArrayType(buildType(ast, t.substring(0, bracketIdx).trim()), dims);
        }
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
        int genericIdx = t.indexOf('<');
        if (genericIdx > 0) t = t.substring(0, genericIdx);
        return ast.newSimpleType(ast.newName(t));
    }

    private record FieldInfo(String name, String typeName) {}
}
