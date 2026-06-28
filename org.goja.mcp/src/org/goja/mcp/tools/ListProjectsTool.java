package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.core.LoadedProject;
import org.goja.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * List all projects currently loaded into the workspace.
 *
 * <p>USAGE: <code>list_projects()</code>
 *
 * <p>OUTPUT: { defaultProjectKey, projects: [{ projectKey, projectPath,
 *   sourceFileCount, packageCount, buildSystem, isDefault }] }
 *
 * <p>Useful for AI agents working in a multi-project workspace to see what
 * project keys they can pass via the optional <code>projectKey</code>
 * parameter on analysis tools, and to know which one is the default
 * (used when projectKey is unset).
 */
public class ListProjectsTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ListProjectsTool.class);

    private final Supplier<IJdtService> serviceSupplier;

    public ListProjectsTool(Supplier<IJdtService> serviceSupplier) {
        this.serviceSupplier = serviceSupplier;
    }

    @Override
    public String getName() {
        return "list_projects";
    }

    @Override
    public String getDescription() {
        return """
            List all projects currently loaded into the workspace.

            USAGE: list_projects()
            OUTPUT: { defaultProjectKey, projects: [{ projectKey, projectPath,
                sourceFileCount, packageCount, buildSystem, isDefault }] }

            Use this to discover the projectKey values you can pass to
            analysis tools' optional projectKey parameter. The default
            project (used when projectKey is not specified) is flagged
            with isDefault=true.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.put("required", List.of());
        return schema;
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        IJdtService service = serviceSupplier.get();
        if (service == null) {
            // Empty result rather than error — listing an empty workspace is
            // a valid query.
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("projects", List.of());
            return ToolResponse.success(empty);
        }

        try {
            String defaultKey = service.defaultProjectKey().orElse(null);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (LoadedProject lp : service.allProjects()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("projectKey", lp.projectKey());
                row.put("projectPath", lp.projectRoot().toString());
                // v1.7.1 (bug #4): hyphen instead of underscore on the wire so
                // ECLIPSE_PDE serialises as "eclipse-pde" rather than "eclipse_pde".
                row.put("buildSystem", lp.buildSystem().name().toLowerCase().replace('_', '-'));
                row.put("sourceFileCount", lp.sourceFileCount());
                row.put("packageCount", lp.packageCount());
                row.put("classpathEntryCount", lp.classpathEntryCount());
                row.put("loadedAt", lp.loadedAt().toString());
                row.put("isDefault", lp.projectKey().equals(defaultKey));
                rows.add(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            if (defaultKey != null) {
                result.put("defaultProjectKey", defaultKey);
            }
            result.put("projects", rows);
            return ToolResponse.success(result);

        } catch (Exception e) {
            log.error("Failed to list projects", e);
            return ToolResponse.internalError(e);
        }
    }
}
