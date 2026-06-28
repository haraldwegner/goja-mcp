package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Remove a project from the workspace without restarting the MCP service.
 * Used by goja-studio when a user removes a project from a running
 * port-grouped workspace (Sprint 10 live update).
 *
 * <p>USAGE: <code>remove_project(projectKey="payments-api")</code>
 *
 * <p>OUTPUT: { projectKey, removed, newDefaultProjectKey }
 *
 * <p>Primarily for orchestration by goja-studio. Idempotent: removing
 * an unknown key returns {removed: false} without error.
 */
public class RemoveProjectTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(RemoveProjectTool.class);

    private final Supplier<IJdtService> serviceSupplier;

    public RemoveProjectTool(Supplier<IJdtService> serviceSupplier) {
        this.serviceSupplier = serviceSupplier;
    }

    @Override
    public String getName() {
        return "remove_project";
    }

    @Override
    public String getDescription() {
        return """
            Remove a project from the workspace without restarting the MCP
            service. Idempotent — removing an unknown key returns
            {removed: false} without error.

            USAGE: remove_project(projectKey="payments-api")
            OUTPUT: { projectKey, removed, newDefaultProjectKey }

            If the removed project was the default (the one tools use when
            no projectKey is specified), the next available project becomes
            the default. If no projects remain, the service is left empty
            and most analysis tools will report "no project loaded" until
            load_project or add_project is called.

            Primarily intended for orchestration by goja-studio.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "projectKey", Map.of(
                "type", "string",
                "description", "Project key returned by load_project, add_project, or list_projects"
            )
        ));
        schema.put("required", List.of("projectKey"));
        return schema;
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        if (arguments == null || !arguments.has("projectKey")) {
            return ToolResponse.invalidParameter("projectKey", "Required parameter missing");
        }
        IJdtService service = serviceSupplier.get();
        if (service == null) {
            return ToolResponse.error(
                "PROJECT_NOT_LOADED",
                "No workspace exists yet. There is nothing to remove.",
                "Call load_project to create a workspace before invoking remove_project.");
        }

        String projectKey = arguments.get("projectKey").asText();

        try {
            log.info("Removing project '{}' from workspace", projectKey);
            boolean removed = service.removeProject(projectKey);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("projectKey", projectKey);
            result.put("removed", removed);
            service.defaultProjectKey().ifPresent(k -> result.put("newDefaultProjectKey", k));
            return ToolResponse.success(result);

        } catch (Exception e) {
            log.error("Failed to remove project", e);
            return ToolResponse.internalError(e);
        }
    }
}
