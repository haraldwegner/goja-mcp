package org.goja.core;

import org.eclipse.jdt.core.IJavaProject;
import org.goja.core.project.ProjectImporter;
import org.goja.core.search.SearchService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Per-project state for a project loaded into the workspace.
 *
 * <p>Sprint 10 introduces multi-project workspaces: a single goja MCP
 * server can hold N projects loaded simultaneously, all sharing one
 * Eclipse workspace. Each project's per-instance state (project root,
 * IJavaProject handle, search service, indexer summary) is captured in
 * one of these records, keyed by {@code projectKey} in the service's
 * project map.
 *
 * @param projectKey         Stable short id derived from project path. See
 *                           {@link ProjectKeys#derive(Path)}. Used as the
 *                           addressable identifier in MCP tool input.
 * @param projectRoot        Absolute, normalized path to the project's
 *                           root directory on disk.
 * @param javaProject        Eclipse JDT {@link IJavaProject} handle.
 * @param searchService      Per-project search service wrapping JDT's
 *                           SearchEngine against this project's classpath.
 * @param pathUtils          Path utilities scoped to this project root.
 * @param loadedAt           Wall-clock timestamp when the project was
 *                           registered with the service.
 * @param sourceFileCount    Number of .java files discovered (for
 *                           health_check / load_project response).
 * @param packageCount       Number of distinct packages.
 * @param packages           Package list (for health_check response).
 * @param buildSystem        Detected build system (Maven / Gradle / Bazel /
 *                           UNKNOWN).
 */
public record LoadedProject(
    String projectKey,
    Path projectRoot,
    IJavaProject javaProject,
    SearchService searchService,
    IPathUtils pathUtils,
    Instant loadedAt,
    int sourceFileCount,
    int packageCount,
    List<String> packages,
    ProjectImporter.BuildSystem buildSystem
) {
    public int classpathEntryCount() {
        try {
            return javaProject != null ? javaProject.getRawClasspath().length : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
