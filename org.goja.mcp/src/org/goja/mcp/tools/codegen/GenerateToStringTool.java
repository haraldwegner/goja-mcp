package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
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
 * Sprint 13 (v1.7.0) — Ring 2 codegen: {@code generate_tostring}.
 *
 * <p>Generates {@code toString()} over the given fields in one of two styles:
 * {@code STRING_CONCATENATION} (default — readable) or {@code STRING_BUILDER}
 * (avoids intermediate Strings).</p>
 *
 * <p>{@code STRING_FORMAT} and {@code STRING_BUILDER_CHAINED} are tracked as
 * v1.8.x follow-ups; the tool rejects them with {@code INVALID_PARAMETER}
 * pointing at the upgrade-checklist for now.</p>
 */
public class GenerateToStringTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateToStringTool.class);

    private final org.goja.mcp.refactoring.RefactoringChangeCache changeCache;

    public GenerateToStringTool(Supplier<IJdtService> serviceSupplier,
                               RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "generate_tostring";
    }

    @Override
    public String getDescription() {
        return """
            Generate toString() for the given fields. Two styles supported:
            STRING_CONCATENATION (default) and STRING_BUILDER.

            USAGE:
              generate_tostring(filePath="...", line=10, column=14,
                                fields=["id", "name"])
              generate_tostring(filePath="...", line=10, column=14,
                                fields=["id", "name"], style="STRING_BUILDER")

            Inputs:
            - filePath / line / column — caret inside the target class.
            - fields — non-empty array of field names (existing fields).
            - style — STRING_CONCATENATION (default) or STRING_BUILDER.

            STRING_FORMAT and STRING_BUILDER_CHAINED arrive in v1.8.x; the
            tool returns INVALID_PARAMETER for those today.

            Result:
              { operation, filePath, methodsAdded, warnings,
                generatedSource, modifiedFiles }
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
        properties.put("fields", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("style", Map.of(
            "type", "string",
            "enum", List.of("STRING_CONCATENATION", "STRING_BUILDER")));
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
        String style = getStringParam(arguments, "style", "STRING_CONCATENATION");

        if (!"STRING_CONCATENATION".equalsIgnoreCase(style)
            && !"STRING_BUILDER".equalsIgnoreCase(style)) {
            return ToolResponse.invalidParameter("style",
                "Supported styles in v1.7.0: STRING_CONCATENATION, STRING_BUILDER. "
                    + "Got: '" + style + "'. STRING_FORMAT / STRING_BUILDER_CHAINED "
                    + "land in v1.8.x.");
        }

        JsonNode fieldsNode = arguments.get("fields");
        if (!fieldsNode.isArray() || fieldsNode.isEmpty()) {
            return ToolResponse.invalidParameter("fields",
                "fields must be a non-empty array.");
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
            for (String name : fieldNames) {
                if (type.getField(name) == null || !type.getField(name).exists()) {
                    return ToolResponse.invalidParameter("fields",
                        "Field '" + name + "' is not declared on " + type.getElementName() + ".");
                }
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

            if (methodAlreadyExists(type, "toString", 0)) {
                warnings.add("Skipped toString() — already exists");
            } else {
                MethodDeclaration toStr = "STRING_BUILDER".equalsIgnoreCase(style)
                    ? buildToStringBuilderStyle(ast, typeDecl.getName().getIdentifier(), fieldNames)
                    : buildToStringConcatStyle(ast, typeDecl.getName().getIdentifier(), fieldNames);
                bodyRewrite.insertLast(toStr, null);
                methodsAdded.add("toString");
            }

            TextEdit edits = rewrite.rewriteAST();
            String original = cu.getSource();
            Document doc = new Document(original);
            edits.apply(doc);
            String newSource = doc.get();

            boolean autoApply = getBooleanParam(arguments, "auto_apply", true);
            if (!autoApply) {
                SourceCommit.Staged staged = SourceCommit.stageFullReplace(
                    cu, newSource, "generate_tostring", changeCache, service);
                Map<String, Object> stagedData = new LinkedHashMap<>();
                stagedData.put("operation", "generate_tostring");
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
                cu, newSource, "generate_tostring", changeCache, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "generate_tostring");
            data.put("filePath", service.getPathUtils().formatPath(
                cu.getResource().getLocation().toFile().toPath()));
            data.put("methodsAdded", methodsAdded);
            data.put("warnings", warnings);
            data.put("style", style.toUpperCase());
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
            log.warn("generate_tostring failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private static MethodDeclaration baseToString(AST ast) {
        MethodDeclaration m = ast.newMethodDeclaration();
        m.setName(ast.newSimpleName("toString"));
        m.setReturnType2(ast.newSimpleType(ast.newSimpleName("String")));
        @SuppressWarnings("unchecked")
        List<Object> mods = m.modifiers();
        MarkerAnnotation override = ast.newMarkerAnnotation();
        override.setTypeName(ast.newSimpleName("Override"));
        mods.add(override);
        mods.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        return m;
    }

    private static MethodDeclaration buildToStringConcatStyle(AST ast, String typeName, List<String> fields) {
        MethodDeclaration m = baseToString(ast);
        Block body = ast.newBlock();
        @SuppressWarnings("unchecked")
        List<Object> stmts = body.statements();

        // "TypeName ["
        StringLiteral header = ast.newStringLiteral();
        header.setLiteralValue(typeName + " [");
        Expression expr = header;

        for (int i = 0; i < fields.size(); i++) {
            String name = fields.get(i);
            String prefixStr = (i == 0 ? "" : ", ") + name + "=";
            StringLiteral prefix = ast.newStringLiteral();
            prefix.setLiteralValue(prefixStr);
            InfixExpression addPrefix = ast.newInfixExpression();
            addPrefix.setOperator(InfixExpression.Operator.PLUS);
            addPrefix.setLeftOperand(expr);
            addPrefix.setRightOperand(prefix);
            InfixExpression addValue = ast.newInfixExpression();
            addValue.setOperator(InfixExpression.Operator.PLUS);
            addValue.setLeftOperand(addPrefix);
            addValue.setRightOperand(ast.newSimpleName(name));
            expr = addValue;
        }

        StringLiteral closer = ast.newStringLiteral();
        closer.setLiteralValue("]");
        InfixExpression addCloser = ast.newInfixExpression();
        addCloser.setOperator(InfixExpression.Operator.PLUS);
        addCloser.setLeftOperand(expr);
        addCloser.setRightOperand(closer);

        ReturnStatement ret = ast.newReturnStatement();
        ret.setExpression(addCloser);
        stmts.add(ret);
        m.setBody(body);
        return m;
    }

    private static MethodDeclaration buildToStringBuilderStyle(AST ast, String typeName, List<String> fields) {
        MethodDeclaration m = baseToString(ast);
        Block body = ast.newBlock();
        @SuppressWarnings("unchecked")
        List<Object> stmts = body.statements();

        // StringBuilder sb = new StringBuilder();
        VariableDeclarationFragment frag = ast.newVariableDeclarationFragment();
        frag.setName(ast.newSimpleName("sb"));
        ClassInstanceCreation create = ast.newClassInstanceCreation();
        create.setType(ast.newSimpleType(ast.newSimpleName("StringBuilder")));
        frag.setInitializer(create);
        VariableDeclarationStatement decl = ast.newVariableDeclarationStatement(frag);
        decl.setType(ast.newSimpleType(ast.newSimpleName("StringBuilder")));
        stmts.add(decl);

        // sb.append("TypeName [");
        stmts.add(ast.newExpressionStatement(appendCall(ast, "sb", literal(ast, typeName + " ["))));

        for (int i = 0; i < fields.size(); i++) {
            String name = fields.get(i);
            String prefix = (i == 0 ? "" : ", ") + name + "=";
            // sb.append("prefix").append(name);
            MethodInvocation appendPrefix = appendCall(ast, "sb", literal(ast, prefix));
            MethodInvocation appendValue = ast.newMethodInvocation();
            appendValue.setExpression(appendPrefix);
            appendValue.setName(ast.newSimpleName("append"));
            @SuppressWarnings("unchecked")
            List<Object> args = appendValue.arguments();
            args.add(ast.newSimpleName(name));
            stmts.add(ast.newExpressionStatement(appendValue));
        }

        stmts.add(ast.newExpressionStatement(appendCall(ast, "sb", literal(ast, "]"))));

        // return sb.toString();
        MethodInvocation toStr = ast.newMethodInvocation();
        toStr.setExpression(ast.newSimpleName("sb"));
        toStr.setName(ast.newSimpleName("toString"));
        ReturnStatement ret = ast.newReturnStatement();
        ret.setExpression(toStr);
        stmts.add(ret);
        m.setBody(body);
        return m;
    }

    private static StringLiteral literal(AST ast, String value) {
        StringLiteral lit = ast.newStringLiteral();
        lit.setLiteralValue(value);
        return lit;
    }

    private static MethodInvocation appendCall(AST ast, String receiver, Expression arg) {
        MethodInvocation call = ast.newMethodInvocation();
        call.setExpression(ast.newSimpleName(receiver));
        call.setName(ast.newSimpleName("append"));
        @SuppressWarnings("unchecked")
        List<Object> args = call.arguments();
        args.add(arg);
        return call;
    }

    private static boolean methodAlreadyExists(IType type, String name, int paramCount) {
        try {
            for (IMethod m : type.getMethods()) {
                if (name.equals(m.getElementName()) && m.getNumberOfParameters() == paramCount) {
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
}
