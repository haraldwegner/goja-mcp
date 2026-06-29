package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A (v1.1.1) — workspace-membership front door, collapsing
 * list_projects / add_project / remove_project by {@code action}. (load_project
 * stays separate — it installs the workspace service.) Not read-only (add/remove
 * mutate workspace state).
 */
public class ProjectTool extends AbstractTool {

    private static final List<String> ACTIONS = List.of("list", "add", "remove");

    private final ListProjectsTool list;
    private final AddProjectTool add;
    private final RemoveProjectTool remove;

    public ProjectTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.list = new ListProjectsTool(serviceSupplier);
        this.add = new AddProjectTool(serviceSupplier);
        this.remove = new RemoveProjectTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "project";
    }

    @Override
    public String getDescription() {
        return """
            Manage projects in the loaded workspace.

            USAGE: project(action="<action>", ...)

            - list   — list loaded projects (projectKey, path, counts). (no params)
            - add    — add a project to the workspace. Needs: projectPath.
            - remove — drop a project from the workspace. Needs: projectKey.

            (Use load_project to (re)install the workspace.) Requires a loaded workspace.
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
        action.put("description", "Which workspace-membership action to run.");
        properties.put("action", action);
        properties.put("projectPath", Map.of("type", "string", "description", "add: absolute path of the project to add."));
        properties.put("projectKey", Map.of("type", "string", "description", "remove: key of the project to drop."));
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return schema;
    }

    // The project-membership delegates override execute() (not executeWithService)
    // because they handle the no-workspace-loaded case themselves, so dispatch via
    // their public execute().
    @Override
    public ToolResponse execute(JsonNode arguments) {
        String action = getStringParam(arguments, "action");
        if (action == null || action.isBlank()) {
            return ToolResponse.invalidParameter("action", "action is required; one of " + ACTIONS);
        }
        return switch (action) {
            case "list"   -> list.execute(arguments);
            case "add"    -> add.execute(arguments);
            case "remove" -> remove.execute(arguments);
            default -> ToolResponse.invalidParameter("action",
                "Unknown action '" + action + "'. Allowed: " + ACTIONS);
        };
    }
}
