package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A — parametric front door collapsing the two call-hierarchy tools
 * into one always-loaded tool, selected by {@code direction}. Both delegates
 * take the identical {@code {filePath, line, column, maxResults}} input.
 *
 * <p>Replaces {@code get_call_hierarchy_incoming} (callers) and
 * {@code get_call_hierarchy_outgoing} (callees); the narrow classes remain as
 * the delegated implementations and are no longer registered.</p>
 */
public class GetCallHierarchyTool extends AbstractTool {

    private static final List<String> DIRECTIONS = List.of("incoming", "outgoing");

    private final GetCallHierarchyIncomingTool incoming;
    private final GetCallHierarchyOutgoingTool outgoing;

    public GetCallHierarchyTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.incoming = new GetCallHierarchyIncomingTool(serviceSupplier);
        this.outgoing = new GetCallHierarchyOutgoingTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_call_hierarchy";
    }

    @Override
    public String getDescription() {
        return """
            Walk the call hierarchy of the method at a source position.

            USAGE: get_call_hierarchy(direction="<incoming|outgoing>", filePath=..., line=..., column=...)

            Directions (both take filePath + ZERO-BASED line/column on a method):
            - incoming — who CALLS this method (callers).
            - outgoing — what this method CALLS (callees).

            The `incoming` direction also accepts an FQN member `symbol`
            (pkg.Type#method) instead of filePath/line/column.

            IMPORTANT: Uses ZERO-BASED coordinates.

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
        direction.put("description", "incoming = callers of the method; outgoing = methods it calls.");
        properties.put("direction", direction);

        properties.put("filePath", Map.of(
            "type", "string",
            "description", "Path to source file"));
        properties.put("line", Map.of(
            "type", "integer",
            "description", "Zero-based line number"));
        properties.put("column", Map.of(
            "type", "integer",
            "description", "Zero-based column number"));
        properties.put("maxResults", Map.of(
            "type", "integer",
            "description", "Max results to return (default 50)"));
        properties.put("symbol", Map.of(
            "type", "string",
            "description", "FQN member form (incoming only): pkg.Type#method — resolves the "
                + "method directly, no filePath/line/column needed."));
        properties.put("scope", Map.of(
            "type", "string",
            "description", "symbol form: 'workspace' (default) or 'project'."));

        schema.put("properties", properties);
        // filePath/line/column are validated per-delegate (a `symbol` replaces them for incoming).
        schema.put("required", List.of("direction"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String direction = getStringParam(arguments, "direction");
        if (direction == null || direction.isBlank()) {
            return ToolResponse.invalidParameter("direction",
                "direction is required; one of " + DIRECTIONS);
        }
        return switch (direction) {
            case "incoming" -> incoming.executeWithService(service, arguments);
            case "outgoing" -> outgoing.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("direction",
                "Unknown direction '" + direction + "'. Allowed: " + DIRECTIONS);
        };
    }
}
