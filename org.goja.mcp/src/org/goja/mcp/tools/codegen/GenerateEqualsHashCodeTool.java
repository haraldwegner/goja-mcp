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
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
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
 * Sprint 13 (v1.7.0) — Ring 2 codegen: {@code generate_equals_hashcode}.
 *
 * <p>Generates paired {@code equals(Object)} and {@code hashCode()}
 * over the given fields, using {@code Objects.equals(...)} for reference
 * fields, primitive comparison ({@code ==}) for primitives, and
 * {@code Objects.hash(...)} for the hash. Inserts the
 * {@code java.util.Objects} import when missing.</p>
 */
public class GenerateEqualsHashCodeTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateEqualsHashCodeTool.class);

    private final org.goja.mcp.refactoring.RefactoringChangeCache changeCache;

    public GenerateEqualsHashCodeTool(Supplier<IJdtService> serviceSupplier,
                                     RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "generate_equals_hashcode";
    }

    @Override
    public String getDescription() {
        return """
            Generate paired equals(Object) and hashCode() for the given fields.
            Uses Objects.equals/Objects.hash from java.util.Objects (import is
            added when missing). Primitive fields use direct == comparison.

            USAGE:
              generate_equals_hashcode(filePath="...", line=10, column=14,
                                       fields=["id", "name"])

            Inputs:
            - filePath / line / column — caret inside the target class.
            - fields — non-empty array of field names.

            Result:
              { operation, filePath, methodsAdded, warnings,
                generatedSource, modifiedFiles }

            Existing equals(Object) or hashCode() are skipped (warning), never
            overwritten.
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

        JsonNode fieldsNode = arguments.get("fields");
        if (!fieldsNode.isArray() || fieldsNode.isEmpty()) {
            return ToolResponse.invalidParameter("fields",
                "fields must be a non-empty array. equals/hashCode without "
                    + "explicit fields produces a trivial implementation; "
                    + "specify the identity fields explicitly.");
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
                String typeSig = field.getTypeSignature();
                String typeName = Signature.toString(typeSig);
                fieldInfos.add(new FieldInfo(name, typeName, isPrimitive(typeName)));
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

            if (methodAlreadyExists(type, "equals", 1)) {
                warnings.add("Skipped equals(Object) — already exists");
            } else {
                bodyRewrite.insertLast(buildEquals(ast, typeDecl.getName().getIdentifier(), fieldInfos), null);
                methodsAdded.add("equals");
            }
            if (methodAlreadyExists(type, "hashCode", 0)) {
                warnings.add("Skipped hashCode() — already exists");
            } else {
                bodyRewrite.insertLast(buildHashCode(ast, fieldInfos), null);
                methodsAdded.add("hashCode");
            }

            TextEdit edits = rewrite.rewriteAST();
            String original = cu.getSource();
            Document doc = new Document(original);
            edits.apply(doc);
            String newSource = doc.get();

            // Ensure the java.util.Objects import textually so the staged and
            // applied paths produce identical, compilable source.
            if (!newSource.contains("import java.util.Objects;")) {
                int pkgEnd = newSource.indexOf(";");
                if (newSource.startsWith("package ") && pkgEnd > 0) {
                    newSource = newSource.substring(0, pkgEnd + 1)
                        + "\n\nimport java.util.Objects;"
                        + newSource.substring(pkgEnd + 1);
                } else {
                    newSource = "import java.util.Objects;\n" + newSource;
                }
            }

            boolean autoApply = getBooleanParam(arguments, "auto_apply", true);
            if (!autoApply) {
                SourceCommit.Staged staged = SourceCommit.stageFullReplace(
                    cu, newSource, "generate_equals_hashcode", changeCache, service);
                Map<String, Object> stagedData = new LinkedHashMap<>();
                stagedData.put("operation", "generate_equals_hashcode");
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
                cu, newSource, "generate_equals_hashcode", changeCache, service);
            String finalSource = cu.getSource();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "generate_equals_hashcode");
            data.put("filePath", service.getPathUtils().formatPath(
                cu.getResource().getLocation().toFile().toPath()));
            data.put("methodsAdded", methodsAdded);
            data.put("warnings", warnings);
            data.put("generatedSource", finalSource);

            data.put("applied", true);
            data.put("filesModified", List.of(committed.filePath()));
            data.put("diff", committed.diff());
            data.put("undoChangeId", committed.undoChangeId());

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(methodsAdded.size())
                .returnedCount(methodsAdded.size())
                .build());
        } catch (Exception e) {
            log.warn("generate_equals_hashcode failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private static MethodDeclaration buildEquals(AST ast, String typeName, List<FieldInfo> fields) {
        MethodDeclaration equals = ast.newMethodDeclaration();
        equals.setName(ast.newSimpleName("equals"));
        equals.setReturnType2(ast.newPrimitiveType(PrimitiveType.BOOLEAN));
        @SuppressWarnings("unchecked")
        List<Object> mods = equals.modifiers();
        MarkerAnnotation override = ast.newMarkerAnnotation();
        override.setTypeName(ast.newSimpleName("Override"));
        mods.add(override);
        mods.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));

        SingleVariableDeclaration param = ast.newSingleVariableDeclaration();
        param.setType(ast.newSimpleType(ast.newSimpleName("Object")));
        param.setName(ast.newSimpleName("obj"));
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = equals.parameters();
        params.add(param);

        Block body = ast.newBlock();
        @SuppressWarnings("unchecked")
        List<Object> stmts = body.statements();

        // if (this == obj) return true;
        IfStatement ifSelf = ast.newIfStatement();
        InfixExpression selfCmp = ast.newInfixExpression();
        selfCmp.setLeftOperand(ast.newThisExpression());
        selfCmp.setOperator(InfixExpression.Operator.EQUALS);
        selfCmp.setRightOperand(ast.newSimpleName("obj"));
        ifSelf.setExpression(selfCmp);
        ReturnStatement retTrue = ast.newReturnStatement();
        retTrue.setExpression(ast.newBooleanLiteral(true));
        ifSelf.setThenStatement(retTrue);
        stmts.add(ifSelf);

        // if (obj == null || getClass() != obj.getClass()) return false;
        IfStatement ifNullOrClass = ast.newIfStatement();
        InfixExpression nullCheck = ast.newInfixExpression();
        nullCheck.setLeftOperand(ast.newSimpleName("obj"));
        nullCheck.setOperator(InfixExpression.Operator.EQUALS);
        nullCheck.setRightOperand(ast.newNullLiteral());

        MethodInvocation thisGetClass = ast.newMethodInvocation();
        thisGetClass.setName(ast.newSimpleName("getClass"));
        MethodInvocation objGetClass = ast.newMethodInvocation();
        objGetClass.setExpression(ast.newSimpleName("obj"));
        objGetClass.setName(ast.newSimpleName("getClass"));
        InfixExpression classCheck = ast.newInfixExpression();
        classCheck.setLeftOperand(thisGetClass);
        classCheck.setOperator(InfixExpression.Operator.NOT_EQUALS);
        classCheck.setRightOperand(objGetClass);

        InfixExpression combined = ast.newInfixExpression();
        combined.setLeftOperand(nullCheck);
        combined.setOperator(InfixExpression.Operator.CONDITIONAL_OR);
        combined.setRightOperand(classCheck);
        ifNullOrClass.setExpression(combined);
        ReturnStatement retFalse = ast.newReturnStatement();
        retFalse.setExpression(ast.newBooleanLiteral(false));
        ifNullOrClass.setThenStatement(retFalse);
        stmts.add(ifNullOrClass);

        // <Type> other = (<Type>) obj;
        VariableDeclarationFragment frag = ast.newVariableDeclarationFragment();
        frag.setName(ast.newSimpleName("other"));
        CastExpression cast = ast.newCastExpression();
        cast.setType(ast.newSimpleType(ast.newSimpleName(typeName)));
        cast.setExpression(ast.newSimpleName("obj"));
        frag.setInitializer(cast);
        VariableDeclarationStatement varDecl = ast.newVariableDeclarationStatement(frag);
        varDecl.setType(ast.newSimpleType(ast.newSimpleName(typeName)));
        stmts.add(varDecl);

        // return Objects.equals(this.f, other.f) && ... ;  (or this.f == other.f for primitives)
        if (fields.isEmpty()) {
            ReturnStatement ret = ast.newReturnStatement();
            ret.setExpression(ast.newBooleanLiteral(true));
            stmts.add(ret);
        } else {
            ReturnStatement ret = ast.newReturnStatement();
            ret.setExpression(buildEqualsExpr(ast, fields, 0));
            stmts.add(ret);
        }

        equals.setBody(body);
        return equals;
    }

    private static org.eclipse.jdt.core.dom.Expression buildEqualsExpr(AST ast, List<FieldInfo> fields, int idx) {
        if (idx == fields.size() - 1) {
            return fieldEqualityExpr(ast, fields.get(idx));
        }
        InfixExpression and = ast.newInfixExpression();
        and.setOperator(InfixExpression.Operator.CONDITIONAL_AND);
        and.setLeftOperand(fieldEqualityExpr(ast, fields.get(idx)));
        and.setRightOperand(buildEqualsExpr(ast, fields, idx + 1));
        return and;
    }

    private static org.eclipse.jdt.core.dom.Expression fieldEqualityExpr(AST ast, FieldInfo fi) {
        if (fi.primitive) {
            // this.field == other.field
            FieldAccess thisAcc = ast.newFieldAccess();
            thisAcc.setExpression(ast.newThisExpression());
            thisAcc.setName(ast.newSimpleName(fi.name));
            FieldAccess otherAcc = ast.newFieldAccess();
            otherAcc.setExpression(ast.newSimpleName("other"));
            otherAcc.setName(ast.newSimpleName(fi.name));
            InfixExpression cmp = ast.newInfixExpression();
            cmp.setLeftOperand(thisAcc);
            cmp.setOperator(InfixExpression.Operator.EQUALS);
            cmp.setRightOperand(otherAcc);
            return cmp;
        }
        // Objects.equals(this.field, other.field)
        MethodInvocation call = ast.newMethodInvocation();
        call.setExpression(ast.newSimpleName("Objects"));
        call.setName(ast.newSimpleName("equals"));
        FieldAccess thisAcc = ast.newFieldAccess();
        thisAcc.setExpression(ast.newThisExpression());
        thisAcc.setName(ast.newSimpleName(fi.name));
        FieldAccess otherAcc = ast.newFieldAccess();
        otherAcc.setExpression(ast.newSimpleName("other"));
        otherAcc.setName(ast.newSimpleName(fi.name));
        @SuppressWarnings("unchecked")
        List<Object> args = call.arguments();
        args.add(thisAcc);
        args.add(otherAcc);
        return call;
    }

    private static MethodDeclaration buildHashCode(AST ast, List<FieldInfo> fields) {
        MethodDeclaration hashCode = ast.newMethodDeclaration();
        hashCode.setName(ast.newSimpleName("hashCode"));
        hashCode.setReturnType2(ast.newPrimitiveType(PrimitiveType.INT));
        @SuppressWarnings("unchecked")
        List<Object> mods = hashCode.modifiers();
        MarkerAnnotation override = ast.newMarkerAnnotation();
        override.setTypeName(ast.newSimpleName("Override"));
        mods.add(override);
        mods.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));

        Block body = ast.newBlock();
        @SuppressWarnings("unchecked")
        List<Object> stmts = body.statements();

        MethodInvocation hash = ast.newMethodInvocation();
        hash.setExpression(ast.newSimpleName("Objects"));
        hash.setName(ast.newSimpleName("hash"));
        @SuppressWarnings("unchecked")
        List<Object> args = hash.arguments();
        for (FieldInfo fi : fields) {
            args.add(ast.newSimpleName(fi.name));
        }

        ReturnStatement ret = ast.newReturnStatement();
        ret.setExpression(hash);
        stmts.add(ret);

        hashCode.setBody(body);
        return hashCode;
    }

    private static boolean isPrimitive(String typeName) {
        return switch (typeName) {
            case "boolean", "byte", "char", "double", "float", "int", "long", "short" -> true;
            default -> false;
        };
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

    private record FieldInfo(String name, String typeName, boolean primitive) {}
}
