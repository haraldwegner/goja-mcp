package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 12 (v1.6.0) — {@code compile_workspace}: trigger an incremental
 * build over every loaded project in the workspace and return aggregated
 * problem markers.
 *
 * <p>Closes the {@code refactor → compile → fix} loop the agent previously
 * had to drive via per-file {@code get_diagnostics} (incomplete: misses
 * cascading errors in untouched files and project-level errors like broken
 * {@code Require-Bundle} or unresolved Tycho deps) or shelling out to
 * Maven / Gradle (slow, suffers classpath drift between IDE and CLI).</p>
 *
 * <p>Uses {@link IResource#findMarkers} on {@link IMarker#PROBLEM} markers,
 * not AST reconcile (the path {@link GetDiagnosticsTool} takes). The
 * incremental builder writes project-level markers — Java compiler errors,
 * classpath errors, manifest errors — that AST reconcile alone misses.</p>
 */
public class CompileWorkspaceTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(CompileWorkspaceTool.class);

    public CompileWorkspaceTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "compile_workspace";
    }

    @Override
    public String getDescription() {
        return """
            Run an incremental Java build over every loaded project (or one
            specific project when projectKey is set) and return aggregated
            problem markers — compiler errors, warnings, and project-level
            errors (broken Require-Bundle, unresolved classpath, manifest
            errors) in one call.

            USAGE:
              compile_workspace()
              compile_workspace(projectKey="core")
              compile_workspace(minSeverity="WARNING")
              compile_workspace(includeTaskMarkers=true)
              compile_workspace(clean=true)
              compile_workspace(scope="test")

            Inputs:
            - projectKey — optional. Compile just that project; default is the
              full workspace.
            - minSeverity — "ERROR" (default) or "WARNING". Markers below the
              threshold are dropped from the result.
            - includeTaskMarkers — default false. When true, TODO/FIXME markers
              are included in addition to PROBLEM markers.
            - clean — default false. When true, CLEAN_BUILD precedes FULL_BUILD
              so JDT's incremental compile cache is invalidated. Use when a
              record's canonical constructor shape changes or any other
              signature-shape edit that the incremental builder would otherwise
              skip recompiling consumers for (bugs.md #8).
            - scope — "main" | "test" | "both" (default "both"). Filters
              diagnostics by the source root the file lives under (Maven
              src/main/* vs src/test/* convention). When the project compiles
              cleanly but test sources have errors, scope="test" surfaces them
              explicitly (bugs.md #9).
            - summary — default false. When true, return counts only
              (errorCount/warningCount + byProject) with NO diagnostics array —
              the consumable shape when a broken classpath yields thousands of
              errors (v2.7.1).
            - limit / offset — page the diagnostics array (default limit 100,
              offset 0). errorCount/warningCount/count always reflect the FULL
              set; `truncated` + `hint` flag a capped page (v2.7.1).

            Result:
              { operation, projectsCompiled, errorCount, warningCount, count,
                offset, limit, returnedCount, truncated,
                diagnostics: [{filePath, line, column, severity, message,
                              sourceProject}] }

            Compilation errors are a normal RESULT (returned in `diagnostics`).
            The tool itself only fails on missing projectKey or aborted build.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("minSeverity", Map.of(
            "type", "string",
            "enum", List.of("ERROR", "WARNING"),
            "description", "Drop diagnostics below this severity. Default: ERROR."));
        properties.put("includeTaskMarkers", Map.of(
            "type", "boolean",
            "description", "Include TODO/FIXME markers (default false)."));
        properties.put("clean", Map.of(
            "type", "boolean",
            "description", "When true, CLEAN_BUILD precedes FULL_BUILD so JDT's incremental compile cache is invalidated (bugs.md #8). Default false."));
        properties.put("scope", Map.of(
            "type", "string",
            "enum", List.of("main", "test", "both"),
            "description", "Filter diagnostics by source-root convention (src/main/* vs src/test/*). Default 'both' (bugs.md #9)."));
        properties.put("summary", Map.of(
            "type", "boolean",
            "description", "Counts only (errorCount/warningCount + byProject), NO diagnostics array — for workspaces with thousands of errors (v2.7.1). Default false."));
        properties.put("limit", Map.of(
            "type", "integer",
            "description", "Max diagnostics returned (default 100); counts always reflect the full set (v2.7.1)."));
        properties.put("offset", Map.of(
            "type", "integer",
            "description", "Skip the first N diagnostics (pagination; default 0) (v2.7.1)."));
        schema.put("properties", properties);
        return withProjectKey(schema);
    }

    /** v2.7.1: default page size — mirrors find_quality_issue's v2.6.1 cap. */
    private static final int DEFAULT_DIAGNOSTICS_LIMIT = 100;

    private static final Set<String> VALID_SCOPES = Set.of("main", "test", "both");

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String minSeverityRaw = getStringParam(arguments, "minSeverity", "ERROR");
        boolean includeTaskMarkers = getBooleanParam(arguments, "includeTaskMarkers", false);
        boolean cleanFirst = getBooleanParam(arguments, "clean", false);
        String scope = getStringParam(arguments, "scope", "both");

        if (!VALID_SCOPES.contains(scope)) {
            return ToolResponse.invalidParameter("scope",
                "Must be 'main', 'test', or 'both'; got '" + scope + "'");
        }

        int minSeverity;
        switch (minSeverityRaw.toUpperCase()) {
            case "ERROR" -> minSeverity = IMarker.SEVERITY_ERROR;
            case "WARNING" -> minSeverity = IMarker.SEVERITY_WARNING;
            default -> {
                return ToolResponse.invalidParameter("minSeverity",
                    "Must be 'ERROR' or 'WARNING'; got '" + minSeverityRaw + "'");
            }
        }

        // AbstractTool.execute already validated `projectKey` if present and
        // wrapped the service in a ScopedJdtService; however, ScopedJdtService
        // delegates allProjects() to the underlying multi-project service, so
        // we need to choose the project set ourselves. When projectKey is set,
        // operate on just that one; otherwise walk every loaded project.
        String projectKey = getStringParam(arguments, "projectKey");
        Collection<LoadedProject> projects;
        if (projectKey != null && !projectKey.isBlank()) {
            Optional<LoadedProject> scoped = service.getProject(projectKey);
            if (scoped.isEmpty()) {
                // bugs.md #11 (Sprint 14): same dropped-vs-typo distinction
                // as AbstractTool.execute. AbstractTool doesn't fire for this
                // tool because compile_workspace handles its own projectKey
                // (it needs unscoped allProjects() when none is set).
                Optional<Long> dropped = service.wasRecentlyDropped(projectKey);
                if (dropped.isPresent()) {
                    return ToolResponse.projectKeyDropped(projectKey, dropped.get());
                }
                return ToolResponse.invalidParameter("projectKey",
                    "Unknown projectKey '" + projectKey + "'. Use list_projects.");
            }
            projects = List.of(scoped.get());
        } else {
            projects = service.allProjects();
        }

        try {
            int errorCount = 0;
            int warningCount = 0;
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            int compiled = 0;

            for (LoadedProject loaded : projects) {
                IProject project = loaded.javaProject().getProject();
                try {
                    // Pick up any filesystem changes the agent / user made
                    // since the project was loaded — without this, an
                    // incremental build won't see new or edited source files
                    // (Eclipse-linked folders need an explicit refreshLocal
                    // to repopulate the resource tree). We use FULL_BUILD
                    // because INCREMENTAL_BUILD's delta tracking lags fresh
                    // refreshLocal additions in Tycho-test-style headless
                    // Equinox runtimes — and a full build over a workspace
                    // already loaded into memory is fast enough that the
                    // simpler / more reliable mode wins.
                    //
                    // Sprint 14 (bugs.md #8): when clean=true, CLEAN_BUILD
                    // precedes FULL_BUILD so JDT discards its incremental
                    // compile cache. Required when a record's canonical
                    // constructor shape changes (or any other signature edit
                    // that the incremental builder would skip recompiling
                    // consumers for).
                    project.refreshLocal(IResource.DEPTH_INFINITE,
                        new NullProgressMonitor());
                    if (cleanFirst) {
                        project.build(IncrementalProjectBuilder.CLEAN_BUILD,
                            new NullProgressMonitor());
                    }
                    project.build(IncrementalProjectBuilder.FULL_BUILD,
                        new NullProgressMonitor());
                } catch (Exception e) {
                    log.warn("Build failed on project '{}': {}", loaded.projectKey(), e.getMessage(), e);
                    // Continue collecting markers — partial results are still useful.
                }
                compiled++;

                IMarker[] problems = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
                for (IMarker marker : problems) {
                    int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                    if (severity < minSeverity) continue;
                    if (!matchesScope(marker.getResource(), scope)) continue;
                    Map<String, Object> entry = describeMarker(marker, loaded, service);
                    if (entry != null) {
                        diagnostics.add(entry);
                        if (severity == IMarker.SEVERITY_ERROR) errorCount++;
                        else if (severity == IMarker.SEVERITY_WARNING) warningCount++;
                    }
                }

                if (includeTaskMarkers) {
                    IMarker[] tasks = project.findMarkers(IMarker.TASK, true, IResource.DEPTH_INFINITE);
                    for (IMarker task : tasks) {
                        if (!matchesScope(task.getResource(), scope)) continue;
                        Map<String, Object> entry = describeMarker(task, loaded, service);
                        if (entry != null) diagnostics.add(entry);
                    }
                }
            }

            // v2.7.1 (dogfood 2026-07-10): a classpath-broken workspace produced
            // 17,383 diagnostics in one 4.25 MB response — unconsumable for any
            // MCP client. Bound the array the same way find_quality_issue was
            // bounded in v2.6.1: summary → counts only; otherwise page with
            // limit/offset. The counts always reflect the FULL set.
            int total = diagnostics.size();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "compile_workspace");
            data.put("projectsCompiled", compiled);
            data.put("errorCount", errorCount);
            data.put("warningCount", warningCount);

            if (getBooleanParam(arguments, "summary", false)) {
                Map<String, Integer> byProject = new LinkedHashMap<>();
                for (Map<String, Object> d : diagnostics) {
                    byProject.merge(String.valueOf(d.get("sourceProject")), 1, Integer::sum);
                }
                data.put("summary", true);
                data.put("count", total);
                data.put("byProject", byProject);
                return ToolResponse.success(data, ResponseMeta.builder()
                    .totalCount(total)
                    .returnedCount(0)
                    .build());
            }

            int offset = Math.max(0, getIntParam(arguments, "offset", 0));
            int limit = getIntParam(arguments, "limit", DEFAULT_DIAGNOSTICS_LIMIT);
            if (limit <= 0) {
                limit = DEFAULT_DIAGNOSTICS_LIMIT;
            }
            int from = Math.min(offset, total);
            int to = Math.min(from + limit, total);
            List<Map<String, Object>> page = new ArrayList<>(diagnostics.subList(from, to));
            boolean truncated = to < total || from > 0;
            data.put("count", total);
            data.put("offset", offset);
            data.put("limit", limit);
            data.put("returnedCount", page.size());
            data.put("truncated", truncated);
            data.put("diagnostics", page);
            if (truncated) {
                data.put("hint", "showing diagnostics " + from + "–" + to + " of " + total
                    + " — use summary:true for counts only, or offset/limit to page");
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total)
                .returnedCount(page.size())
                .build());
        } catch (Exception e) {
            log.warn("compile_workspace threw unexpectedly: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Sprint 14 (bugs.md #9): classify the file as main-source or test-source
     * based on the Maven convention {@code src/main/*} vs {@code src/test/*}.
     * Linked-folder resources inside the Eclipse workspace point at the
     * original on-disk path via {@link IResource#getLocation()}, so the same
     * convention still applies.
     *
     * <p>Falls open (no filter) when:</p>
     * <ul>
     *   <li>scope = "both" (the default)</li>
     *   <li>the resource is null or has no location (project-level markers)</li>
     *   <li>the resource doesn't sit under any {@code src/main/} or
     *       {@code src/test/} segment (project-level errors, build-config
     *       errors, etc.)</li>
     * </ul>
     */
    private static boolean matchesScope(org.eclipse.core.resources.IResource resource, String scope) {
        if ("both".equals(scope)) return true;
        if (resource == null) return true;
        org.eclipse.core.runtime.IPath location = resource.getLocation();
        if (location == null) return true;
        String pathStr = location.toString();
        boolean inTest = pathStr.contains("/src/test/") || pathStr.contains("\\src\\test\\");
        boolean inMain = pathStr.contains("/src/main/") || pathStr.contains("\\src\\main\\");
        // Project-level markers (no main/test designation) always surface,
        // regardless of scope — they're cross-cutting (missing classpath,
        // broken manifest, etc.).
        if (!inTest && !inMain) return true;
        return "test".equals(scope) ? inTest : inMain;
    }

    private static Map<String, Object> describeMarker(IMarker marker,
                                                       LoadedProject loaded,
                                                       IJdtService service) {
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            IResource resource = marker.getResource();
            if (resource != null && resource.getLocation() != null) {
                IPath location = resource.getLocation();
                entry.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
            }
            int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
            if (line > 0) entry.put("line", line - 1); // markers are 1-based; tools are 0-based
            int charStart = marker.getAttribute(IMarker.CHAR_START, -1);
            if (charStart >= 0) entry.put("charStart", charStart);
            int charEnd = marker.getAttribute(IMarker.CHAR_END, -1);
            if (charEnd >= 0) entry.put("charEnd", charEnd);
            int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            entry.put("severity", switch (severity) {
                case IMarker.SEVERITY_ERROR -> "ERROR";
                case IMarker.SEVERITY_WARNING -> "WARNING";
                default -> "INFO";
            });
            String message = marker.getAttribute(IMarker.MESSAGE, "");
            entry.put("message", message);
            entry.put("sourceProject", loaded.projectKey());
            return entry;
        } catch (Exception e) {
            return null;
        }
    }
}
