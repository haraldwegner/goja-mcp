package org.goja.core;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ISourceRange;
import org.goja.core.project.ProjectImporter;
import org.goja.core.search.SearchService;
import org.goja.core.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Main JDT service implementation.
 * Ties together workspace management, project import, and search capabilities.
 */
public class JdtServiceImpl implements IJdtService {

    private static final Logger log = LoggerFactory.getLogger(JdtServiceImpl.class);

    private final WorkspaceManager workspaceManager;
    private final ProjectImporter projectImporter;
    private final int timeoutSeconds;

    private Path projectRoot;
    private IPathUtils pathUtils;
    private IJavaProject javaProject;
    private SearchService searchService;
    private Instant loadedAt;

    // Project info for health_check
    private int sourceFileCount;
    private int packageCount;
    private List<String> packages;
    private ProjectImporter.BuildSystem buildSystem;

    // Sprint 10 multi-project state. Mirrors the legacy single-project fields
    // above for the default project, so single-project getters continue to
    // work unchanged. Additional projects loaded via addProject(Path) are
    // tracked here keyed by ProjectKeys.derive(path). The default key
    // points at the project returned by the legacy getJavaProject() etc.
    private final Map<String, LoadedProject> projectsByKey = new ConcurrentHashMap<>();
    private volatile String defaultProjectKey;

    /**
     * Sprint 14 (bugs.md #11): tracks recently-removed project keys so a
     * caller holding a stale key gets a distinct {@code PROJECT_KEY_DROPPED}
     * error (with the drop timestamp) instead of an ambiguous
     * {@code INVALID_PARAMETER}. Entries expire after {@link #DROP_TTL_MILLIS}
     * so an old typo eventually falls through to the regular not-found path.
     */
    private final Map<String, Long> droppedKeyTimestamps = new ConcurrentHashMap<>();

    /**
     * How long {@link #droppedKeyTimestamps} retains an entry. 5 minutes is
     * long enough for a multi-turn agent session to recognise the drop and
     * re-acquire via {@code list_projects}, short enough that a long-past
     * typo doesn't surface as "dropped" weeks later.
     */
    static final long DROP_TTL_MILLIS = 5L * 60 * 1000;

    // Workspace-scoped SearchService — searches across all loaded projects.
    // Lazily built on first access, invalidated whenever the project map
    // changes (addProject / removeProject / loadProject). With a single
    // project loaded the scope contains that one project; behavior is
    // identical to the per-project SearchService. With N projects, search
    // sees all N classpaths at once — the expected behavior under Sprint
    // 10's port-grouped service consolidation.
    private volatile SearchService workspaceSearchService;

    public JdtServiceImpl() {
        this.workspaceManager = new WorkspaceManager();
        this.projectImporter = new ProjectImporter();
        this.timeoutSeconds = parseTimeout();
    }

    private static int parseTimeout() {
        String timeout = System.getenv("GOJA_TIMEOUT_SECONDS");
        if (timeout == null) return 30;
        try {
            int seconds = Integer.parseInt(timeout);
            return Math.max(5, Math.min(seconds, 300));
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    /**
     * Load a project for analysis as the sole / default project, replacing
     * any previously loaded projects.
     *
     * <p>Sprint 10 semantics: this method clears the multi-project map and
     * registers the loaded project as the default. Use {@link #addProject}
     * to append a project to a multi-project workspace without clearing.
     *
     * @param path Path to the project root
     * @throws CoreException if project loading fails
     */
    public void loadProject(Path path) throws CoreException {
        log.info("Loading project: {}", path);

        // Clear the multi-project map — load_project semantics are
        // "wipe and load one", per Sprint 10 ADR Q6.
        projectsByKey.clear();
        defaultProjectKey = null;
        workspaceSearchService = null;

        LoadedProject loaded = loadInternal(path);
        defaultProjectKey = loaded.projectKey();

        // Mirror the loaded project into the legacy single-project fields
        // so existing tools that read getJavaProject() / getProjectRoot()
        // / getSourceFileCount() / etc. continue to work without changes.
        applyToLegacyFields(loaded);

        log.info("Project loaded successfully at {} as default key '{}'",
            loaded.loadedAt(), defaultProjectKey);
    }

    /**
     * Append a project to the workspace without clearing existing projects.
     * The default project key is unchanged; the appended project is
     * accessible via {@link #getProject(String)} using its derived key.
     *
     * @param path Path to the project root
     * @return the registered LoadedProject (with its derived key)
     * @throws CoreException if project loading fails
     */
    public LoadedProject addProject(Path path) throws CoreException {
        log.info("Adding project to workspace: {}", path);
        LoadedProject loaded = loadInternal(path);
        workspaceSearchService = null;  // scope changed — rebuild on next access
        // bugs.md #11 (Sprint 14): re-adding a project with the same key as a
        // previously-dropped one clears the dropped-key entry. Otherwise the
        // dropped marker would shadow the live project until TTL expiry.
        droppedKeyTimestamps.remove(loaded.projectKey());
        if (defaultProjectKey == null) {
            // First project ever — promote to default so single-project
            // tools have something to read.
            defaultProjectKey = loaded.projectKey();
            applyToLegacyFields(loaded);
        }
        log.info("Project added at {} with key '{}'", loaded.loadedAt(), loaded.projectKey());
        return loaded;
    }

    /**
     * Sprint 14 (bugs.md #11): if a caller holds a {@code projectKey} that
     * USED to be valid but has been dropped, return the drop timestamp for
     * up to {@link #DROP_TTL_MILLIS} after the drop. Past the TTL, the entry
     * is evicted and this returns {@link Optional#empty()}.
     */
    @Override
    public Optional<Long> wasRecentlyDropped(String projectKey) {
        Long ts = droppedKeyTimestamps.get(projectKey);
        if (ts == null) return Optional.empty();
        if (System.currentTimeMillis() - ts > DROP_TTL_MILLIS) {
            droppedKeyTimestamps.remove(projectKey, ts);
            return Optional.empty();
        }
        return Optional.of(ts);
    }

    /**
     * Test-only: forcibly expire a dropped-key entry by setting its
     * timestamp far enough in the past that {@link #wasRecentlyDropped}
     * sees it as expired on the next call. Lets the dedicated TTL test run
     * without sleeping for {@link #DROP_TTL_MILLIS}.
     */
    void expireDroppedKeyForTest(String projectKey) {
        droppedKeyTimestamps.computeIfPresent(projectKey,
            (k, v) -> System.currentTimeMillis() - DROP_TTL_MILLIS - 1);
    }

    /**
     * Remove a project from the workspace. If the removed project was the
     * default, the next available project (if any) becomes the default.
     *
     * @param projectKey key of the project to remove
     * @return true if a project with that key was loaded and removed
     */
    public boolean removeProject(String projectKey) {
        LoadedProject removed = projectsByKey.remove(projectKey);
        if (removed == null) {
            return false;
        }
        // bugs.md #11 (Sprint 14): record the drop so a stale caller gets a
        // distinct PROJECT_KEY_DROPPED instead of INVALID_PARAMETER.
        droppedKeyTimestamps.put(projectKey, System.currentTimeMillis());
        workspaceSearchService = null;  // scope changed — rebuild on next access
        // Sprint 11 Phase B: drop bundle registrations contributed by this project.
        workspaceManager.unregisterBundlesForProject(removed.javaProject().getProject());
        log.info("Removed project '{}' from workspace", projectKey);
        if (projectKey.equals(defaultProjectKey)) {
            // Pick a new default deterministically: the first remaining key
            // by natural string order, or null if no projects remain.
            defaultProjectKey = projectsByKey.keySet().stream().sorted().findFirst().orElse(null);
            if (defaultProjectKey != null) {
                applyToLegacyFields(projectsByKey.get(defaultProjectKey));
            } else {
                clearLegacyFields();
            }
        }
        return true;
    }

    /**
     * Shared loader: detect, configure, register. Does not clear or
     * change the default key — callers decide that.
     */
    private LoadedProject loadInternal(Path path) throws CoreException {
        Path absRoot = path.toAbsolutePath().normalize();
        IPathUtils utils = new PathUtilsImpl(absRoot);

        workspaceManager.initialize();

        ProjectImporter.BuildSystem detected = projectImporter.detectBuildSystem(absRoot);
        int fileCount = projectImporter.countSourceFiles(absRoot);
        List<String> pkgList = projectImporter.findPackages(absRoot);

        log.info("Detected {} build system, {} source files, {} packages",
            detected, fileCount, pkgList.size());

        // Derive a project key, disambiguate if it collides with an
        // existing loaded project.
        String key = ProjectKeys.derive(absRoot);
        if (projectsByKey.containsKey(key)) {
            key = ProjectKeys.disambiguate(key, absRoot);
        }

        String projectName = "goja-" + absRoot.getFileName();
        IProject project = workspaceManager.createLinkedProject(projectName, absRoot);

        // Sprint 11 Phase B: register the bundle in the workspace bundle pool
        // BEFORE configureJavaProject runs, so this project's own classpath
        // resolution (and subsequent siblings') can honour Require-Bundle.
        ProjectImporter.readManifestSymbolicName(absRoot)
            .ifPresent(symbolicName -> workspaceManager.registerBundle(symbolicName, project));

        IJavaProject jp = projectImporter.configureJavaProject(project, absRoot, workspaceManager);
        SearchService search = new SearchService(jp);

        LoadedProject loaded = new LoadedProject(
            key, absRoot, jp, search, utils, Instant.now(),
            fileCount, pkgList.size(), pkgList, detected
        );
        projectsByKey.put(key, loaded);
        return loaded;
    }

    /** Mirror a LoadedProject into the legacy single-project fields. */
    private void applyToLegacyFields(LoadedProject loaded) {
        this.projectRoot = loaded.projectRoot();
        this.pathUtils = loaded.pathUtils();
        this.javaProject = loaded.javaProject();
        this.searchService = loaded.searchService();
        this.loadedAt = loaded.loadedAt();
        this.sourceFileCount = loaded.sourceFileCount();
        this.packageCount = loaded.packageCount();
        this.packages = loaded.packages();
        this.buildSystem = loaded.buildSystem();
    }

    /** Reset the legacy single-project fields when no default exists. */
    private void clearLegacyFields() {
        this.projectRoot = null;
        this.pathUtils = null;
        this.javaProject = null;
        this.searchService = null;
        this.loadedAt = null;
        this.sourceFileCount = 0;
        this.packageCount = 0;
        this.packages = null;
        this.buildSystem = null;
    }

    // ========== Sprint 10 multi-project getters ==========

    @Override
    public Optional<String> defaultProjectKey() {
        return Optional.ofNullable(defaultProjectKey);
    }

    @Override
    public Optional<LoadedProject> getProject(String projectKey) {
        return Optional.ofNullable(projectsByKey.get(projectKey));
    }

    @Override
    public Collection<String> projectKeys() {
        return Collections.unmodifiableSet(projectsByKey.keySet());
    }

    @Override
    public Collection<LoadedProject> allProjects() {
        return Collections.unmodifiableCollection(projectsByKey.values());
    }

    @Override
    public IPathUtils getPathUtils() {
        return pathUtils;
    }

    @Override
    public Path getProjectRoot() {
        return projectRoot;
    }

    @Override
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    @Override
    public <T> T executeWithTimeout(Callable<T> operation, String operationName) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<T> future = executor.submit(operation);

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException(
                operationName + " timed out after " + timeoutSeconds + " seconds"
            );
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(operationName + " failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(operationName + " was interrupted");
        } finally {
            executor.shutdownNow();
        }
    }

    // Getters for project info (used by LoadProjectTool response)

    @Override
    public IJavaProject getJavaProject() {
        return javaProject;
    }

    @Override
    public SearchService getSearchService() {
        // Sprint 10: prefer the workspace-scoped SearchService so queries
        // span all loaded projects. Falls back to the legacy per-project
        // service when no projects are loaded yet (e.g., between the
        // service constructor and the first loadProject() / addProject()).
        SearchService workspace = getOrBuildWorkspaceSearchService();
        return workspace != null ? workspace : searchService;
    }

    /**
     * Lazily build a SearchService whose scope spans every currently loaded
     * project. Recomputed only when the cache is null (set by addProject /
     * removeProject / loadProject mutations).
     */
    private SearchService getOrBuildWorkspaceSearchService() {
        SearchService cached = workspaceSearchService;
        if (cached != null || projectsByKey.isEmpty()) {
            return cached;
        }
        synchronized (this) {
            if (workspaceSearchService == null && !projectsByKey.isEmpty()) {
                IJavaProject[] all = projectsByKey.values().stream()
                    .map(LoadedProject::javaProject)
                    .toArray(IJavaProject[]::new);
                workspaceSearchService = new SearchService(all);
            }
        }
        return workspaceSearchService;
    }

    public Instant getLoadedAt() {
        return loadedAt;
    }

    public int getSourceFileCount() {
        return sourceFileCount;
    }

    public int getPackageCount() {
        return packageCount;
    }

    public List<String> getPackages() {
        return packages;
    }

    public ProjectImporter.BuildSystem getBuildSystem() {
        return buildSystem;
    }

    public int getClasspathEntryCount() {
        try {
            return javaProject != null ? javaProject.getRawClasspath().length : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ========== New Interface Methods for Tools ==========

    @Override
    public ICompilationUnit getCompilationUnit(Path filePath) {
        // Try the default project first (fast path, also the only path
        // pre-Sprint 10), then fan out across the rest of the workspace
        // so a tool call without projectKey can resolve files in any
        // loaded project — required for the single-workspace mode where
        // one goja process holds N projects.
        if (javaProject != null) {
            ICompilationUnit cu = lookupCompilationUnit(javaProject, filePath);
            if (cu != null) return cu;
        }
        for (LoadedProject other : projectsByKey.values()) {
            if (other.javaProject() == javaProject) continue; // already tried
            ICompilationUnit cu = lookupCompilationUnit(other.javaProject(), filePath);
            if (cu != null) return cu;
        }
        log.debug("Compilation unit not found for: {}", filePath);
        return null;
    }

    private static ICompilationUnit lookupCompilationUnit(IJavaProject jp, Path filePath) {
        if (jp == null) return null;
        try {
            String pathStr = filePath.toString().replace('\\', '/');
            String classPath = pathStr;
            String[] sourcePrefixes = {"src/main/java/", "src/test/java/", "src/main/kotlin/", "src/test/kotlin/", "src/"};
            for (String prefix : sourcePrefixes) {
                if (pathStr.contains(prefix)) {
                    int idx = pathStr.indexOf(prefix);
                    classPath = pathStr.substring(idx + prefix.length());
                    break;
                }
            }
            String withoutExt = classPath.replace(".java", "");
            String qualifiedName = withoutExt.replace('/', '.');
            IType type = jp.findType(qualifiedName);
            if (type != null) return type.getCompilationUnit();

            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    int lastSlash = classPath.lastIndexOf('/');
                    String packageName = lastSlash > 0 ? classPath.substring(0, lastSlash).replace('/', '.') : "";
                    String className = lastSlash > 0 ? classPath.substring(lastSlash + 1) : classPath;
                    IPackageFragment pkg = root.getPackageFragment(packageName);
                    if (pkg != null && pkg.exists()) {
                        ICompilationUnit cu = pkg.getCompilationUnit(className);
                        if (cu != null && cu.exists()) return cu;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public IJavaElement getElementAtPosition(Path filePath, int line, int column) {
        ICompilationUnit cu = getCompilationUnit(filePath);
        if (cu == null) {
            log.debug("Compilation unit not found for: {}", filePath);
            return null;
        }

        try {
            // Ensure the compilation unit is open and reconciled for codeSelect to work
            if (!cu.isOpen()) {
                cu.open(null);
            }

            // Reconcile to ensure the AST is up to date
            cu.reconcile(ICompilationUnit.NO_AST, false, null, null);

            int offset = getOffset(cu, line, column);
            log.debug("Looking for element at {}:{}:{} (offset {})", filePath, line, column, offset);

            IJavaElement[] elements = cu.codeSelect(offset, 0);
            if (elements.length > 0) {
                log.debug("Found element: {} ({})", elements[0].getElementName(), elements[0].getClass().getSimpleName());
                return elements[0];
            }

            // Fallback: try to find element at offset using getElementAt
            IJavaElement element = cu.getElementAt(offset);
            if (element != null) {
                log.debug("Found element via getElementAt: {} ({})", element.getElementName(), element.getClass().getSimpleName());
                return element;
            }

            log.debug("No element found at position");
            return null;
        } catch (JavaModelException e) {
            log.warn("Error getting element at position: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public IType getTypeAtPosition(Path filePath, int line, int column) {
        IJavaElement element = getElementAtPosition(filePath, line, column);
        if (element instanceof IType type) {
            return type;
        }
        // If the element is within a type, try to get the enclosing type
        if (element != null) {
            IType enclosingType = (IType) element.getAncestor(IJavaElement.TYPE);
            return enclosingType;
        }
        return null;
    }

    @Override
    public IType findType(String typeName) {
        if (typeName == null || typeName.isBlank()) return null;
        // Default project first, then the rest of the workspace.
        if (javaProject != null) {
            IType type = lookupType(javaProject, typeName);
            if (type != null) return type;
        }
        for (LoadedProject other : projectsByKey.values()) {
            if (other.javaProject() == javaProject) continue;
            IType type = lookupType(other.javaProject(), typeName);
            if (type != null) return type;
        }
        return null;
    }

    private static IType lookupType(IJavaProject jp, String typeName) {
        if (jp == null) return null;
        try {
            IType type = jp.findType(typeName);
            if (type != null) return type;
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    for (IJavaElement child : root.getChildren()) {
                        if (child instanceof IPackageFragment pkg) {
                            for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                                for (IType t : cu.getTypes()) {
                                    if (t.getElementName().equals(typeName)) return t;
                                }
                            }
                        }
                    }
                }
            }
            return null;
        } catch (JavaModelException e) {
            return null;
        }
    }

    @Override
    public String getContextLine(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return "";
            }

            // Find line start
            int lineStart = offset;
            while (lineStart > 0 && source.charAt(lineStart - 1) != '\n') {
                lineStart--;
            }

            // Find line end
            int lineEnd = offset;
            while (lineEnd < source.length() && source.charAt(lineEnd) != '\n' && source.charAt(lineEnd) != '\r') {
                lineEnd++;
            }

            String line = source.substring(lineStart, Math.min(lineEnd, lineStart + 200));
            return line.trim();
        } catch (JavaModelException e) {
            log.trace("Error getting context line: {}", e.getMessage());
            return "";
        }
    }

    @Override
    public int getOffset(ICompilationUnit cu, int line, int column) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return 0;
            }

            int offset = 0;
            int currentLine = 0;

            // Navigate to the correct line
            while (currentLine < line && offset < source.length()) {
                if (source.charAt(offset) == '\n') {
                    currentLine++;
                }
                offset++;
            }

            // Add column offset
            return Math.min(offset + column, source.length());
        } catch (JavaModelException e) {
            log.warn("Error calculating offset: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int getLineNumber(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return 0;
            }

            int line = 0;
            for (int i = 0; i < offset && i < source.length(); i++) {
                if (source.charAt(i) == '\n') {
                    line++;
                }
            }
            return line;
        } catch (JavaModelException e) {
            log.warn("Error calculating line number: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public int getColumnNumber(ICompilationUnit cu, int offset) {
        try {
            String source = cu.getSource();
            if (source == null) {
                return 0;
            }

            int column = 0;
            for (int i = offset - 1; i >= 0; i--) {
                if (source.charAt(i) == '\n') {
                    break;
                }
                column++;
            }
            return column;
        } catch (JavaModelException e) {
            log.warn("Error calculating column number: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public List<Path> getAllJavaFiles() {
        // Aggregate across every loaded project so workspace-wide listings
        // (find_unused_code etc.) see all .java files in single-workspace
        // mode. Falls back to the legacy javaProject when projectsByKey is
        // empty (transient state during construction).
        List<Path> files = new ArrayList<>();
        Collection<LoadedProject> loaded = projectsByKey.values();
        if (loaded.isEmpty()) {
            if (javaProject != null) {
                collectFilesFrom(javaProject, files);
            }
            return files;
        }
        for (LoadedProject p : loaded) {
            collectFilesFrom(p.javaProject(), files);
        }
        return files;
    }

    private void collectFilesFrom(IJavaProject jp, List<Path> files) {
        if (jp == null) return;
        try {
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    collectJavaFiles(root, files);
                }
            }
        } catch (JavaModelException e) {
            log.warn("Error getting Java files for {}: {}", jp.getElementName(), e.getMessage());
        }
    }

    private void collectJavaFiles(IPackageFragmentRoot root, List<Path> files) throws JavaModelException {
        for (IJavaElement child : root.getChildren()) {
            if (child instanceof IPackageFragment pkg) {
                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    IResource resource = cu.getResource();
                    if (resource != null) {
                        IPath location = resource.getLocation();
                        if (location != null) {
                            files.add(Path.of(location.toOSString()));
                        }
                    }
                }
            }
        }
    }
}
