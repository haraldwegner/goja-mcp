package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.ProjectLoadingState;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.WorkspaceHealth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Health check tool for verifying server status.
 * Adapted from src/main/java/dev/jawata/tools/HealthCheckTool.java
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
            OUTPUT: Server status, project info, capabilities, and WORKSPACE HEALTH.

            WORKFLOW:
            1. Call health_check to verify server is running
            2. If no project loaded, call load_project next
            3. Use returned capabilities to understand available features

            WORKSPACE HEALTH — read `workspace.healthy`. When it is false, one or more
            projects cannot be READ (closed, deleted, or a broken classpath). Then:
              • every whole-workspace analysis is incomplete — a tool reporting "nothing
                found" may simply have been unable to look;
              • REFACTORINGS ARE REFUSED, because a rename cannot see references in a project
                it cannot read and would leave them silently broken.
            Each unhealthy project carries a `remedy`. TELL THE USER — it is their workspace
            and only they can fix it. Do not work around it, and do not fall back to grep.
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
        status.put("message", "JAWATA MCP Server is operational");
        // bugs.md #14: report the real bundle version (same source the initialize
        // handshake uses), not a hardcoded literal.
        status.put("version", org.jawata.mcp.protocol.McpProtocolHandler.serverVersion());
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
            List<String> unhealthy = new ArrayList<>();
            String defaultKey = service.defaultProjectKey().orElse(null);
            for (LoadedProject lp : service.allProjects()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("projectKey", lp.projectKey());
                row.put("projectPath", lp.projectRoot().toString());
                row.put("sourceFileCount", lp.sourceFileCount());
                row.put("isDefault", lp.projectKey().equals(defaultKey));

                projects.add(row);
            }

            // IS THIS WORKSPACE FIT TO BE USED? A project can be registered and yet be
            // closed, or gone, or have a broken classpath. Every whole-workspace analysis
            // then walks it, fails, and (until this sprint) reported an empty result as a
            // finding — and a REFACTORING would rewrite references while blind to that
            // project's call sites. It is the user's workspace and the user's fault to fix,
            // so it is stated here, in the first call anyone makes, with the remedy attached.
            List<WorkspaceHealth.Problem> problems = WorkspaceHealth.diagnose(service);
            for (Map<String, Object> row : projects) {
                String key = String.valueOf(row.get("projectKey"));
                WorkspaceHealth.Problem p = problems.stream()
                    .filter(x -> x.projectKey().equals(key)).findFirst().orElse(null);
                row.put("healthy", p == null);
                if (p != null) {
                    row.put("problem", p.problem());
                    row.put("remedy", p.remedy());
                    unhealthy.add(p.projectKey() + ": " + p.problem());
                }
            }

            Map<String, Object> workspace = new LinkedHashMap<>();
            workspace.put("projects", projects);
            workspace.put("projectCount", projects.size());
            workspace.put("healthy", unhealthy.isEmpty());
            if (!unhealthy.isEmpty()) {
                workspace.put("unhealthyProjects", unhealthy);
                workspace.put("warning", "This workspace has " + unhealthy.size() + " project(s) "
                    + "that CANNOT BE READ. Every whole-workspace analysis will be incomplete, "
                    + "and any tool reporting 'nothing found' may simply have been unable to "
                    + "look. REFACTORINGS ARE REFUSED while this is true — a rename cannot see "
                    + "references in a project it cannot read, so it would silently leave them "
                    + "broken. Each project above carries its remedy. This is a configuration "
                    + "fault for the workspace's owner to fix; it is not something an agent "
                    + "should work around.");
            }
            status.put("workspace", workspace);
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

        // Sprint 27 D1: the embedder, reported as what it IS rather than as
        // what was configured. The launch flag only makes the Vector API
        // backend POSSIBLE — whether it loaded is a fact of this JVM, and the
        // difference is invisible in results (both backends produce identical
        // vectors) while being a ~3x difference in speed. So it is stated
        // here, and stated as measured: `backend` is the implementation that
        // actually answered a probe multiplication at startup, not an
        // inference from the command line.
        Map<String, Object> embedder = new LinkedHashMap<>();
        embedder.put("backend", org.jawata.mcp.embed.MatMuls.activeName());
        embedder.put("vectorApi",
            !"scalar".equalsIgnoreCase(org.jawata.mcp.embed.MatMuls.activeName()));
        try {
            org.jawata.mcp.knowledge.EmbeddingService svc =
                org.jawata.mcp.knowledge.EmbeddingService.shared();
            embedder.put("available", svc.available());
            if (svc.available()) {
                embedder.put("identity", svc.identityKey());
            } else {
                // An unavailable embedder is a REASON, not a bare false: the
                // semantic half of recall is off and somebody has to know why.
                embedder.put("note", "semantic recall is OFF — recall falls back to"
                    + " keyword/alias matching exactly as it did before Sprint 27");
            }
        } catch (RuntimeException e) {
            embedder.put("available", false);
            embedder.put("note", "embedder status could not be read: " + e.getMessage());
        }
        status.put("embedder", embedder);

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
        String timeout = System.getenv("JAWATA_TIMEOUT_SECONDS");
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
        return "true".equalsIgnoreCase(System.getenv("JAWATA_ABSOLUTE_PATHS"));
    }
}
