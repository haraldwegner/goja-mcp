package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.core.LoadedProject;
import org.goja.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Append a Java project to the existing workspace without clearing previously
 * loaded projects. Used by goja-studio to assemble multi-project
 * workspaces (Sprint 10 port-grouped service consolidation).
 *
 * <p>USAGE: <code>add_project(projectPath="/path/to/project")</code>
 *
 * <p>OUTPUT: { projectKey, projectPath, sourceFileCount, packageCount, status }
 *
 * <p>Primarily for orchestration by goja-studio. AI agents typically
 * don't need to call this directly during analysis — the manager pre-loads
 * the workspace's projects on service startup. Calling add_project after
 * load_project appends without clearing the loaded set.
 */
public class AddProjectTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AddProjectTool.class);

    private final Supplier<IJdtService> serviceSupplier;

    public AddProjectTool(Supplier<IJdtService> serviceSupplier) {
        this.serviceSupplier = serviceSupplier;
    }

    @Override
    public String getName() {
        return "add_project";
    }

    @Override
    public String getDescription() {
        return """
            Append a Java project to the existing workspace without clearing
            previously loaded projects. Use this to build a multi-project
            workspace where related projects can be queried together.

            USAGE: add_project(projectPath="/absolute/path/to/project")
            OUTPUT: { projectKey, projectPath, sourceFileCount, packageCount, buildSystem, status }

            DIFFERS FROM load_project:
            - load_project clears the workspace and loads ONE project (default project).
            - add_project APPENDS without clearing. The default project (used by
              tools when projectKey is not specified) does not change.

            REQUIRES: load_project must have been called first to establish a
            base workspace; otherwise add_project promotes itself to default.

            Primarily intended for orchestration by goja-studio. Manual
            multi-project workflows are also supported.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "projectPath", Map.of(
                "type", "string",
                "description", "Absolute path to the project root directory"
            )
        ));
        schema.put("required", List.of("projectPath"));
        return schema;
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        if (arguments == null || !arguments.has("projectPath")) {
            return ToolResponse.invalidParameter("projectPath", "Required parameter missing");
        }
        IJdtService service = serviceSupplier.get();
        if (service == null) {
            return ToolResponse.error(
                "PROJECT_NOT_LOADED",
                "No workspace exists yet. Call load_project before add_project.",
                "Call load_project with the first project's path, then add_project for each additional project.");
        }

        String projectPath = arguments.get("projectPath").asText();

        try {
            Path path = Path.of(projectPath).toAbsolutePath().normalize();

            if (!Files.exists(path)) {
                return ToolResponse.error("FILE_NOT_FOUND",
                    "Project path not found: " + projectPath,
                    "Verify the path exists and is accessible");
            }
            if (!Files.isDirectory(path)) {
                return ToolResponse.error("INVALID_PARAMETER",
                    "Project path is not a directory: " + projectPath,
                    "Provide path to project root directory");
            }

            log.info("Appending project to workspace: {}", path);
            LoadedProject loaded = service.addProject(path);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "loaded");
            result.put("projectKey", loaded.projectKey());
            result.put("projectPath", loaded.projectRoot().toString());
            result.put("buildSystem", loaded.buildSystem().name().toLowerCase());
            result.put("sourceFileCount", loaded.sourceFileCount());
            result.put("packageCount", loaded.packageCount());
            result.put("classpathEntryCount", loaded.classpathEntryCount());
            result.put("loadedAt", loaded.loadedAt().toString());
            return ToolResponse.success(result);

        } catch (Exception e) {
            log.error("Failed to add project", e);
            return ToolResponse.internalError(e);
        }
    }
}
