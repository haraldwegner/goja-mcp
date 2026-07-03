package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Advisor;
import org.goja.mcp.domain.NoOpAdvisor;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.PlanStore;
import org.goja.mcp.refactoring.RefactoringChangeCache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A (v1.1.1) — the staged-refactoring lifecycle front door, collapsing
 * apply_refactoring / undo_refactoring / inspect_refactoring by {@code action}.
 * Not marked read-only (apply/undo mutate; inspect reads).
 */
public class RefactoringTool extends AbstractTool {

    // Sprint 18: the single-change lifecycle (apply/undo/inspect on a changeId) plus
    // the multi-step plan lifecycle. Actions are advertised as they land (C4: plan;
    // C5: apply_plan; C6: inspect_plan/undo_plan).
    private static final List<String> ACTIONS =
        List.of("apply", "undo", "inspect", "plan", "apply_plan", "inspect_plan", "undo_plan");

    private final ApplyRefactoringTool apply;
    private final UndoRefactoringTool undo;
    private final InspectRefactoringTool inspect;
    private final PlanRefactoringTool planTool;

    /** Back-compat: no store wired — the advisor is the Sprint-18 {@link NoOpAdvisor}. */
    public RefactoringTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        this(serviceSupplier, cache, new NoOpAdvisor());
    }

    /**
     * Sprint 21 Stage 3 — inject the {@link Advisor} (the store-backed
     * {@code ExperienceAdvisor} in production) so the plan lifecycle consults + records
     * against the knowledge store.
     */
    public RefactoringTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache,
                           Advisor advisor) {
        super(serviceSupplier);
        this.apply = new ApplyRefactoringTool(serviceSupplier, cache);
        this.undo = new UndoRefactoringTool(serviceSupplier, cache);
        this.inspect = new InspectRefactoringTool(serviceSupplier, cache);
        // Process-lived plan store so a planId from `plan` survives to apply_plan/inspect_plan/undo_plan.
        this.planTool = new PlanRefactoringTool(serviceSupplier, cache, new PlanStore(), advisor);
    }

    @Override
    public String getName() {
        return "refactoring";
    }

    @Override
    public String getDescription() {
        return """
            Manage a refactoring: the single-change lifecycle and the multi-step plan lifecycle.

            USAGE: refactoring(action="<action>", ...)

            Single change (staged/auto_apply=false flows):
            - apply   — perform a staged change. Needs: changeId.
            - undo    — revert an applied change. Needs: undoChangeId.
            - inspect — preview a staged change without applying. Needs: changeId.

            Multi-step plan (behaviour-preserving, parity-gated orchestration):
            - plan       — decompose a kind into an ordered, inspectable step list (no changes made).
                           Needs: kind (compose_method | replace_type_code_with_class | inline_singleton),
                           filePath. Kind params: line/column (+ newTypeName for replace_type_code;
                           sections[] for compose_method). Returns a planId.
            - apply_plan — run a planId step by step, parity-gated (compile 0/0 + a purity check)
                           after each; rolls the whole plan back atomically on failure. Needs: planId.
                           Returns the composed undoChangeId + any purity findings.
            - inspect_plan — review a planId: its steps, which are applied vs pending. Needs: planId.
            - undo_plan  — revert a fully-applied plan via its composed undo. Needs: planId.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", ACTIONS);
        action.put("description", "Which lifecycle action to run.");
        properties.put("action", action);
        properties.put("changeId", Map.of("type", "string", "description", "apply/inspect: the staged change id."));
        properties.put("undoChangeId", Map.of("type", "string", "description", "undo: the undo handle id."));
        properties.put("kind", Map.of("type", "string", "enum", PlanRefactoringTool.PLAN_KINDS,
            "description", "plan: which multi-step refactoring to plan."));
        properties.put("target", Map.of("type", "string", "description", "plan: optional symbol/label for advice + display."));
        properties.put("filePath", Map.of("type", "string", "description", "plan: source file of the refactoring target."));
        properties.put("line", Map.of("type", "integer", "description", "plan: zero-based caret line."));
        properties.put("column", Map.of("type", "integer", "description", "plan: zero-based caret column."));
        properties.put("newTypeName", Map.of("type", "string", "description", "plan (replace_type_code_with_class): name for the generated type."));
        properties.put("sections", Map.of("type", "array", "items", Map.of("type", "object"),
            "description", "plan (compose_method): statement ranges to extract, each {startLine,startColumn,endLine,endColumn,methodName} (0-based)."));
        properties.put("planId", Map.of("type", "string", "description", "apply_plan/inspect_plan/undo_plan: the plan id from `plan`."));
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String action = getStringParam(arguments, "action");
        if (action == null || action.isBlank()) {
            return ToolResponse.invalidParameter("action", "action is required; one of " + ACTIONS);
        }
        return switch (action) {
            case "apply"   -> apply.executeWithService(service, arguments);
            case "undo"    -> undo.executeWithService(service, arguments);
            case "inspect" -> inspect.executeWithService(service, arguments);
            case "plan", "apply_plan", "inspect_plan", "undo_plan"
                           -> planTool.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("action",
                "Unknown action '" + action + "'. Allowed: " + ACTIONS);
        };
    }
}
