package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.goja.core.IJdtService;
import org.goja.core.LoadedProject;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 14 Phase B.1 (v1.8.0) — {@code refresh_workspace}: consolidated
 * lifecycle tool that does ALL THREE of the steps the EXECSIM-Java sessions
 * surfaced repeatedly:
 *
 * <ol>
 *   <li><b>Refresh from disk</b> — {@code IResource.refreshLocal(DEPTH_INFINITE)}
 *       per loaded project so files created or edited externally
 *       (Write/Edit, manual touch, manager-side mutation) are visible to
 *       JDT. Sidesteps the WorkspaceFileWatcher gap from bugs.md #6.</li>
 *   <li><b>Invalidate JDT's incremental compile cache</b> —
 *       {@code IncrementalProjectBuilder.CLEAN_BUILD} then
 *       {@code FULL_BUILD}. Same path as {@code compile_workspace(clean=true)}
 *       (bugs.md #8) but always-clean by contract.</li>
 *   <li><b>Preserve {@code projectKey} state</b> — does NOT drop or rotate
 *       keys. Closes the bugs.md #11 trap where a caller's projectKey went
 *       stale after the only available "refresh" knob
 *       ({@code remove_project} + {@code add_project}) re-derived a new
 *       key.</li>
 * </ol>
 *
 * <p>Optional {@code projectKey} narrows scope to one loaded project. Omit
 * for full-workspace refresh.</p>
 *
 * <p>Returns the post-rebuild diagnostics in the same shape as
 * {@code compile_workspace}'s output, plus {@code refreshedProjects} and a
 * {@code summary} block with {@code filesRefreshed},
 * {@code classFilesInvalidated}, {@code errorCount}, {@code warningCount}.</p>
 */
public class RefreshWorkspaceTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(RefreshWorkspaceTool.class);

    public RefreshWorkspaceTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "refresh_workspace";
    }

    @Override
    public String getDescription() {
        return """
            Reconcile the JDT workspace state against disk: refresh resources,
            invalidate the incremental compile cache, full-rebuild, return
            aggregated diagnostics. Preserves projectKey state across calls.

            USAGE:
              refresh_workspace()
              refresh_workspace(projectKey="core")

            Inputs:
            - projectKey — optional. Refresh just that project; default is the
              full workspace.

            Steps performed per project:
            1. IResource.refreshLocal(DEPTH_INFINITE) — pick up files
               created/edited externally (Write/Edit/touch/manager mutation)
               that the file watcher may have missed (bugs.md #6).
            2. CLEAN_BUILD then FULL_BUILD — invalidate JDT's incremental
               cache so signature-shape changes surface as real errors
               (bugs.md #8).
            3. Collect IMarker.PROBLEM markers from the rebuilt projects.

            Result:
              { operation, refreshedProjects: [...],
                summary: { filesRefreshed, classFilesInvalidated,
                           errorCount, warningCount },
                diagnostics: [{filePath, line, column, severity, message,
                              sourceProject}] }

            projectKey-state contract: this tool does NOT call addProject /
            removeProject internally. Any projectKey held by the caller
            remains valid across the refresh (bugs.md #11 corollary).
            Compilation errors are a normal RESULT, not a tool failure.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // We deliberately handle projectKey here (not via AbstractTool's
        // scoping wrapper) because the no-projectKey case needs the
        // full multi-project view via service.allProjects().
        String projectKey = getStringParam(arguments, "projectKey");
        Collection<LoadedProject> projects;
        if (projectKey != null && !projectKey.isBlank()) {
            Optional<LoadedProject> scoped = service.getProject(projectKey);
            if (scoped.isEmpty()) {
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
            int filesRefreshed = 0;
            int classFilesInvalidated = 0;
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            List<String> refreshedProjects = new ArrayList<>();

            for (LoadedProject loaded : projects) {
                IJavaProject jp = loaded.javaProject();
                IProject project = jp.getProject();
                refreshedProjects.add(loaded.projectKey());

                // Approximate per-project file count (sum of compilation units
                // across source roots). Reported as filesRefreshed + as
                // classFilesInvalidated since CLEAN_BUILD invalidates every
                // class file for that project's sources.
                int projectFileCount = countCompilationUnits(jp);
                filesRefreshed += projectFileCount;
                classFilesInvalidated += projectFileCount;

                try {
                    project.refreshLocal(IResource.DEPTH_INFINITE,
                        new NullProgressMonitor());
                    project.build(IncrementalProjectBuilder.CLEAN_BUILD,
                        new NullProgressMonitor());
                    project.build(IncrementalProjectBuilder.FULL_BUILD,
                        new NullProgressMonitor());
                } catch (Exception e) {
                    log.warn("Refresh + rebuild failed on project '{}': {}",
                        loaded.projectKey(), e.getMessage(), e);
                    // Continue collecting markers — partial results still useful.
                }

                IMarker[] problems = project.findMarkers(IMarker.PROBLEM, true,
                    IResource.DEPTH_INFINITE);
                for (IMarker marker : problems) {
                    int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                    if (severity < IMarker.SEVERITY_WARNING) continue;
                    Map<String, Object> entry = describeMarker(marker, loaded, service);
                    if (entry != null) {
                        diagnostics.add(entry);
                        if (severity == IMarker.SEVERITY_ERROR) errorCount++;
                        else if (severity == IMarker.SEVERITY_WARNING) warningCount++;
                    }
                }
            }

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("filesRefreshed", filesRefreshed);
            summary.put("classFilesInvalidated", classFilesInvalidated);
            summary.put("errorCount", errorCount);
            summary.put("warningCount", warningCount);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "refresh_workspace");
            data.put("refreshedProjects", refreshedProjects);
            data.put("summary", summary);
            data.put("diagnostics", diagnostics);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(diagnostics.size())
                .returnedCount(diagnostics.size())
                .build());
        } catch (Exception e) {
            log.warn("refresh_workspace threw unexpectedly: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Cheap approximation: walk source roots once, count
     * {@code IPackageFragment}s' compilation units. JDT's
     * {@code IJavaProject.getPackageFragmentRoots()} is in-memory and
     * doesn't trigger I/O after the project is loaded.
     */
    private static int countCompilationUnits(IJavaProject jp) {
        int count = 0;
        try {
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
                for (var element : root.getChildren()) {
                    if (element instanceof IPackageFragment frag) {
                        count += frag.getCompilationUnits().length;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Failed to count CUs for project '{}': {}",
                jp.getElementName(), e.getMessage());
        }
        return count;
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
            if (line > 0) entry.put("line", line - 1);
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

    @SuppressWarnings("unused")
    private static boolean isSourceClasspath(IClasspathEntry entry) {
        return entry.getEntryKind() == IClasspathEntry.CPE_SOURCE;
    }
}
