package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Load a Java project for analysis.
 * MUST be called before using navigation/analysis tools.
 *
 * Adapted from src/main/java/dev/goja/tools/LoadProjectTool.java
 */
public class LoadProjectTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(LoadProjectTool.class);

    private final Supplier<IJdtService> serviceSupplier;
    private final Consumer<IJdtService> serviceSetter;

    public LoadProjectTool(Supplier<IJdtService> serviceSupplier, Consumer<IJdtService> serviceSetter) {
        this.serviceSupplier = serviceSupplier;
        this.serviceSetter = serviceSetter;
    }

    /** Back-compat constructor: setter-only. New service per call. */
    public LoadProjectTool(Consumer<IJdtService> serviceSetter) {
        this(() -> null, serviceSetter);
    }

    @Override
    public String getName() {
        return "load_project";
    }

    @Override
    public String getDescription() {
        return """
            Load a Java project for analysis.
            MUST be called before using other analysis tools.

            USAGE: load_project(projectPath="/path/to/project")
            OUTPUT: Project structure summary including packages, source files, build system

            Supports:
            - Maven projects (pom.xml), including <sourceDirectory> / <testSourceDirectory> overrides
            - Gradle projects (build.gradle or build.gradle.kts)
            - Eclipse projects (.classpath src/lib entries honored when present)
            - Plain Java projects with src/ directory

            WORKFLOW:
            1. Call load_project with absolute path to project root
            2. Wait for project to load (may take a few seconds for large projects)
            3. Use health_check to verify project is loaded
            4. Begin using analysis tools (search_symbols, find_references, etc.)
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "projectPath", Map.of(
                "type", "string",
                "description", "Absolute path to the project root directory containing pom.xml or build.gradle"
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

        String projectPath = arguments.get("projectPath").asText();

        try {
            Path path = Path.of(projectPath).toAbsolutePath().normalize();

            // Validate path
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

            log.info("Loading project: {}", path);

            // Reuse the existing service instance if one is already in place,
            // so add_project / remove_project / list_projects (and any
            // already-running analysis tools) keep operating on the same
            // workspace. The service's loadProject() implements clear-and-
            // load semantics: it wipes any previously loaded projects and
            // installs the new one as the default. Sprint 10 ADR Q6.
            JdtServiceImpl service = (JdtServiceImpl) serviceSupplier.get();
            if (service == null) {
                service = new JdtServiceImpl();
                serviceSetter.accept(service);
            }
            service.loadProject(path);

            // Build response
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("loaded", true);
            result.put("projectPath", service.getPathUtils().formatPath(path));
            result.put("buildSystem", service.getBuildSystem().name().toLowerCase());
            result.put("sourceFileCount", service.getSourceFileCount());
            result.put("packageCount", service.getPackageCount());

            // Include first 20 packages
            List<String> packages = service.getPackages();
            result.put("packages", packages.size() <= 20 ? packages : packages.subList(0, 20));

            result.put("classpathEntryCount", service.getClasspathEntryCount());
            result.put("loadedAt", service.getLoadedAt().toString());

            log.info("Project loaded successfully: {} files, {} packages",
                service.getSourceFileCount(), service.getPackageCount());

            return ToolResponse.success(result);

        } catch (Exception e) {
            log.error("Failed to load project", e);
            return ToolResponse.internalError(e);
        }
    }
}
