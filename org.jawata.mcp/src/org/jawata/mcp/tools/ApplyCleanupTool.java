package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.SourceScan;

import java.util.Optional;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 15 — parametric headless clean-up catalog (upstream v1.4.2 harvest).
 * Applies safe, mechanical source clean-ups via the standard apply/undo
 * contract ({@link AbstractApplyingRefactoringTool}).
 *
 * <p>Deliberately scoped to clean-ups the existing surface does NOT already
 * cover: {@code organize_imports} (imports), {@code format} (whitespace),
 * {@code apply_quick_fix} (compiler quick-fixes) and {@code find_modernization}
 * (language-idiom upgrades — find-only). The two kinds here are the canonical
 * non-overlapping ones:</p>
 *
 * <ul>
 *   <li>{@code add_final} — mark method/constructor parameters and local
 *       variable declarations {@code final} when they are never reassigned.
 *       Reassignment is detected by binding (not name), so shadowing can't
 *       produce a false positive that would break compilation.</li>
 *   <li>{@code redundant_modifiers} — strip modifiers that are implicit on
 *       interface members ({@code public}/{@code abstract} on methods,
 *       {@code public}/{@code static}/{@code final} on fields,
 *       {@code public}/{@code static} on nested types).</li>
 * </ul>
 *
 * <p>{@code filePath} scopes to one file; omit it to sweep the whole project.
 * A no-op (nothing to clean) returns {@code hasChanges: false} without touching
 * anything.</p>
 */
public class ApplyCleanupTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ApplyCleanupTool.class);

    static final Set<String> KINDS = Set.of("add_final", "redundant_modifiers");

    public ApplyCleanupTool(Supplier<IJdtService> serviceSupplier,
                            RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "apply_cleanup";
    }

    @Override
    public String getDescription() {
        return """
            Apply a safe, mechanical source clean-up across a file or project.
            Auto-applies by default and returns
            { filesModified, diff, undoChangeId, summary }; pass auto_apply:false
            to stage instead. A no-op returns hasChanges:false.

            USAGE: apply_cleanup(kind="<kind>")  — whole default project
                   apply_cleanup(kind="<kind>", filePath="path/to/File.java")

            KINDS:
            - add_final           — mark parameters and local variables `final`
                                    when never reassigned (binding-checked, so it
                                    never breaks compilation).
            - redundant_modifiers — remove modifiers that are implicit on interface
                                    members (public/abstract methods, public/static/
                                    final fields, public/static nested types).

            This catalog is intentionally non-overlapping with organize_imports,
            format, apply_quick_fix and find_modernization. Optional: projectKey
            to scope a project-wide sweep. Requires load_project first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", KINDS);
        kind.put("description", "Which clean-up to apply. See the tool description.");
        properties.put("kind", kind);
        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "Optional. Restrict to one file; omit to sweep the whole project.");
        properties.put("filePath", filePath);
        schema.put("properties", properties);
        schema.put("required", List.of("kind"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("kind",
                "kind is required; one of " + KINDS));
        }
        if (!KINDS.contains(kind)) {
            return Preparation.fail(ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS));
        }

        List<Path> targets = new ArrayList<>();
        String filePath = getStringParam(arguments, "filePath");
        if (filePath != null && !filePath.isBlank()) {
            Path path = Path.of(filePath);
            if (service.getCompilationUnit(path) == null) {
                return Preparation.fail(ToolResponse.fileNotFound(filePath));
            }
            targets.add(path);
        } else {
            targets.addAll(service.getAllJavaFiles());
        }

        Map<IFile, List<TextEdit>> editsByFile = new LinkedHashMap<>();
        int totalEdits = 0;
        // A cleanup SWEEP that silently skips the files it cannot read, and then reports
        // "filesScanned: <every file we listed>", tells you the project is clean when it has
        // not looked at parts of it. For a tool that CHANGES your code, that is the worst
        // version of this bug: you believe the sweep is done.
        SourceScan scan = SourceScan.of(targets);
        for (Path path : scan.files()) {
            ICompilationUnit cu = scan.resolve(service, path);
            if (cu == null) {
                continue;   // RECORDED — reported below, never silently dropped
            }
            CompilationUnit ast = scan.parse(cu, path, true);
            if (ast == null) {
                continue;
            }
            scan.examined();

            ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
            int edits = "add_final".equals(kind)
                ? rewriteAddFinal(ast, rewrite)
                : rewriteRedundantModifiers(ast, rewrite);
            if (edits == 0) {
                continue;
            }
            TextEdit edit = rewrite.rewriteAST();
            if (!edit.hasChildren()) {
                continue;
            }
            List<TextEdit> list = new ArrayList<>();
            list.add(edit);
            editsByFile.put((IFile) cu.getResource(), list);
            totalEdits += edits;
        }

        // "Nothing to clean up" is only sayable if we managed to read the code.
        Optional<ToolResponse> blind = scan.refuseIfBlind("code to clean up");
        if (blind.isPresent()) {
            return Preparation.fail(blind.get());
        }

        if (editsByFile.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", getName());
            data.put("applied", false);
            data.put("hasChanges", false);
            data.put("kind", kind);
            // filesScanned USED TO BE targets.size() — the number of files we LISTED, which
            // claimed we had scanned files we skipped. It is now the number we actually read.
            data.put("filesScanned", scan.examinedCount());
            data.putAll(scan.describe());
            return Preparation.fail(ToolResponse.success(data, ResponseMeta.builder()
                .steering(scan.steering(0, "code to clean up"))
                .suggestedNextTools(List.of("get_diagnostics to check for remaining issues"))
                .build()));
        }

        Change change = ChangeEngine.fromFileEdits("apply_cleanup " + kind, editsByFile);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("kind", kind);
        extras.put("hasChanges", true);
        extras.put("filesChanged", editsByFile.size());
        extras.put("editCount", totalEdits);
        extras.putAll(scan.describe());
        if (scan.incomplete()) {
            // The sweep is going ahead — the edits it DID find are real and safe. But the
            // caller must not walk away believing the project has been swept.
            extras.put("sweepIncomplete", true);
            extras.put("warning", scan.missed() + " file(s) could not be read and were NOT "
                + "cleaned. This cleanup is PARTIAL — run refresh_workspace and repeat to "
                + "finish the job.");
        }
        String summary = "apply_cleanup " + kind + " (" + totalEdits + " edit(s) across "
            + editsByFile.size() + " file(s))";
        return Preparation.of(change, summary, extras);
    }

    /**
     * Add {@code final} to parameters and local declarations whose binding is
     * never written after declaration. Returns the number of declarations
     * marked.
     */
    private int rewriteAddFinal(CompilationUnit ast, ASTRewrite rewrite) {
        Set<String> written = collectWrittenVariableKeys(ast);
        int[] count = {0};
        AST factory = ast.getAST();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SingleVariableDeclaration node) {
                // Parameters (method/constructor/catch/enhanced-for). Only those
                // directly owned by a method's parameter list are addressed here.
                if (!(node.getParent() instanceof MethodDeclaration)) {
                    return true;
                }
                if (Modifier.isFinal(node.getModifiers())) {
                    return true;
                }
                if (isWritten(node.resolveBinding(), written)) {
                    return true;
                }
                addFinal(rewrite, node, SingleVariableDeclaration.MODIFIERS2_PROPERTY, factory);
                count[0]++;
                return true;
            }

            @Override
            public boolean visit(VariableDeclarationStatement node) {
                if (Modifier.isFinal(node.getModifiers())) {
                    return true;
                }
                // A statement can declare several fragments; final applies to all,
                // so only add it when EVERY fragment is never reassigned.
                for (Object f : node.fragments()) {
                    VariableDeclarationFragment frag = (VariableDeclarationFragment) f;
                    if (isWritten(frag.resolveBinding(), written)) {
                        return true;
                    }
                }
                addFinal(rewrite, node, VariableDeclarationStatement.MODIFIERS2_PROPERTY, factory);
                count[0]++;
                return true;
            }
        });
        return count[0];
    }

    private static void addFinal(ASTRewrite rewrite, org.eclipse.jdt.core.dom.ASTNode node,
                                 org.eclipse.jdt.core.dom.ChildListPropertyDescriptor prop,
                                 AST factory) {
        ListRewrite lr = rewrite.getListRewrite(node, prop);
        lr.insertFirst(factory.newModifier(Modifier.ModifierKeyword.FINAL_KEYWORD), null);
    }

    private static boolean isWritten(IVariableBinding binding, Set<String> written) {
        return binding == null || written.contains(binding.getKey());
    }

    /** Binding keys of every variable that is the target of a write (=, +=, ++, --). */
    private Set<String> collectWrittenVariableKeys(CompilationUnit ast) {
        Set<String> written = new HashSet<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                recordIfVariable(node.getLeftHandSide(), written);
                return true;
            }

            @Override
            public boolean visit(PrefixExpression node) {
                if (node.getOperator() == PrefixExpression.Operator.INCREMENT
                        || node.getOperator() == PrefixExpression.Operator.DECREMENT) {
                    recordIfVariable(node.getOperand(), written);
                }
                return true;
            }

            @Override
            public boolean visit(PostfixExpression node) {
                recordIfVariable(node.getOperand(), written);
                return true;
            }
        });
        return written;
    }

    private static void recordIfVariable(org.eclipse.jdt.core.dom.Expression target, Set<String> written) {
        if (target instanceof SimpleName name) {
            IBinding b = name.resolveBinding();
            if (b instanceof IVariableBinding vb) {
                written.add(vb.getKey());
            }
        }
    }

    /**
     * Remove modifiers that are implicit on interface members. Returns the
     * number of modifier tokens removed.
     */
    private int rewriteRedundantModifiers(CompilationUnit ast, ASTRewrite rewrite) {
        int[] count = {0};
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (!node.isInterface()) {
                    return true;
                }
                for (MethodDeclaration m : node.getMethods()) {
                    count[0] += removeModifiers(rewrite, m, m.modifiers(),
                        MethodDeclaration.MODIFIERS2_PROPERTY,
                        Modifier.ModifierKeyword.PUBLIC_KEYWORD,
                        Modifier.ModifierKeyword.ABSTRACT_KEYWORD);
                }
                for (FieldDeclaration fd : node.getFields()) {
                    count[0] += removeModifiers(rewrite, fd, fd.modifiers(),
                        FieldDeclaration.MODIFIERS2_PROPERTY,
                        Modifier.ModifierKeyword.PUBLIC_KEYWORD,
                        Modifier.ModifierKeyword.STATIC_KEYWORD,
                        Modifier.ModifierKeyword.FINAL_KEYWORD);
                }
                for (TypeDeclaration nested : node.getTypes()) {
                    count[0] += removeModifiers(rewrite, nested, nested.modifiers(),
                        TypeDeclaration.MODIFIERS2_PROPERTY,
                        Modifier.ModifierKeyword.PUBLIC_KEYWORD,
                        Modifier.ModifierKeyword.STATIC_KEYWORD);
                }
                return true;
            }
        });
        return count[0];
    }

    private static int removeModifiers(ASTRewrite rewrite, org.eclipse.jdt.core.dom.ASTNode owner,
                                       List<?> modifiers,
                                       org.eclipse.jdt.core.dom.ChildListPropertyDescriptor prop,
                                       Modifier.ModifierKeyword... redundant) {
        Set<Modifier.ModifierKeyword> toDrop = new HashSet<>(List.of(redundant));
        ListRewrite lr = rewrite.getListRewrite(owner, prop);
        int removed = 0;
        for (Object o : modifiers) {
            if (o instanceof Modifier mod && toDrop.contains(mod.getKeyword())) {
                lr.remove(mod, null);
                removed++;
            }
        }
        return removed;
    }
}
