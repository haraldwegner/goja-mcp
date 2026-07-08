package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Advisor;
import org.goja.mcp.domain.Outcome;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.ParityGate;
import org.goja.mcp.refactoring.Plan;
import org.goja.mcp.refactoring.PlanStep;
import org.goja.mcp.refactoring.PlanStore;
import org.goja.mcp.refactoring.PurityCheck;
import org.goja.mcp.refactoring.PurityCheck.PurityFinding;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.smell.RecipeCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 18 — the plan-lifecycle delegate behind {@code refactoring(action=plan|
 * apply_plan|inspect_plan|undo_plan)}.
 *
 * <ul>
 *   <li>{@code plan} (C4) — decompose a kind into an ordered, inspectable
 *       {@link PlanStep} list; consult the {@link Advisor}; store a {@link Plan}.</li>
 *   <li>{@code apply_plan} (C5) — walk the plan step by step: perform the primitive,
 *       run the {@link ParityGate} (compile 0/0) and {@link PurityCheck} on the step's
 *       target method, and on a compile failure / a new-control-flow purity finding /
 *       a step that fails to apply, <b>roll back every applied step</b> (reverse-order
 *       undo, atomic) and report. On success, compose the per-step undos into one plan
 *       undo handle and emit an {@link Outcome} through the advisor. Other purity
 *       findings are surfaced, not blocking.</li>
 * </ul>
 *
 * <p>Not registered — a package-private delegate the {@code refactoring} front door
 * routes plan actions to; it shares the front door's {@code changeCache} and a
 * process-lived {@link PlanStore}.</p>
 */
class PlanRefactoringTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(PlanRefactoringTool.class);

    static final List<String> PLAN_KINDS = List.of(
        "compose_method", "replace_type_code_with_class", "inline_singleton",
        // OCP-cure recipes (OcpCure.recipesFor) — single-step, parity-gated refactor_to_pattern plans.
        "refactor_to_state", "refactor_to_command_dispatcher", "form_template_method");

    private static final Set<String> CONTROL_FIELDS =
        Set.of("action", "kind", "target", "filePath", "planId", "sections", "projectKey", "auto_apply");

    private final RefactoringChangeCache changeCache;
    private final PlanStore planStore;
    private final Advisor advisor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExtractTool extractTool;
    private final RefactorToPatternTool patternTool;
    /**
     * Sprint 22a R.1 — the step-tool dispatch, an OPEN registry (was a closed
     * extract/refactor_to_pattern switch in {@code invokeStep}). A new recipe
     * primitive registers one entry here; the recipe engine picks it up with no
     * further edit.
     */
    private final Map<String, StepInvoker> stepTools = new LinkedHashMap<>();

    /** Runs one recipe step's primitive against the current workspace. */
    @FunctionalInterface
    private interface StepInvoker {
        ToolResponse invoke(IJdtService service, JsonNode args);
    }

    PlanRefactoringTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache changeCache,
                        PlanStore planStore, Advisor advisor) {
        super(serviceSupplier);
        this.changeCache = changeCache;
        this.planStore = planStore;
        this.advisor = advisor;
        this.extractTool = new ExtractTool(serviceSupplier, changeCache);
        this.patternTool = new RefactorToPatternTool(serviceSupplier, changeCache);
        this.stepTools.put("extract", extractTool::executeWithService);
        this.stepTools.put("refactor_to_pattern", patternTool::executeWithService);
    }

    @Override
    public String getName() {
        return "refactoring.plan";
    }

    @Override
    public String getDescription() {
        return "Plan-lifecycle delegate of refactoring(action=plan…). Not registered; the front door owns the schema.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of("type", "object");
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String action = getStringParam(arguments, "action", "");
        return switch (action) {
            case "plan" -> doPlan(arguments);
            case "apply_plan" -> doApplyPlan(service, arguments);
            case "inspect_plan" -> doInspectPlan(arguments);
            case "undo_plan" -> doUndoPlan(service, arguments);
            default -> ToolResponse.invalidParameter("action",
                "plan lifecycle: '" + action + "' is not available in this build.");
        };
    }

    // ---------------------------------------------------------------- plan (C4)

    private ToolResponse doPlan(JsonNode args) {
        String kind = getStringParam(args, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind",
                "plan kind must be one of " + PLAN_KINDS + ", or a smell kind with a known recipe.");
        }
        // Recipe bridge (Sprint 22a R.1): a smell kind that is not itself a plan kind
        // resolves to its cure recipe — the first applicable plan kind — so a
        // find_quality_issue finding hands straight to a runnable, parity-gated plan.
        String sourceSmell = null;
        if (!PLAN_KINDS.contains(kind)) {
            List<String> recipes = RecipeCatalog.recipesFor(kind);
            if (recipes.isEmpty()) {
                return ToolResponse.invalidParameter("kind",
                    "plan kind must be one of " + PLAN_KINDS + ", or a smell kind with a known recipe; "
                        + "'" + kind + "' has neither.");
            }
            sourceSmell = kind;
            kind = recipes.get(0);
        }
        String filePath = getStringParam(args, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required.");
        }
        String target = getStringParam(args, "target", filePath);

        List<PlanStep> steps;
        try {
            steps = buildSteps(kind, args, filePath);
        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("plan", e.getMessage());
        }
        if (steps.isEmpty()) {
            return ToolResponse.invalidParameter("plan", "No steps could be built for kind '" + kind + "'.");
        }

        List<String> advice = advisor.adviseBefore(kind, target);
        Plan plan = planStore.create(kind, target, steps, advice);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "refactoring");
        data.put("action", "plan");
        data.put("planId", plan.planId());
        data.put("kind", kind);
        if (sourceSmell != null) {
            data.put("sourceSmell", sourceSmell);
            data.put("recipe", kind);
        }
        data.put("target", target);
        data.put("stepCount", steps.size());
        data.put("steps", stepViews(steps));
        data.put("advice", advice);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(steps.size())
            .returnedCount(steps.size())
            .suggestedNextTools(List.of(
                "refactoring(action=inspect_plan, planId) to review the plan",
                "refactoring(action=apply_plan, planId) to run it through the parity gate"))
            .build());
    }

    // ---------------------------------------------------------- apply_plan (C5)

    private ToolResponse doApplyPlan(IJdtService service, JsonNode args) {
        String planId = getStringParam(args, "planId");
        if (planId == null || planId.isBlank()) {
            return ToolResponse.invalidParameter("planId", "Required.");
        }
        Optional<Plan> opt = planStore.get(planId);
        if (opt.isEmpty()) {
            return ToolResponse.invalidParameter("planId", "Unknown or expired plan '" + planId + "'.");
        }
        Plan plan = opt.get();

        List<Change> undoStack = new ArrayList<>();      // per-step undos, application order
        LinkedHashSet<String> modifiedFiles = new LinkedHashSet<>();
        List<Map<String, Object>> purityFindings = new ArrayList<>();
        plan.appliedThrough(-1);

        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);

            // Capture the target method BEFORE the step (for the purity diff), if the step is method-scoped.
            MethodDeclaration before = null;
            String methodName = null;
            String stepFile = step.args().path("filePath").asText(null);
            int stepLine = step.args().has("startLine")
                ? step.args().get("startLine").asInt(-1) : step.args().path("line").asInt(-1);
            if (stepFile != null && stepLine >= 0) {
                before = enclosingMethod(parseFile(stepFile), stepLine);
                if (before != null) {
                    methodName = before.getName().getIdentifier();
                }
            }

            // Perform the primitive.
            ToolResponse r = invokeStep(service, step);
            if (r == null || !r.isSuccess()) {
                rollback(undoStack, service);
                String reason = "step " + (i + 1) + " (" + step.tool() + ") failed to apply"
                    + (r == null ? "" : ": " + r.getError());
                return rolledBack(plan, i, reason, purityFindings);
            }
            Map<String, Object> d = dataOf(r);
            String undoId = d.get("undoChangeId") instanceof String s ? s : null;
            if (undoId != null) {
                changeCache.take(undoId, RefactoringChangeCache.Kind.UNDO)
                    .ifPresent(e -> undoStack.add(e.change()));
            }
            if (d.get("filesModified") instanceof List<?> list) {
                list.forEach(x -> modifiedFiles.add(String.valueOf(x)));
            }

            // Parity gate: compile must stay clean.
            ParityGate.Result gate = ParityGate.compile(service);
            if (!gate.clean()) {
                rollback(undoStack, service);
                return rolledBack(plan, i,
                    "parity gate: step " + (i + 1) + " broke the build (" + gate.errorCount() + " errors)",
                    purityFindings);
            }

            // Purity check on the step's target method (opportunistic, syntactic).
            boolean controlFlowRisk = false;
            if (methodName != null && stepFile != null) {
                MethodDeclaration after = methodByName(parseFile(stepFile), methodName);
                if (after != null) {
                    for (PurityFinding f : PurityCheck.check(i, before, after)) {
                        purityFindings.add(findingView(f));
                        if (PurityCheck.NEW_CONTROL_FLOW_OUTCOME.equals(f.rule())) {
                            controlFlowRisk = true;
                        }
                    }
                }
            }
            if (controlFlowRisk) {
                rollback(undoStack, service);
                return rolledBack(plan, i,
                    "purity halt: step " + (i + 1) + " introduces a new control-flow outcome on " + methodName
                        + " — reclassify it as a separate, tested behaviour change",
                    purityFindings);
            }

            plan.appliedThrough(i);
        }

        // Success: compose the per-step undos (reverse order) into one plan undo handle.
        String planUndoId = null;
        if (!undoStack.isEmpty()) {
            CompositeChange composite = new CompositeChange("undo plan " + planId);
            for (int i = undoStack.size() - 1; i >= 0; i--) {
                composite.add(undoStack.get(i));
            }
            planUndoId = changeCache.put(RefactoringChangeCache.Kind.UNDO, composite,
                "undo: plan " + planId, "", new ArrayList<>(modifiedFiles));
        }
        plan.undoChangeId(planUndoId);

        List<String> notes = new ArrayList<>();
        purityFindings.forEach(f -> notes.add(f.get("rule") + " @step" + f.get("stepIndex")));
        Outcome outcome = new Outcome("refactoring.apply_plan", plan.kind(), plan.target(),
            Outcome.APPLIED, new ArrayList<>(modifiedFiles), planUndoId, notes);
        advisor.record(outcome);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "refactoring");
        data.put("action", "apply_plan");
        data.put("applied", true);
        data.put("planId", planId);
        data.put("stepsRun", plan.steps().size());
        data.put("filesModified", new ArrayList<>(modifiedFiles));
        data.put("undoChangeId", planUndoId);
        data.put("purityFindings", purityFindings);
        data.put("outcome", outcomeView(outcome));
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(plan.steps().size())
            .returnedCount(plan.steps().size())
            .suggestedNextTools(List.of(
                "compile_workspace to verify the composed refactoring",
                "undo_refactoring with the undoChangeId to revert the whole plan"))
            .build());
    }

    private ToolResponse invokeStep(IJdtService service, PlanStep step) {
        StepInvoker invoker = stepTools.get(step.tool());
        return invoker == null ? null : invoker.invoke(service, step.args());
    }

    private void rollback(List<Change> undoStack, IJdtService service) {
        for (int i = undoStack.size() - 1; i >= 0; i--) {
            try {
                ChangeEngine.perform(undoStack.get(i), service);
            } catch (RuntimeException e) {
                log.warn("apply_plan rollback step failed: {}", e.getMessage());
            }
        }
    }

    private ToolResponse rolledBack(Plan plan, int failedStep, String reason, List<Map<String, Object>> findings) {
        plan.appliedThrough(-1);
        Outcome outcome = new Outcome("refactoring.apply_plan", plan.kind(), plan.target(),
            Outcome.ROLLED_BACK, List.of(), null, List.of(reason));
        advisor.record(outcome);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "refactoring");
        data.put("action", "apply_plan");
        data.put("applied", false);
        data.put("rolledBack", true);
        data.put("planId", plan.planId());
        data.put("failedAtStep", failedStep);
        data.put("reason", reason);
        data.put("purityFindings", findings);
        data.put("outcome", outcomeView(outcome));
        return ToolResponse.success(data, ResponseMeta.builder()
            .suggestedNextTools(List.of("refactoring(action=inspect_plan, planId) to review the plan"))
            .build());
    }

    // ------------------------------------------------- inspect_plan / undo_plan (C6)

    private ToolResponse doInspectPlan(JsonNode args) {
        String planId = getStringParam(args, "planId");
        if (planId == null || planId.isBlank()) {
            return ToolResponse.invalidParameter("planId", "Required.");
        }
        Optional<Plan> opt = planStore.get(planId);
        if (opt.isEmpty()) {
            return ToolResponse.invalidParameter("planId", "Unknown or expired plan '" + planId + "'.");
        }
        Plan plan = opt.get();
        int applied = plan.appliedThrough();

        List<Map<String, Object>> stepView = new ArrayList<>();
        for (PlanStep s : plan.steps()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", s.index());
            m.put("tool", s.tool());
            m.put("args", s.args());
            m.put("expectedStateAfter", s.expectedStateAfter());
            m.put("status", s.index() <= applied ? "applied" : "pending");
            stepView.add(m);
        }
        boolean fullyApplied = applied >= 0 && applied == plan.steps().size() - 1;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "refactoring");
        data.put("action", "inspect_plan");
        data.put("planId", planId);
        data.put("kind", plan.kind());
        data.put("target", plan.target());
        data.put("stepCount", plan.steps().size());
        data.put("appliedThrough", applied);
        data.put("applied", fullyApplied);
        data.put("undoChangeId", plan.undoChangeId());
        data.put("steps", stepView);
        data.put("advice", plan.advice());
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(plan.steps().size())
            .returnedCount(plan.steps().size())
            .build());
    }

    private ToolResponse doUndoPlan(IJdtService service, JsonNode args) {
        String planId = getStringParam(args, "planId");
        if (planId == null || planId.isBlank()) {
            return ToolResponse.invalidParameter("planId", "Required.");
        }
        Optional<Plan> opt = planStore.get(planId);
        if (opt.isEmpty()) {
            return ToolResponse.invalidParameter("planId", "Unknown or expired plan '" + planId + "'.");
        }
        Plan plan = opt.get();
        if (plan.appliedThrough() < 0 || plan.undoChangeId() == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "refactoring");
            data.put("action", "undo_plan");
            data.put("planId", planId);
            data.put("undone", false);
            data.put("message", "Plan has no applied changes to undo.");
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }

        Optional<Change> undo = changeCache.take(plan.undoChangeId(), RefactoringChangeCache.Kind.UNDO)
            .map(e -> e.change());
        if (undo.isEmpty()) {
            return ToolResponse.error("UNDO_UNAVAILABLE",
                "The undo handle for plan '" + planId + "' has expired or was already consumed.",
                "Re-run the plan if you need to change it.");
        }
        ChangeEngine.ApplyOutcome outcome = ChangeEngine.perform(undo.get(), service);
        if (outcome.validationError() != null) {
            return ToolResponse.error("UNDO_FAILED",
                "undo_plan failed: " + outcome.validationError(),
                "The workspace may be in a partially-reverted state; inspect it before retrying.");
        }
        plan.appliedThrough(-1);
        plan.undoChangeId(null);

        Outcome oc = new Outcome("refactoring.undo_plan", plan.kind(), plan.target(),
            Outcome.ROLLED_BACK, outcome.modifiedFilePaths(), null, List.of("plan undone"));
        advisor.record(oc);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "refactoring");
        data.put("action", "undo_plan");
        data.put("planId", planId);
        data.put("undone", true);
        data.put("filesModified", outcome.modifiedFilePaths());
        data.put("outcome", outcomeView(oc));
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(outcome.modifiedFilePaths().size())
            .returnedCount(outcome.modifiedFilePaths().size())
            .build());
    }

    // -------------------------------------------------------------- step building

    private List<PlanStep> buildSteps(String kind, JsonNode args, String filePath) {
        if ("compose_method".equals(kind)) {
            return composeSteps(args, filePath);
        }
        if ("replace_type_code_with_class".equals(kind) && getStringParam(args, "newTypeName") == null) {
            throw new IllegalArgumentException("replace_type_code_with_class needs newTypeName.");
        }
        // Every other plan kind is a single, parity-gated refactor_to_pattern step (incl. the
        // OCP-cure recipes) — the kind-specific params pass straight through to the delegate.
        return List.of(patternStep(0, kind, args, filePath, "applied " + kind));
    }

    /** A single {@code refactor_to_pattern} step carrying the caret + all kind-specific params. */
    private PlanStep patternStep(int index, String patternKind, JsonNode args, String filePath, String expected) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", patternKind);
        a.put("filePath", filePath);
        Iterator<String> names = args.fieldNames();
        while (names.hasNext()) {
            String f = names.next();
            if (!CONTROL_FIELDS.contains(f)) {
                a.set(f, args.get(f));
            }
        }
        return new PlanStep(index, "refactor_to_pattern", a, expected, -1);
    }

    private List<PlanStep> composeSteps(JsonNode args, String filePath) {
        JsonNode sections = args.get("sections");
        if (sections == null || !sections.isArray() || sections.isEmpty()) {
            throw new IllegalArgumentException(
                "compose_method needs a non-empty 'sections' array of {startLine,startColumn,endLine,endColumn,methodName}.");
        }
        List<JsonNode> ordered = new ArrayList<>();
        sections.forEach(ordered::add);
        ordered.sort((x, y) -> Integer.compare(y.path("startLine").asInt(-1), x.path("startLine").asInt(-1)));

        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            JsonNode s = ordered.get(i);
            String name = s.path("methodName").asText(null);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("each compose_method section needs a methodName.");
            }
            ObjectNode a = mapper.createObjectNode();
            a.put("kind", "method");
            a.put("filePath", filePath);
            a.put("startLine", s.path("startLine").asInt());
            a.put("startColumn", s.path("startColumn").asInt());
            a.put("endLine", s.path("endLine").asInt());
            a.put("endColumn", s.path("endColumn").asInt());
            a.put("methodName", name);
            steps.add(new PlanStep(i, "extract", a, "extracted " + name, -1));
        }
        return steps;
    }

    // ---------------------------------------------------------------- views + AST helpers

    private List<Map<String, Object>> stepViews(List<PlanStep> steps) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PlanStep s : steps) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", s.index());
            m.put("tool", s.tool());
            m.put("args", s.args());
            m.put("expectedStateAfter", s.expectedStateAfter());
            m.put("rollbackTo", s.rollbackTo());
            out.add(m);
        }
        return out;
    }

    private static Map<String, Object> findingView(PurityFinding f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stepIndex", f.stepIndex());
        m.put("rule", f.rule());
        m.put("detail", f.detail());
        return m;
    }

    private static Map<String, Object> outcomeView(Outcome o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("operation", o.operation());
        m.put("kind", o.kind());
        m.put("target", o.target());
        m.put("status", o.status());
        m.put("filePaths", o.filePaths());
        m.put("undoChangeId", o.undoChangeId());
        m.put("notes", o.notes());
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        return r.getData() instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private CompilationUnit parseFile(String filePath) {
        try {
            String src = Files.readString(Path.of(filePath));
            ASTParser p = ASTParser.newParser(AST.getJLSLatest());
            p.setSource(src.toCharArray());
            p.setKind(ASTParser.K_COMPILATION_UNIT);
            return (CompilationUnit) p.createAST(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static MethodDeclaration enclosingMethod(CompilationUnit ast, int zeroBasedLine) {
        if (ast == null) {
            return null;
        }
        int target = zeroBasedLine + 1; // JDT line numbers are 1-based
        MethodDeclaration[] best = {null};
        int[] bestSpan = {Integer.MAX_VALUE};
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration m) {
                int start = ast.getLineNumber(m.getStartPosition());
                int end = ast.getLineNumber(m.getStartPosition() + Math.max(0, m.getLength() - 1));
                if (start <= target && target <= end) {
                    int span = end - start;
                    if (span < bestSpan[0]) {
                        bestSpan[0] = span;
                        best[0] = m;
                    }
                }
                return true;
            }
        });
        return best[0];
    }

    private static MethodDeclaration methodByName(CompilationUnit ast, String name) {
        if (ast == null) {
            return null;
        }
        MethodDeclaration[] found = {null};
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration m) {
                if (found[0] == null && m.getName().getIdentifier().equals(name)) {
                    found[0] = m;
                }
                return true;
            }
        });
        return found[0];
    }

}
