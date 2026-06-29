package org.goja.mcp.tools.build;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AbstractTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A (v1.1.1) — Maven dependency front door, collapsing add_dependency /
 * update_dependency / find_unused_dependencies by {@code action}. Lives in the
 * {@code build} package for protected-delegate access. Not read-only (add/update
 * edit the pom; find_unused reads).
 */
public class DependencyTool extends AbstractTool {

    private static final List<String> ACTIONS = List.of("add", "update", "find_unused");

    private final AddDependencyTool add;
    private final UpdateDependencyTool update;
    private final FindUnusedDependenciesTool findUnused;

    public DependencyTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.add = new AddDependencyTool(serviceSupplier);
        this.update = new UpdateDependencyTool(serviceSupplier);
        this.findUnused = new FindUnusedDependenciesTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "dependency";
    }

    @Override
    public String getDescription() {
        return """
            Manage Maven dependencies.

            USAGE: dependency(action="<action>", ...)

            - add         — add a dependency. Needs: groupId, artifactId, version.
            - update      — change a dependency's version. Needs: groupId, artifactId, newVersion.
            - find_unused — declared-but-unused dependencies. (no params)

            Maven projects only. Requires load_project to be called first.
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
        action.put("description", "Which dependency action to run.");
        properties.put("action", action);
        properties.put("groupId", Map.of("type", "string", "description", "add/update: Maven groupId."));
        properties.put("artifactId", Map.of("type", "string", "description", "add/update: Maven artifactId."));
        properties.put("version", Map.of("type", "string", "description", "add: the version to add."));
        properties.put("newVersion", Map.of("type", "string", "description", "update: the new version."));
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
            case "add"         -> add.executeWithService(service, arguments);
            case "update"      -> update.executeWithService(service, arguments);
            case "find_unused" -> findUnused.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("action",
                "Unknown action '" + action + "'. Allowed: " + ACTIONS);
        };
    }
}
