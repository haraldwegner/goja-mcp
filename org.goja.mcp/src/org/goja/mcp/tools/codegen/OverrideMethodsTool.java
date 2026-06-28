package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.ThrowStatement;
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
 * Sprint 13 (v1.7.0) — Ring 2 codegen: {@code override_methods}.
 *
 * <p>Two modes:</p>
 * <ul>
 *   <li>Query mode (no {@code methods} arg): walks the supertype hierarchy
 *       and returns {@code availableMethods[]} listing overridable
 *       signatures. No source modification.</li>
 *   <li>Generate mode ({@code methods=["name(paramType,...)"]} or
 *       {@code methods=["name"]}): inserts {@code @Override}-annotated
 *       stubs whose body throws
 *       {@code UnsupportedOperationException("not yet implemented")}.</li>
 * </ul>
 *
 * <p>Skips {@code Object} methods (toString / equals / hashCode / clone /
 * etc.) from the candidate list unless the user asks for them by name —
 * keeps the default listing focused on the user's domain types.</p>
 */
public class OverrideMethodsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(OverrideMethodsTool.class);

    private final org.goja.mcp.refactoring.RefactoringChangeCache changeCache;

    public OverrideMethodsTool(Supplier<IJdtService> serviceSupplier,
                              RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "override_methods";
    }

    @Override
    public String getDescription() {
        return """
            Add @Override-annotated stub methods on a target class for any
            method declared by a supertype or interface. Default body throws
            UnsupportedOperationException("not yet implemented").

            Two modes:
            - Query: omit `methods` to receive availableMethods[] listing
              overridable signatures (excludes java.lang.Object members by
              default).
            - Generate: pass `methods` as method names ("describe") or
              signatures ("describe()") to add stubs.

            USAGE:
              override_methods(filePath="...", line=10, column=14)
              override_methods(filePath="...", line=10, column=14,
                               methods=["describe"])

            Result:
              query mode: { operation, availableMethods[] }
              generate mode: { operation, filePath, methodsAdded[],
                               warnings[], generatedSource, modifiedFiles }
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
        properties.put("methods", Map.of(
            "type", "array",
            "items", Map.of("type", "string"),
            "description", "Method names or simple signatures. Omit for query mode."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse missing = requireParam(arguments, "filePath");
        if (missing != null) return missing;

        String filePathRaw = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", 0);
        int column = getIntParam(arguments, "column", 0);

        List<String> requested = new ArrayList<>();
        boolean queryMode = true;
        if (arguments.has("methods") && arguments.get("methods").isArray()) {
            JsonNode methodsNode = arguments.get("methods");
            for (JsonNode n : methodsNode) {
                String s = n.asText();
                if (s != null && !s.isBlank()) requested.add(s.trim());
            }
            if (!requested.isEmpty()) queryMode = false;
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

            List<IMethod> candidates = collectOverridableMethods(type, queryMode);

            if (queryMode) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", "override_methods");
                List<String> available = new ArrayList<>();
                for (IMethod m : candidates) {
                    available.add(formatMethodSignature(m));
                }
                data.put("availableMethods", available);
                return ToolResponse.success(data, ResponseMeta.builder()
                    .totalCount(available.size())
                    .returnedCount(available.size())
                    .build());
            }

            // Generate mode: parse the CU and rewrite.
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

            for (String requestedName : requested) {
                IMethod match = findMatch(candidates, requestedName);
                if (match == null) {
                    // Be lenient: include Object methods if requested by name.
                    match = findObjectMethod(type, requestedName);
                }
                if (match == null) {
                    warnings.add("No overridable method matching '" + requestedName + "'");
                    continue;
                }
                if (methodAlreadyExists(type, match)) {
                    warnings.add("Skipped '" + match.getElementName() + "' — already declared on target");
                    continue;
                }
                bodyRewrite.insertLast(buildOverrideStub(ast, match), null);
                methodsAdded.add(match.getElementName());
            }

            TextEdit edits = rewrite.rewriteAST();
            String original = cu.getSource();
            Document doc = new Document(original);
            edits.apply(doc);
            String newSource = doc.get();

            boolean autoApply = getBooleanParam(arguments, "auto_apply", true);
            if (!autoApply) {
                SourceCommit.Staged staged = SourceCommit.stageFullReplace(
                    cu, newSource, "override_methods", changeCache, service);
                Map<String, Object> stagedData = new LinkedHashMap<>();
                stagedData.put("operation", "override_methods");
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
                cu, newSource, "override_methods", changeCache, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "override_methods");
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
            log.warn("override_methods failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private static List<IMethod> collectOverridableMethods(IType type, boolean excludeObject) throws Exception {
        ITypeHierarchy hierarchy = type.newSupertypeHierarchy(new NullProgressMonitor());
        IType[] supertypes = hierarchy.getAllSupertypes(type);
        List<IMethod> out = new ArrayList<>();
        for (IType st : supertypes) {
            if (excludeObject && "java.lang.Object".equals(st.getFullyQualifiedName())) {
                continue;
            }
            for (IMethod m : st.getMethods()) {
                if (m.isConstructor()) continue;
                int flags = m.getFlags();
                if (Flags.isPrivate(flags) || Flags.isStatic(flags) || Flags.isFinal(flags)) {
                    continue;
                }
                out.add(m);
            }
        }
        return out;
    }

    private static IMethod findObjectMethod(IType type, String name) throws Exception {
        ITypeHierarchy hierarchy = type.newSupertypeHierarchy(new NullProgressMonitor());
        for (IType st : hierarchy.getAllSupertypes(type)) {
            if (!"java.lang.Object".equals(st.getFullyQualifiedName())) continue;
            for (IMethod m : st.getMethods()) {
                if (matchByNameOrSignature(m, name)) return m;
            }
        }
        return null;
    }

    private static IMethod findMatch(List<IMethod> candidates, String requested) {
        for (IMethod m : candidates) {
            if (matchByNameOrSignature(m, requested)) return m;
        }
        return null;
    }

    private static boolean matchByNameOrSignature(IMethod m, String requested) {
        String reqName = requested;
        int parenIdx = requested.indexOf('(');
        if (parenIdx > 0) {
            reqName = requested.substring(0, parenIdx);
        }
        if (!m.getElementName().equals(reqName)) return false;
        // If the requested string includes parens, match by parameter count
        // (simple heuristic; we don't try to resolve param types).
        if (parenIdx > 0) {
            String inside = requested.substring(parenIdx + 1, requested.length() - 1).trim();
            int requestedParams = inside.isEmpty()
                ? 0
                : (int) inside.chars().filter(c -> c == ',').count() + 1;
            try {
                if (m.getNumberOfParameters() != requestedParams) return false;
            } catch (Exception ignore) {}
        }
        return true;
    }

    private static String formatMethodSignature(IMethod method) {
        try {
            StringBuilder sb = new StringBuilder(method.getElementName()).append('(');
            String[] paramTypes = method.getParameterTypes();
            for (int i = 0; i < paramTypes.length; i++) {
                if (i > 0) sb.append(", ");
                sb.append(Signature.toString(paramTypes[i]));
            }
            sb.append(')');
            return sb.toString();
        } catch (Exception e) {
            return method.getElementName() + "(?)";
        }
    }

    private static MethodDeclaration buildOverrideStub(AST ast, IMethod source) throws Exception {
        MethodDeclaration md = ast.newMethodDeclaration();
        md.setName(ast.newSimpleName(source.getElementName()));
        md.setReturnType2(buildType(ast, Signature.toString(source.getReturnType())));

        @SuppressWarnings("unchecked")
        List<Object> mods = md.modifiers();
        MarkerAnnotation override = ast.newMarkerAnnotation();
        override.setTypeName(ast.newSimpleName("Override"));
        mods.add(override);
        mods.add(ast.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));

        String[] paramTypes = source.getParameterTypes();
        String[] paramNames = source.getParameterNames();
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = md.parameters();
        for (int i = 0; i < paramTypes.length; i++) {
            SingleVariableDeclaration p = ast.newSingleVariableDeclaration();
            p.setType(buildType(ast, Signature.toString(paramTypes[i])));
            String name = (paramNames != null && i < paramNames.length && paramNames[i] != null
                && !paramNames[i].isBlank()) ? paramNames[i] : "arg" + i;
            p.setName(ast.newSimpleName(name));
            params.add(p);
        }

        Block body = ast.newBlock();
        @SuppressWarnings("unchecked")
        List<Object> stmts = body.statements();

        ThrowStatement throwStmt = ast.newThrowStatement();
        ClassInstanceCreation create = ast.newClassInstanceCreation();
        create.setType(ast.newSimpleType(ast.newSimpleName("UnsupportedOperationException")));
        StringLiteral msg = ast.newStringLiteral();
        msg.setLiteralValue("not yet implemented");
        @SuppressWarnings("unchecked")
        List<Object> ctorArgs = create.arguments();
        ctorArgs.add(msg);
        throwStmt.setExpression(create);
        stmts.add(throwStmt);
        md.setBody(body);

        return md;
    }

    private static boolean methodAlreadyExists(IType type, IMethod candidate) {
        try {
            for (IMethod existing : type.getMethods()) {
                if (existing.getElementName().equals(candidate.getElementName())
                    && existing.getNumberOfParameters() == candidate.getNumberOfParameters()) {
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
}
