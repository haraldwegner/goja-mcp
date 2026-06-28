package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.TextEdit;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.fqn.FqnResolver;
import org.goja.mcp.tools.nullness.NullnessStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 15b — source-mutating null-safety tool (apply/undo contract via
 * {@link AbstractApplyingRefactoringTool}). Read-only null analysis lives in
 * {@code analyze_nullness}.
 *
 * <p>Kinds: {@code add} (this stage) inserts a {@code @Nullable}/{@code @NonNull}
 * annotation (+ import) on a method return, a parameter, or a field, in the
 * project's detected style (default JSpecify). {@code migrate} follows.</p>
 *
 * <p><b>Public-API guard:</b> annotating an exported (public/protected) member is
 * a breaking downstream contract change, so it is refused unless
 * {@code allowPublicApi:true} is passed.</p>
 */
public class ApplyNullAnnotationsTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ApplyNullAnnotationsTool.class);

    static final Set<String> KINDS = Set.of("add", "migrate");

    public ApplyNullAnnotationsTool(Supplier<IJdtService> serviceSupplier,
                                    RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "apply_null_annotations";
    }

    @Override
    public String getDescription() {
        return """
            Apply nullness annotations via the apply/undo contract (returns
            { filesModified, diff, undoChangeId, summary }; auto_apply:false to stage).

            USAGE: apply_null_annotations(kind="add", symbol="com.foo.Bar#method",
                                          nullness="nullable" [, parameter="x"])

            KINDS:
            - add     — insert @Nullable/@NonNull (+ import) on a method return, a parameter
                        (pass `parameter`), or a field. `style` defaults to the project's detected
                        family, else JSpecify. PUBLIC-API GUARD: a public/protected target is
                        refused unless allowPublicApi:true (breaking downstream contract).
            - migrate — convert nullness annotations from one family to another (e.g.
                        from="JETBRAINS" to="JSPECIFY"): swaps the @Nullable/@NonNull usages +
                        imports. REFUSES ambiguous conversions (default-scoping annotations like
                        @NonNullByDefault/@NullMarked, wildcard imports, fully-qualified usages)
                        — returns a manual-review result rather than guessing.

            Params (add): symbol (FQN, required), nullness (required), parameter, style,
            allowPublicApi. Params (migrate): from (family, required), to (family, default
            JSPECIFY), filePath (optional scope). Requires load_project first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("kind", Map.of("type", "string", "enum", KINDS,
            "description", "Which mutation to apply. See the tool description."));
        properties.put("symbol", Map.of("type", "string",
            "description", "FQN target: \"com.foo.Bar#method\" or \"com.foo.Bar#field\"."));
        properties.put("nullness", Map.of("type", "string", "enum", Set.of("nullable", "nonnull"),
            "description", "Which annotation to add."));
        properties.put("parameter", Map.of("type", "string",
            "description", "Optional. For a method target, annotate this parameter instead of the return."));
        properties.put("style", Map.of("type", "string",
            "description", "Optional nullness family override (JSPECIFY/ECLIPSE/JETBRAINS/...). Default: detected, else JSpecify."));
        properties.put("allowPublicApi", Map.of("type", "boolean",
            "description", "Required true to annotate a public/protected (exported) target."));
        properties.put("from", Map.of("type", "string",
            "description", "For kind=migrate: source nullness family (JSPECIFY/ECLIPSE/JETBRAINS/JSR305/...)."));
        properties.put("to", Map.of("type", "string",
            "description", "For kind=migrate: target family (default JSPECIFY)."));
        properties.put("filePath", Map.of("type", "string",
            "description", "For kind=migrate: optional scope to one file; omit to migrate the project."));
        schema.put("properties", properties);
        schema.put("required", List.of("kind"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || !KINDS.contains(kind)) {
            return Preparation.fail(ToolResponse.invalidParameter("kind", "one of " + KINDS));
        }
        return "migrate".equals(kind) ? prepareMigrate(service, arguments)
            : prepareAdd(service, arguments);
    }

    private Preparation prepareMigrate(IJdtService service, JsonNode arguments) throws Exception {
        String fromRaw = getStringParam(arguments, "from");
        if (fromRaw == null || fromRaw.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("from",
                "required source family, e.g. \"JETBRAINS\" or \"JSR305\"."));
        }
        NullnessStyle from;
        try {
            from = NullnessStyle.valueOf(fromRaw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Preparation.fail(ToolResponse.invalidParameter("from", "unknown family '" + fromRaw + "'."));
        }
        String toRaw = getStringParam(arguments, "to");
        NullnessStyle to = NullnessStyle.DEFAULT;
        if (toRaw != null && !toRaw.isBlank()) {
            try {
                to = NullnessStyle.valueOf(toRaw.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Preparation.fail(ToolResponse.invalidParameter("to", "unknown family '" + toRaw + "'."));
            }
        }
        if (from == to) {
            return Preparation.fail(ToolResponse.invalidParameter("from/to", "from and to are the same family."));
        }

        final NullnessStyle fromStyle = from;
        final NullnessStyle toStyle = to;

        String filePath = getStringParam(arguments, "filePath");
        List<java.nio.file.Path> targets = new ArrayList<>();
        if (filePath != null && !filePath.isBlank()) {
            java.nio.file.Path p = java.nio.file.Path.of(filePath);
            if (service.getCompilationUnit(p) == null) {
                return Preparation.fail(ToolResponse.fileNotFound(filePath));
            }
            targets.add(p);
        } else {
            targets.addAll(service.getAllJavaFiles());
        }

        Map<IFile, List<TextEdit>> editsByFile = new LinkedHashMap<>();
        int filesChanged = 0;
        for (java.nio.file.Path path : targets) {
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                continue;
            }
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);

            // Ambiguity gate: refuse anything beyond the simple Nullable/NonNull pair.
            boolean usesFrom = false;
            for (Object o : ast.imports()) {
                ImportDeclaration imp = (ImportDeclaration) o;
                String fqn = imp.getName().getFullyQualifiedName();
                if (fqn.equals(from.pkg) || fqn.startsWith(from.pkg + ".")) {
                    usesFrom = true;
                    if (imp.isOnDemand()) {
                        return Preparation.fail(ambiguous(from, "a wildcard import of " + from.pkg
                            + ".* in " + service.getPathUtils().formatPath(path)));
                    }
                    String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
                    if (!simple.equals(from.nullable) && !simple.equals(from.nonnull)) {
                        return Preparation.fail(ambiguous(from, "the non-pair annotation @" + simple
                            + " in " + service.getPathUtils().formatPath(path)));
                    }
                }
            }
            if (!usesFrom) {
                continue;
            }

            ImportRewrite ir = ImportRewrite.create(cu, true);
            ASTRewrite rw = ASTRewrite.create(ast.getAST());
            AST factory = ast.getAST();
            boolean[] usedNullable = {false};
            boolean[] usedNonnull = {false};
            String[] refusal = {null};
            ast.accept(new ASTVisitor() {
                private boolean handle(Annotation node) {
                    Name typeName = node.getTypeName();
                    String full = typeName.getFullyQualifiedName();
                    String simple = full.contains(".") ? full.substring(full.lastIndexOf('.') + 1) : full;
                    boolean qualified = full.contains(".");
                    if (qualified && (full.equals(fromStyle.pkg + "." + fromStyle.nullable)
                            || full.equals(fromStyle.pkg + "." + fromStyle.nonnull))) {
                        refusal[0] = "a fully-qualified usage @" + full;
                        return false;
                    }
                    if (simple.equals(fromStyle.nullable)) {
                        usedNullable[0] = true;
                        if (!fromStyle.nullable.equals(toStyle.nullable)) {
                            rw.replace(typeName, factory.newSimpleName(toStyle.nullable), null);
                        }
                    } else if (simple.equals(fromStyle.nonnull)) {
                        usedNonnull[0] = true;
                        if (!fromStyle.nonnull.equals(toStyle.nonnull)) {
                            rw.replace(typeName, factory.newSimpleName(toStyle.nonnull), null);
                        }
                    }
                    return true;
                }

                @Override
                public boolean visit(MarkerAnnotation node) { return handle(node); }

                @Override
                public boolean visit(org.eclipse.jdt.core.dom.NormalAnnotation node) { return handle(node); }

                @Override
                public boolean visit(org.eclipse.jdt.core.dom.SingleMemberAnnotation node) { return handle(node); }
            });
            if (refusal[0] != null) {
                return Preparation.fail(ambiguous(from, refusal[0]));
            }

            if (usedNullable[0]) {
                ir.removeImport(from.nullableFqn());
                ir.addImport(to.nullableFqn());
            }
            if (usedNonnull[0]) {
                ir.removeImport(from.nonnullFqn());
                ir.addImport(to.nonnullFqn());
            }
            List<TextEdit> edits = new ArrayList<>();
            edits.add(ir.rewriteImports(null));
            edits.add(rw.rewriteAST());
            editsByFile.put((IFile) cu.getResource(), edits);
            filesChanged++;
        }

        if (editsByFile.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", getName());
            data.put("applied", false);
            data.put("hasChanges", false);
            data.put("reason", "no " + from.name() + " nullness usages in scope");
            return Preparation.fail(ToolResponse.success(data, org.goja.mcp.models.ResponseMeta.builder().build()));
        }

        Change change = ChangeEngine.fromFileEdits("migrate nullness " + from.name() + "→" + to.name(),
            editsByFile);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("from", from.name());
        extras.put("to", to.name());
        extras.put("filesChanged", filesChanged);
        return Preparation.of(change,
            "migrate nullness annotations " + from.name() + "→" + to.name()
                + " (" + filesChanged + " file(s))", extras);
    }

    private static ToolResponse ambiguous(NullnessStyle from, String detail) {
        return ToolResponse.error("MIGRATION_AMBIGUOUS",
            "Refusing to migrate " + from.name() + " automatically — " + detail + ".",
            "Family semantics are not 1:1 here; convert this construct manually.");
    }

    private Preparation prepareAdd(IJdtService service, JsonNode arguments) throws Exception {
        String symbol = getStringParam(arguments, "symbol");
        if (symbol == null || symbol.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("symbol",
                "required FQN, e.g. \"com.foo.Bar#method\" or \"com.foo.Bar#field\"."));
        }
        String nullness = getStringParam(arguments, "nullness");
        if (!"nullable".equals(nullness) && !"nonnull".equals(nullness)) {
            return Preparation.fail(ToolResponse.invalidParameter("nullness",
                "must be \"nullable\" or \"nonnull\"."));
        }
        String paramName = getStringParam(arguments, "parameter");
        boolean allowPublic = getBooleanParam(arguments, "allowPublicApi", false);

        Optional<IJavaElement> resolved = FqnResolver.resolveWorkspace(symbol, service);
        if (resolved.isEmpty()) {
            return Preparation.fail(ToolResponse.symbolNotFound(symbol));
        }
        IJavaElement element = resolved.get();
        if (!(element instanceof IMember member)) {
            return Preparation.fail(ToolResponse.invalidParameter("symbol",
                "target must be a method or field."));
        }

        // Public-API guard.
        int flags = member.getFlags();
        if ((Flags.isPublic(flags) || Flags.isProtected(flags)) && !allowPublic) {
            return Preparation.fail(ToolResponse.error("REFACTORING_REFUSED",
                "Refusing to annotate the exported (public/protected) target " + symbol
                    + " — this is a breaking downstream contract change.",
                "Pass allowPublicApi:true to proceed intentionally."));
        }

        NullnessStyle style = resolveStyle(service, getStringParam(arguments, "style"));
        String annFqn = "nullable".equals(nullness) ? style.nullableFqn() : style.nonnullFqn();

        ICompilationUnit cu = member.getCompilationUnit();
        if (cu == null) {
            return Preparation.fail(ToolResponse.invalidParameter("symbol", "target has no source file."));
        }

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        AST factory = ast.getAST();
        ASTRewrite rewrite = ASTRewrite.create(factory);
        ImportRewrite importRewrite = ImportRewrite.create(cu, true);
        String ref = importRewrite.addImport(annFqn);

        boolean isMethod = member instanceof IMethod;
        String memberName = member.getElementName();
        int paramCount = member instanceof IMethod m ? m.getNumberOfParameters() : -1;
        boolean[] applied = {false};

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (!isMethod || applied[0]
                        || !node.getName().getIdentifier().equals(memberName)
                        || node.parameters().size() != paramCount) {
                    return true;
                }
                if (paramName != null && !paramName.isBlank()) {
                    for (Object o : node.parameters()) {
                        SingleVariableDeclaration p = (SingleVariableDeclaration) o;
                        if (p.getName().getIdentifier().equals(paramName)) {
                            insert(rewrite, p, SingleVariableDeclaration.MODIFIERS2_PROPERTY, ref, factory);
                            applied[0] = true;
                            return false;
                        }
                    }
                } else {
                    insert(rewrite, node, MethodDeclaration.MODIFIERS2_PROPERTY, ref, factory);
                    applied[0] = true;
                    return false;
                }
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                if (isMethod || applied[0]) {
                    return true;
                }
                for (Object o : node.fragments()) {
                    if (((VariableDeclarationFragment) o).getName().getIdentifier().equals(memberName)) {
                        insert(rewrite, node, FieldDeclaration.MODIFIERS2_PROPERTY, ref, factory);
                        applied[0] = true;
                        return false;
                    }
                }
                return true;
            }
        });

        if (!applied[0]) {
            return Preparation.fail(ToolResponse.invalidParameter("symbol/parameter",
                "could not locate the target declaration to annotate."));
        }

        List<TextEdit> edits = new ArrayList<>();
        edits.add(importRewrite.rewriteImports(null));
        edits.add(rewrite.rewriteAST());
        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits("add @" + ref, Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("style", style.name());
        extras.put("nullness", nullness);
        extras.put("annotation", annFqn);
        extras.put("target", paramName != null && !paramName.isBlank() ? "param:" + paramName
            : isMethod ? "return" : "field");
        String summary = "add @" + ref + " to " + symbol
            + (paramName != null && !paramName.isBlank() ? " parameter " + paramName : "");
        return Preparation.of(change, summary, extras);
    }

    private static void insert(ASTRewrite rewrite, org.eclipse.jdt.core.dom.ASTNode node,
                               org.eclipse.jdt.core.dom.ChildListPropertyDescriptor prop,
                               String ref, AST factory) {
        MarkerAnnotation ann = factory.newMarkerAnnotation();
        ann.setTypeName(factory.newName(ref));
        ListRewrite lr = rewrite.getListRewrite(node, prop);
        lr.insertFirst(ann, null);
    }

    /** Explicit style override, else the project's detected family, else JSpecify. */
    private NullnessStyle resolveStyle(IJdtService service, String override) throws Exception {
        if (override != null && !override.isBlank()) {
            try {
                return NullnessStyle.valueOf(override.toUpperCase());
            } catch (IllegalArgumentException ignore) {
                // fall through to detection
            }
        }
        Map<NullnessStyle, Integer> totals = new java.util.EnumMap<>(NullnessStyle.class);
        for (java.nio.file.Path path : service.getAllJavaFiles()) {
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                continue;
            }
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);
            List<String> imports = new ArrayList<>();
            for (Object o : ast.imports()) {
                imports.add(((org.eclipse.jdt.core.dom.ImportDeclaration) o).getName().getFullyQualifiedName());
            }
            NullnessStyle.tally(imports).forEach((s, n) -> totals.merge(s, n, Integer::sum));
        }
        NullnessStyle dominant = NullnessStyle.DEFAULT;
        int best = 0;
        for (Map.Entry<NullnessStyle, Integer> e : totals.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                dominant = e.getKey();
            }
        }
        return dominant;
    }
}
