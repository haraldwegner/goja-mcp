package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
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

    private static final List<String> ACTIONS = List.of("apply", "undo", "inspect");

    private final ApplyRefactoringTool apply;
    private final UndoRefactoringTool undo;
    private final InspectRefactoringTool inspect;

    public RefactoringTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.apply = new ApplyRefactoringTool(serviceSupplier, cache);
        this.undo = new UndoRefactoringTool(serviceSupplier, cache);
        this.inspect = new InspectRefactoringTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "refactoring";
    }

    @Override
    public String getDescription() {
        return """
            Manage a staged refactoring (the apply/undo/inspect lifecycle).

            USAGE: refactoring(action="<action>", ...)

            - apply   — perform a staged change. Needs: changeId.
            - undo    — revert an applied change. Needs: undoChangeId.
            - inspect — preview a staged change without applying. Needs: changeId.

            (Refactor tools apply by default and return an undoChangeId; this is for
            staged/auto_apply=false flows.) Requires load_project to be called first.
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
            default -> ToolResponse.invalidParameter("action",
                "Unknown action '" + action + "'. Allowed: " + ACTIONS);
        };
    }
}
