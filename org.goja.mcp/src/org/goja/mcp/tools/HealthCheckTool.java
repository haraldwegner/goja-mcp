package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.core.LoadedProject;
import org.goja.mcp.ProjectLoadingState;
import org.goja.mcp.models.ToolResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Health check tool for verifying server status.
 * Adapted from src/main/java/dev/goja/tools/HealthCheckTool.java
 *
 * USAGE: Call on startup to verify server is operational
 * OUTPUT: Server status, project info if loaded, capabilities
 */
public class HealthCheckTool implements Tool {

    private final Supplier<Boolean> projectLoadedSupplier;
    private final Supplier<Integer> toolCountSupplier;
    private final Supplier<ProjectLoadingState> loadingStateSupplier;
    private final Supplier<String> loadingErrorSupplier;
    private final Supplier<IJdtService> serviceSupplier;
    private final Instant startTime;

    public HealthCheckTool(Supplier<Boolean> projectLoadedSupplier,
                           Supplier<Integer> toolCountSupplier,
                           Supplier<ProjectLoadingState> loadingStateSupplier,
                           Supplier<String> loadingErrorSupplier,
                           Supplier<IJdtService> serviceSupplier) {
        this.projectLoadedSupplier = projectLoadedSupplier;
        this.toolCountSupplier = toolCountSupplier;
        this.loadingStateSupplier = loadingStateSupplier;
        this.loadingErrorSupplier = loadingErrorSupplier;
        this.serviceSupplier = serviceSupplier;
        this.startTime = Instant.now();
    }

    /** Back-compat constructor that omits the multi-project service supplier. */
    public HealthCheckTool(Supplier<Boolean> projectLoadedSupplier,
                           Supplier<Integer> toolCountSupplier,
                           Supplier<ProjectLoadingState> loadingStateSupplier,
                           Supplier<String> loadingErrorSupplier) {
        this(projectLoadedSupplier, toolCountSupplier, loadingStateSupplier, loadingErrorSupplier, () -> null);
    }

    @Override
    public String getName() {
        return "health_check";
    }

    @Override
    public String getDescription() {
        return """
            Check server status and project state.

            USAGE: Call on startup to verify server is operational.
            OUTPUT: Server status, project info if loaded, capabilities.

            WORKFLOW:
            1. Call health_check to verify server is running
            2. If no project loaded, call load_project next
            3. Use returned capabilities to understand available features
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
        Map<String, Object> status = new LinkedHashMap<>();
        ProjectLoadingState loadingState = loadingStateSupplier.get();

        // Basic status based on loading state
        String statusMessage = switch (loadingState) {
            case NOT_LOADED -> "Waiting for project";
            case LOADING -> "Loading project...";
            case LOADED -> "Ready";
            case FAILED -> "Project load failed";
        };
        status.put("status", statusMessage);
        status.put("message", "GOJA MCP Server is operational");
        // bugs.md #14: report the real bundle version (same source the initialize
        // handshake uses), not a hardcoded literal.
        status.put("version", org.goja.mcp.protocol.McpProtocolHandler.serverVersion());
        status.put("startedAt", startTime.toString());
        status.put("uptime", getUptimeString());

        // Project status with detailed loading state
        Map<String, Object> projectStatus = new LinkedHashMap<>();
        projectStatus.put("status", loadingState.name().toLowerCase());

        switch (loadingState) {
            case NOT_LOADED -> {
                projectStatus.put("loaded", false);
                projectStatus.put("message", "No project loaded. Use load_project to load a Java project.");
            }
            case LOADING -> {
                projectStatus.put("loaded", false);
                projectStatus.put("message", "Project is loading, please wait...");
            }
            case LOADED -> {
                projectStatus.put("loaded", true);
                projectStatus.put("message", "Project loaded successfully");
            }
            case FAILED -> {
                projectStatus.put("loaded", false);
                projectStatus.put("message", "Project failed to load: " + loadingErrorSupplier.get());
            }
        }
        status.put("project", projectStatus);

        // Multi-project workspace summary (Sprint 10). Adds a `projects`
        // array with one entry per loaded project plus the default key,
        // independent of the legacy single-project status above. Empty
        // array if no projects are loaded or the service isn't ready.
        IJdtService service = serviceSupplier.get();
        if (service != null) {
            List<Map<String, Object>> projects = new ArrayList<>();
            String defaultKey = service.defaultProjectKey().orElse(null);
            for (LoadedProject lp : service.allProjects()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("projectKey", lp.projectKey());
                row.put("projectPath", lp.projectRoot().toString());
                row.put("sourceFileCount", lp.sourceFileCount());
                row.put("isDefault", lp.projectKey().equals(defaultKey));
                projects.add(row);
            }
            status.put("workspace", Map.of(
                "projects", projects,
                "projectCount", projects.size()
            ));
        }

        // Java/OS info
        status.put("java", Map.of(
            "version", System.getProperty("java.version"),
            "vendor", System.getProperty("java.vendor")
        ));
        status.put("os", Map.of(
            "name", System.getProperty("os.name"),
            "arch", System.getProperty("os.arch")
        ));

        // Capabilities
        status.put("capabilities", Map.of(
            "findReferences", true,
            "findImplementations", true,
            "typeHierarchy", true,
            "refactoring", true,
            "diagnostics", true
        ));

        // Configuration
        status.put("configuration", Map.of(
            "timeoutSeconds", getTimeoutSeconds(),
            "absolutePaths", useAbsolutePaths()
        ));

        // Tool count
        status.put("toolCount", toolCountSupplier.get());

        return ToolResponse.success(status);
    }

    private String getUptimeString() {
        long seconds = Duration.between(startTime, Instant.now()).getSeconds();
        if (seconds < 60) {
            return seconds + " seconds";
        } else if (seconds < 3600) {
            return (seconds / 60) + " minutes";
        } else {
            return (seconds / 3600) + " hours";
        }
    }

    private int getTimeoutSeconds() {
        String timeout = System.getenv("GOJA_TIMEOUT_SECONDS");
        if (timeout == null) {
            return 30;
        }
        try {
            int value = Integer.parseInt(timeout);
            return Math.min(Math.max(value, 5), 300);  // Clamp 5-300
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    private boolean useAbsolutePaths() {
        return "true".equalsIgnoreCase(System.getenv("GOJA_ABSOLUTE_PATHS"));
    }
}
