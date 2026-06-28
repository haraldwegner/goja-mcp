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
 * Sprint 16b/A — parametric front door for pull-up / push-down, selected by
 * {@code direction}. Both delegates take the identical {@code {filePath, line,
 * column}} input (a member at the caret), so this is a clean uniform collapse.
 *
 * <p>Replaces {@code pull_up} / {@code push_down}; apply/undo contract
 * unchanged.</p>
 */
public class MoveInHierarchyTool extends AbstractTool {

    private static final List<String> DIRECTIONS = List.of("up", "down");

    private final PullUpTool pullUp;
    private final PushDownTool pushDown;

    public MoveInHierarchyTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.pullUp = new PullUpTool(serviceSupplier, cache);
        this.pushDown = new PushDownTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "move_in_hierarchy";
    }

    @Override
    public String getDescription() {
        return """
            Move a member up to a supertype or down to subtypes (behaviour-preserving, reversible).

            USAGE: move_in_hierarchy(direction="<up|down>", filePath=..., line=..., column=...)

            - up   — pull the member at the caret UP into its superclass/interface.
            - down — push the member at the caret DOWN into its subclasses.

            IMPORTANT: ZERO-BASED coordinates. Applies by default; returns
            filesModified/diff/undoChangeId/summary. Pass auto_apply=false to stage only.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> direction = new LinkedHashMap<>();
        direction.put("type", "string");
        direction.put("enum", DIRECTIONS);
        direction.put("description", "up = pull up to supertype; down = push down to subtypes.");
        properties.put("direction", direction);
        properties.put("filePath", Map.of("type", "string", "description", "Path to source file."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line of the member at the caret."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));

        schema.put("properties", properties);
        schema.put("required", List.of("direction", "filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String direction = getStringParam(arguments, "direction");
        if (direction == null || direction.isBlank()) {
            return ToolResponse.invalidParameter("direction", "direction is required; one of " + DIRECTIONS);
        }
        return switch (direction) {
            case "up"   -> pullUp.executeWithService(service, arguments);
            case "down" -> pushDown.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("direction",
                "Unknown direction '" + direction + "'. Allowed: " + DIRECTIONS);
        };
    }
}
