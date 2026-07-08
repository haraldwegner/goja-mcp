package org.goja.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.goja.core.IJdtService;
import org.goja.core.JdtServiceImpl;
import org.goja.core.workspace.WorkspaceFileWatcher;
import org.goja.mcp.knowledge.ExperienceAdvisor;
import org.goja.mcp.knowledge.ExperienceStore;
import org.goja.mcp.knowledge.H2ExperienceStore;
import org.goja.mcp.protocol.McpProtocolHandler;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.AnalyzeTool;
import org.goja.mcp.tools.ApplyCleanupTool;
import org.goja.mcp.tools.ApplyNullAnnotationsTool;
import org.goja.mcp.tools.ChangeMethodSignatureTool;
import org.goja.mcp.tools.CompileWorkspaceTool;
import org.goja.mcp.tools.ConvertAnonymousToLambdaTool;
import org.goja.mcp.tools.EncapsulateFieldTool;
import org.goja.mcp.tools.ExperienceTool;
import org.goja.mcp.tools.ExtractTool;
import org.goja.mcp.tools.FindDuplicateCodeTool;
import org.goja.mcp.tools.FindFieldWritesTool;
import org.goja.mcp.tools.FindModernizationTool;
import org.goja.mcp.tools.FindPatternUsagesTool;
import org.goja.mcp.tools.FindQualityIssueTool;
import org.goja.mcp.tools.FindRefsTool;
import org.goja.mcp.tools.FindStringLiteralsTool;
import org.goja.mcp.tools.FindTestsTool;
import org.goja.mcp.tools.GetAtPositionTool;
import org.goja.mcp.tools.GetCallHierarchyTool;
import org.goja.mcp.tools.GetDiagnosticsTool;
import org.goja.mcp.tools.GoToDefinitionTool;
import org.goja.mcp.tools.HealthCheckTool;
import org.goja.mcp.tools.InlineTool;
import org.goja.mcp.tools.InspectTool;
import org.goja.mcp.tools.LoadProjectTool;
import org.goja.mcp.tools.MoveInHierarchyTool;
import org.goja.mcp.tools.MoveMethodTool;
import org.goja.mcp.tools.MoveTool;
import org.goja.mcp.tools.OrganizeImportsTool;
import org.goja.mcp.tools.ProjectTool;
import org.goja.mcp.tools.QuickFixTool;
import org.goja.mcp.tools.RefactorToPatternTool;
import org.goja.mcp.tools.RefactoringTool;
import org.goja.mcp.tools.RefreshWorkspaceTool;
import org.goja.mcp.tools.RenameSymbolTool;
import org.goja.mcp.tools.ReplaceDuplicatesTool;
import org.goja.mcp.tools.RunTestsTool;
import org.goja.mcp.tools.SearchSymbolsTool;
import org.goja.mcp.tools.ToolRegistry;
import org.goja.mcp.tools.ValidateSyntaxTool;
import org.goja.mcp.tools.build.DependencyTool;
import org.goja.mcp.tools.codegen.GenerateTool;
import org.goja.mcp.tools.workflow.FormatTool;
import org.goja.mcp.tools.workflow.OptimizeImportsWorkspaceTool;
import org.goja.mcp.transport.HttpTransport;
import org.goja.mcp.transport.StdioTransport;
import org.goja.mcp.transport.TokenGenerator;
import org.goja.mcp.transport.Transport;
import org.goja.mcp.transport.TransportConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi application entry point for GOJA MCP server.
 * Reads JSON-RPC messages from stdin and writes responses to stdout.
 *
 * <p>Session isolation is handled by the GojaLauncher wrapper which
 * injects a unique UUID into the workspace path before OSGi starts.
 */
public class GojaApplication implements IApplication {

    private static final Logger log = LoggerFactory.getLogger(GojaApplication.class);

    private volatile IJdtService jdtService;
    private volatile ProjectLoadingState loadingState = ProjectLoadingState.NOT_LOADED;
    private volatile String loadingError = null;
    // Sprint 14b: session-scoped cache backing the refactoring apply/undo
    // contract (staged Changes + undo handles, TTL + LRU bounded).
    private final RefactoringChangeCache refactoringChangeCache = new RefactoringChangeCache();
    private ToolRegistry toolRegistry;
    // Sprint 21 (v2.0): the local experience/knowledge store — workspace-scoped H2,
    // opened at start()/closed at stop(). Backs the ExperienceAdvisor + store tools.
    private ExperienceStore experienceStore;
    /** Sprint 21b (item D): held for the automatic post-project-load refresh. */
    private ExperienceTool experienceTool;
    private McpProtocolHandler protocolHandler;
    private volatile WorkspaceFileWatcher workspaceWatcher;
    private volatile Transport activeTransport;

    // Static instance for loading state access by tools
    private static volatile GojaApplication instance;

    /**
     * Get the current project loading state.
     * Used by tools to provide appropriate feedback when project is loading.
     */
    public static ProjectLoadingState getLoadingState() {
        GojaApplication app = instance;
        return app != null ? app.loadingState : ProjectLoadingState.NOT_LOADED;
    }

    /**
     * Get the loading error message if loading failed.
     */
    public static String getLoadingError() {
        GojaApplication app = instance;
        return app != null ? app.loadingError : null;
    }

    @Override
    public Object start(IApplicationContext context) throws Exception {
        log.info("GOJA MCP Server starting...");
        instance = this;

        // Sprint 14a Stage 2: parse transport CLI flags from the application
        // argv. HTTP is the default; -transport stdio opts back to the
        // pre-Sprint-14a behaviour. Unknown flags (Eclipse -data / -clean /
        // etc.) pass through. The actual HttpTransport impl lands in Stage 3.
        String[] cliArgs = (String[]) context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        TransportConfig transportConfig = TransportConfig.fromArgs(cliArgs);
        log.info("Transport selection: {}", transportConfig);

        // Initialize tool registry and register tools
        toolRegistry = new ToolRegistry();
        // Sprint 21 (v2.0): open the workspace-scoped experience store before registering
        // tools, so store-backed tools + the ExperienceAdvisor can be wired with it.
        // Sprint 21a (item B): a store that cannot open read-write (e.g. written by a NEWER
        // resident) must not kill the server — degrade to in-memory and say so.
        experienceStore = openExperienceStore();
        registerTools();

        // Initialize protocol handler
        protocolHandler = new McpProtocolHandler(toolRegistry);

        log.info("Registered {} tools", toolRegistry.getToolCount());

        // Sprint 10 v1.4.0: prefer workspace.json in the JDT data dir as the
        // source of truth for what to load. The manager (goja-studio)
        // writes this file. Fall back to the legacy JAVA_PROJECT_PATH env
        // var when workspace.json is absent (back-compat for direct manual
        // launches without the manager).
        CompletableFuture.runAsync(this::autoLoadProjects);

        // Run the main message loop (starts immediately, doesn't wait for project load)
        runMessageLoop(transportConfig);

        log.info("GOJA MCP Server stopped");
        return IApplication.EXIT_OK;
    }

    /**
     * Sprint 10 v1.4.0: load projects from {@code workspace.json} in the
     * Eclipse {@code -data} directory if present, otherwise fall back to
     * {@code JAVA_PROJECT_PATH}. This runs asynchronously so the MCP server
     * can respond to {@code initialize} immediately while loading proceeds.
     *
     * v1.7.1 (bug #5): also check the parent of the OSGi data dir. The
     * GojaLauncher wrapper injects a UUID subdir into {@code -data} for
     * session isolation, so OSGi's {@code osgi.instance.area} ends up at
     * {@code <workspace>/<uuid>/} while the manager writes workspace.json at
     * {@code <workspace>/workspace.json}. Without the parent-fallback every
     * non-manager-spawned JVM (Cursor, Claude Code, etc.) saw an empty
     * project list.
     */
    private void autoLoadProjects() {
        Path dataDir = resolveDataDir();
        if (dataDir != null) {
            Path workspaceJson = findWorkspaceJson(dataDir);
            if (workspaceJson != null) {
                loadFromWorkspaceJson(workspaceJson.getParent());
                refreshExperienceAfterProjectLoad();
                return;
            }
        }
        // Fall back to single-project env-var path.
        autoLoadProjectFromEnv();
        refreshExperienceAfterProjectLoad();
    }

    /**
     * Sprint 21b (item D): the store-open refresh would be useless — projects load
     * asynchronously AFTER the store opens, so every pointer would judge as "unknown".
     * Run the automatic staleness pass here instead, once projects are in. Already on
     * the async auto-load thread (zero startup cost); autoRefresh never throws.
     *
     * <p>Sprint 21d (item C, declared boundary): this path runs OUTSIDE the dispatch
     * disk-sync guard on purpose — it fires immediately after project load, when the
     * JDT model was just read from this same disk state (fresh by construction). Every
     * OTHER refresh/recall runs under {@code ToolRegistry.callTool}'s guard.</p>
     *
     * <p>Sprint 21e (item A): the same pass now also BACKFILLS symbol auto-anchors
     * (memory loads before projects — resolution needs the loaded model), and it fires
     * on BOTH post-load paths: this startup auto-load AND, via
     * {@code ToolRegistry.setProjectsMutatedHook}, every successful tool-initiated
     * {@code load_project} / {@code project(action=add|remove)} — add makes new types
     * anchorable, remove lets refresh CLEAR their auto-anchors. Same
     * fresh-by-construction footing: it runs immediately after the mutation, inside
     * the guarded call.</p>
     */
    private void refreshExperienceAfterProjectLoad() {
        if (experienceTool != null) {
            log.info("Experience auto-refresh after project load: {}", experienceTool.autoRefresh());
        }
    }

    /**
     * Look for {@code workspace.json} starting at the OSGi data dir, then
     * walking up one level to handle the GojaLauncher session-isolation
     * subdir. Returns the path of the file if found, else {@code null}.
     *
     * <p>Public + static so unit tests in the {@code org.goja.mcp.tests}
     * bundle can exercise the walk-up logic without booting OSGi.
     */
    public static Path findWorkspaceJson(Path dataDir) {
        if (dataDir == null) {
            return null;
        }
        Path candidate = dataDir.resolve("workspace.json");
        if (Files.isRegularFile(candidate)) {
            return candidate;
        }
        Path parent = dataDir.getParent();
        if (parent != null) {
            candidate = parent.resolve("workspace.json");
            if (Files.isRegularFile(candidate)) {
                log.info("Found workspace.json in parent of OSGi data dir ({}), " +
                    "treating that as the workspace root (GojaLauncher session-isolation path)",
                    parent);
                return candidate;
            }
        }
        return null;
    }

    private Path resolveDataDir() {
        // Use the OSGi-defined system property rather than Platform.getInstanceLocation().getURL()
        // — the latter is a Tycho-restricted API. osgi.instance.area is set by the framework to
        // the URL of the -data dir and is the public way to read it.
        try {
            String area = System.getProperty("osgi.instance.area");
            if (area == null || area.isBlank()) return null;
            return Path.of(java.net.URI.create(area));
        } catch (Exception e) {
            log.warn("Failed to resolve Eclipse instance area: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Sprint 21a (items A+B+H): open the experience store, stamp provenance, recover
     * orphaned session stores, and degrade instead of dying — a store that refuses to
     * open (e.g. schema written by a NEWER resident) falls back to in-memory with a loud
     * log, so the server still starts and recall answers with absence rather than errors.
     *
     * <p>Mode ({@code -Dgoja.experience.store}): {@code shared} (DEFAULT — the user-level
     * store, knowledge spans workspaces) · {@code workspace} (per-workspace file at the
     * STABLE workspace root — never the launcher's session dir, which is deleted on clean
     * shutdown) · {@code memory} · any other value = an explicit store directory.</p>
     */
    private ExperienceStore openExperienceStore() {
        Path dataDir = resolveDataDir();
        Path workspaceRoot = resolveWorkspaceRoot(dataDir);
        String mode = System.getProperty("goja.experience.store", "shared").trim();
        try {
            H2ExperienceStore store = switch (mode) {
                case "memory" -> H2ExperienceStore.openMemory();
                case "workspace" -> H2ExperienceStore.open(workspaceRoot);
                case "", "shared" -> H2ExperienceStore.openShared();
                default -> H2ExperienceStore.openAt(Path.of(mode));
            };
            Path workspaceJson = workspaceRoot != null
                    && Files.isRegularFile(workspaceRoot.resolve("workspace.json"))
                ? workspaceRoot.resolve("workspace.json")
                : findWorkspaceJson(dataDir);
            if (workspaceJson != null) {
                String[] prov = readProvenance(workspaceJson);
                store.setProvenance(prov[0], prov[1]);
            }
            // One-time sweep: pre-21a stores stranded in session-isolation dirs (item A).
            store.recoverOrphans(workspaceRoot);
            return store;
        } catch (Exception e) {
            log.error("Experience store unavailable ({}); continuing with a NON-PERSISTENT "
                + "in-memory store", e.getMessage());
            return H2ExperienceStore.openMemory();
        }
    }

    /**
     * The STABLE workspace root: the launcher-published original {@code -data}
     * ({@code goja.workspace.root}) wins; else walk up from the OSGi data dir to where
     * {@code workspace.json} lives; else the data dir itself. Never the session subdir
     * when the launcher is in play.
     */
    static Path resolveWorkspaceRoot(Path dataDir) {
        String prop = System.getProperty("goja.workspace.root");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        if (dataDir == null) {
            return null;
        }
        Path workspaceJson = findWorkspaceJson(dataDir);
        return workspaceJson != null ? workspaceJson.getParent() : dataDir;
    }

    // --- Sprint 21a (item C): default memory roots for load/reseed ------------------------

    /**
     * The default roots the no-path {@code experience(kind=load|reseed)} seeds from:
     * extra roots from {@code -Dgoja.memory.roots} (path-separator list — the studio
     * config channel), the layered {@code CLAUDE.md} set (global {@code ~/.claude} +
     * each workspace project dir and its ancestors up to {@code $HOME}), and the Claude
     * per-project memory dirs ({@code ~/.claude/projects/<sanitized-path>/memory}).
     */
    private java.util.List<Path> defaultMemoryRoots() {
        return defaultMemoryRoots(
            Path.of(System.getProperty("user.home")),
            workspaceProjectDirs(),
            System.getProperty("goja.memory.roots"));
    }

    /** Package-private + static for tests (no OSGi). Only existing paths are returned. */
    static java.util.List<Path> defaultMemoryRoots(Path home, java.util.List<Path> projectDirs,
            String extraRoots) {
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        if (extraRoots != null && !extraRoots.isBlank()) {
            for (String s : extraRoots.split(java.io.File.pathSeparator)) {
                if (!s.isBlank()) {
                    addIfExists(roots, Path.of(s.strip()));
                }
            }
        }
        addIfExists(roots, home.resolve(".claude").resolve("CLAUDE.md"));
        for (Path proj : projectDirs) {
            Path d = proj.toAbsolutePath().normalize();
            while (d != null) {
                addIfExists(roots, d.resolve("CLAUDE.md"));
                if (d.equals(home) || !d.startsWith(home)) {
                    break;                     // layered up to $HOME; foreign roots: dir only
                }
                d = d.getParent();
            }
            addIfExists(roots, home.resolve(".claude").resolve("projects")
                .resolve(sanitizeProjectDir(proj)).resolve("memory"));
            // Sprint 21b (item C2): the other agents' per-project conventions.
            addIfExists(roots, proj.resolve(".cursor").resolve("rules"));
            addIfExists(roots, proj.resolve(".cursorrules"));
            addIfExists(roots, proj.resolve("AGENTS.md"));
            addIfExists(roots, proj.resolve(".github").resolve("copilot-instructions.md"));
            addIfExists(roots, proj.resolve(".windsurfrules"));
        }
        // Sprint 21b (item C2): the store is user-level, so discovery is too — EVERY
        // Claude memory dir, not just this workspace's projects ("autofind").
        Path claudeProjects = home.resolve(".claude").resolve("projects");
        if (Files.isDirectory(claudeProjects)) {
            try (var dirs = Files.list(claudeProjects)) {
                dirs.sorted().forEach(p -> addIfExists(roots, p.resolve("memory")));
            } catch (java.io.IOException e) {
                log.warn("Cannot list {}: {}", claudeProjects, e.getMessage());
            }
        }
        return java.util.List.copyOf(roots);
    }

    /** The Claude Code project-dir convention: the absolute path with separators as dashes. */
    static String sanitizeProjectDir(Path proj) {
        return proj.toAbsolutePath().normalize().toString()
            .replace(java.io.File.separatorChar, '-').replace('/', '-');
    }

    private static void addIfExists(java.util.Set<Path> set, Path p) {
        if (Files.exists(p)) {
            set.add(p);
        }
    }

    private java.util.List<Path> workspaceProjectDirs() {
        Path dataDir = resolveDataDir();
        Path root = resolveWorkspaceRoot(dataDir);
        Path workspaceJson = root != null && Files.isRegularFile(root.resolve("workspace.json"))
            ? root.resolve("workspace.json")
            : findWorkspaceJson(dataDir);
        return workspaceJson == null ? java.util.List.of() : readProjects(workspaceJson);
    }

    /** All project paths from {@code workspace.json} (empty on any parse problem). */
    static java.util.List<Path> readProjects(Path workspaceJson) {
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(workspaceJson));
            java.util.List<Path> out = new java.util.ArrayList<>();
            JsonNode projects = root.path("projects");
            if (projects.isArray()) {
                for (JsonNode p : projects) {
                    String s = p.asText(null);
                    if (s != null && !s.isBlank()) {
                        out.add(Path.of(s));
                    }
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Cannot read projects from {}: {}", workspaceJson, e.getMessage());
            return java.util.List.of();
        }
    }

    /**
     * Parse {@code workspace.json} into provenance facets: {@code [workspaceId, projectId]}
     * (the manager's workspace {@code name} + first project path; either may be null).
     * Package-private + static so the tests bundle can exercise it without booting OSGi.
     */
    static String[] readProvenance(Path workspaceJson) {
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(workspaceJson));
            String name = root.path("name").asText(null);
            if (name == null || name.isBlank()) {
                Path parent = workspaceJson.getParent();
                name = parent == null ? null : parent.getFileName().toString();
            }
            String project = null;
            JsonNode projects = root.path("projects");
            if (projects.isArray() && projects.size() > 0) {
                project = projects.get(0).asText(null);
            }
            return new String[] {name, project};
        } catch (Exception e) {
            log.warn("Cannot read provenance from {}: {}", workspaceJson, e.getMessage());
            return new String[] {null, null};
        }
    }

    private void loadFromWorkspaceJson(Path dataDir) {
        log.info("Loading workspace from {}", dataDir.resolve("workspace.json"));
        loadingState = ProjectLoadingState.LOADING;
        try {
            JdtServiceImpl service = new JdtServiceImpl();
            WorkspaceFileWatcher watcher = new WorkspaceFileWatcher(dataDir, service);
            watcher.start();  // synchronous initial load + arm watcher thread
            this.jdtService = service;
            this.workspaceWatcher = watcher;
            loadingState = ProjectLoadingState.LOADED;
            log.info("Workspace loaded; watcher armed for live updates");
        } catch (Exception e) {
            log.error("Failed to load workspace from workspace.json: {}", e.getMessage(), e);
            loadingState = ProjectLoadingState.FAILED;
            loadingError = e.getMessage();
        }
    }

    /**
     * Auto-load project from JAVA_PROJECT_PATH environment variable.
     * This runs asynchronously to allow the MCP server to respond immediately.
     * The loading state is tracked and can be queried via health_check.
     */
    private void autoLoadProjectFromEnv() {
        String projectPath = System.getenv("JAVA_PROJECT_PATH");
        if (projectPath == null || projectPath.isBlank()) {
            log.debug("JAVA_PROJECT_PATH not set, waiting for load_project call");
            // State remains NOT_LOADED
            return;
        }

        Path path = Path.of(projectPath).toAbsolutePath().normalize();
        if (!Files.exists(path)) {
            log.warn("JAVA_PROJECT_PATH points to non-existent path: {}", projectPath);
            loadingState = ProjectLoadingState.FAILED;
            loadingError = "JAVA_PROJECT_PATH points to non-existent path: " + projectPath;
            return;
        }

        if (!Files.isDirectory(path)) {
            log.warn("JAVA_PROJECT_PATH is not a directory: {}", projectPath);
            loadingState = ProjectLoadingState.FAILED;
            loadingError = "JAVA_PROJECT_PATH is not a directory: " + projectPath;
            return;
        }

        log.info("Auto-loading project from JAVA_PROJECT_PATH: {}", path);
        loadingState = ProjectLoadingState.LOADING;

        try {
            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(path);
            this.jdtService = service;
            loadingState = ProjectLoadingState.LOADED;
            log.info("Project auto-loaded successfully: {} files, {} packages",
                service.getSourceFileCount(), service.getPackageCount());
        } catch (Exception e) {
            log.error("Failed to auto-load project from JAVA_PROJECT_PATH: {}", e.getMessage(), e);
            loadingState = ProjectLoadingState.FAILED;
            loadingError = e.getMessage();
        }
    }

    private void registerTools() {
        // Sprint 21d: strict disk sync — every tool call reconciles external edits
        // before executing; the unchanged-tree fast path is the only skip.
        toolRegistry.setDiskSync(
            new org.goja.core.workspace.StrictDiskSync(() -> jdtService));

        // Sprint 21e (item A): tool-initiated project mutations refresh + backfill the
        // store's symbol anchors too (the startup auto-load path calls this directly).
        toolRegistry.setProjectsMutatedHook(this::refreshExperienceAfterProjectLoad);

        // Register HealthCheckTool with suppliers for project status, tool
        // count, loading state, and (Sprint 10) the multi-project workspace
        // summary.
        toolRegistry.register(new HealthCheckTool(
            () -> jdtService != null,
            () -> toolRegistry.getToolCount(),
            () -> loadingState,
            () -> loadingError,
            () -> jdtService
        ));

        // Register LoadProjectTool — supplies the existing service so the
        // tool can reuse it (clear-and-load on top), and installs a fresh
        // service on first call. This is what enables add_project /
        // remove_project / list_projects to operate on the same workspace.
        toolRegistry.register(new LoadProjectTool(
            () -> jdtService,
            service -> this.jdtService = service
        ));

        // Sprint 16b/A (v1.1.1): project(action) collapses list/add/remove_project
        // (load_project stays separate — it installs the workspace service).
        toolRegistry.register(new ProjectTool(() -> jdtService));

        // Batch 1: Core Navigation Tools
        toolRegistry.register(new SearchSymbolsTool(() -> jdtService));
        toolRegistry.register(new GoToDefinitionTool(() -> jdtService));
        // Sprint 16b/A (v1.1.1): find_references(kind) collapses references/implementations/method_references.
        toolRegistry.register(new FindRefsTool(() -> jdtService));

        // Sprint 16b/A (v1.1.1): inspect(kind) collapses the 9 read-only structural
        // get_* tools; analyze(kind) collapses the 9 analyze_* tools. Narrow classes
        // stay in-package as delegates; they're no longer registered.
        toolRegistry.register(new InspectTool(() -> jdtService));
        toolRegistry.register(new AnalyzeTool(() -> jdtService));

        // Sprint 16b/A: get_at_position collapses the 9 read-only position-lookup tools.
        toolRegistry.register(new GetAtPositionTool(() -> jdtService));

        // Batch 5: Diagnostics & Call Hierarchy
        toolRegistry.register(new GetDiagnosticsTool(() -> jdtService));
        toolRegistry.register(new ValidateSyntaxTool(() -> jdtService));
        // Sprint 16b/A: get_call_hierarchy collapses incoming + outgoing by direction.
        toolRegistry.register(new GetCallHierarchyTool(() -> jdtService));

        // Analysis tools
        toolRegistry.register(new FindFieldWritesTool(() -> jdtService));
        toolRegistry.register(new FindTestsTool(() -> jdtService));

        // Refactoring tools
        toolRegistry.register(new RenameSymbolTool(() -> jdtService, refactoringChangeCache));
        toolRegistry.register(new OrganizeImportsTool(() -> jdtService, refactoringChangeCache));

        // Sprint 16b/A: apply-tool category front doors. Collapse 16 narrow
        // refactor/codegen tools into 5 parametric verbs; the narrow classes
        // remain as the delegated implementations, no longer registered.
        toolRegistry.register(new ExtractTool(() -> jdtService, refactoringChangeCache));
        toolRegistry.register(new InlineTool(() -> jdtService, refactoringChangeCache));
        toolRegistry.register(new MoveTool(() -> jdtService, refactoringChangeCache));
        toolRegistry.register(new MoveInHierarchyTool(() -> jdtService, refactoringChangeCache));
        // Sprint 22a P1-a.1: the composition-axis primitive — move an instance
        // method onto the type of one of its parameters/fields (net-new front door).
        toolRegistry.register(new MoveMethodTool(() -> jdtService, refactoringChangeCache));
        toolRegistry.register(new GenerateTool(() -> jdtService, refactoringChangeCache));

        // Sprint 19 (Kerievsky): pattern-targeted refactorings behind one parametric
        // front door; the per-pattern delegates are not registered standalone.
        toolRegistry.register(new RefactorToPatternTool(() -> jdtService, refactoringChangeCache));

        // Fine-grained reference search (JDT-unique capabilities).
        // Sprint 11 Phase D: 13 narrow find_* tools collapsed to 2 parametric ones.
        // - find_pattern_usages (annotation, instantiation, type_argument, cast, instanceof)
        // - find_quality_issue  (naming, bugs, unused, large_classes, circular_deps,
        //                        reflection, throws, catches)
        // The narrow tool classes remain in the package as the analysis
        // implementations the parametric tools delegate to; they're no
        // longer registered as user-facing MCP tools.
        toolRegistry.register(new FindPatternUsagesTool(() -> jdtService));
        toolRegistry.register(new FindQualityIssueTool(() -> jdtService));
        // Sprint 22a P2-a: literal-content search (net-new front door #2).
        toolRegistry.register(new FindStringLiteralsTool(() -> jdtService));
        // find_method_references now via find_references(kind=method_references).

        // Compound analysis (analyze_file/type/method now via analyze(kind);
        // get_type_usage_summary via inspect(kind)).

        // Advanced refactoring tools (extract/inline now via the `extract`/`inline` front doors above)
        toolRegistry.register(new ChangeMethodSignatureTool(() -> jdtService, refactoringChangeCache));
        toolRegistry.register(new ConvertAnonymousToLambdaTool(() -> jdtService, refactoringChangeCache));

        // Sprint 11 Phase E (v1.5.1): JDT-LTK structural refactoring.
        // move/pull-up/push-down now via the `move`/`move_in_hierarchy` front doors above.
        toolRegistry.register(new EncapsulateFieldTool(() -> jdtService, refactoringChangeCache));

        // Sprint 12 (v1.6.0): Ring 1 workspace verification tools.
        toolRegistry.register(new CompileWorkspaceTool(() -> jdtService));
        toolRegistry.register(new RunTestsTool(() -> jdtService));

        // Sprint 14 Phase B.1 (v1.8.0): consolidated lifecycle tool.
        toolRegistry.register(new RefreshWorkspaceTool(() -> jdtService));

        // Sprint 14 Phase B.3 (v1.8.0): structural clone detector.
        toolRegistry.register(new FindDuplicateCodeTool(() -> jdtService));

        // Sprint 15 (v1.10.0): parametric modernization sweeps.
        toolRegistry.register(new FindModernizationTool(() -> jdtService));

        // Sprint 15 (v1.10.0): parametric clean-up catalog (upstream v1.4.2 harvest).
        toolRegistry.register(new ApplyCleanupTool(() -> jdtService, refactoringChangeCache));

        // Sprint 15a/15b: naming/Javadoc/nullness analysis now via analyze(kind);
        // apply_null_annotations stays (it mutates).
        toolRegistry.register(new ApplyNullAnnotationsTool(() -> jdtService, refactoringChangeCache));

        // Sprint 13 (v1.7.0): Ring 2 code generation — now via the parametric `generate` front door above.

        // Sprint 16b/A (v1.1.1): dependency(action) collapses add/update/find_unused (Maven-only).
        toolRegistry.register(new DependencyTool(() -> jdtService));

        // Sprint 13 (v1.7.0): Ring 4 formatter / workflow polish.
        toolRegistry.register(new FormatTool(() -> jdtService));
        toolRegistry.register(new OptimizeImportsWorkspaceTool(() -> jdtService));

        // Sprint 16b/A (v1.1.1): quick_fix(action) collapses suggest_imports/get_quick_fixes/apply_quick_fix.
        toolRegistry.register(new QuickFixTool(() -> jdtService));

        // Metrics + advanced analysis now via inspect(kind) (complexity/dependency_graph/
        // di_registrations) and analyze(kind) (change_impact/control_flow/data_flow).
        // Sprint 11 Phase D: FindCircularDependencies / FindReflectionUsage /
        // FindLargeClasses / FindNamingViolations / FindUnusedCode /
        // FindPossibleBugs registrations dropped — exposed via
        // find_quality_issue(kind=...) above.

        // Sprint 16b/A (v1.1.1): refactoring(action) collapses the staged apply/undo/
        // inspect lifecycle (changes + undo handles in refactoringChangeCache, 1h TTL, LRU).
        // Sprint 21 (v2.0): inject the store-backed advisor so the plan lifecycle consults +
        // records against the knowledge store (fills the Sprint-18 seam; was NoOpAdvisor).
        toolRegistry.register(new RefactoringTool(() -> jdtService, refactoringChangeCache,
            new ExperienceAdvisor(experienceStore, () -> jdtService)));
        // Sprint 14b: composite closing the find_duplicate_code loop.
        toolRegistry.register(new ReplaceDuplicatesTool(() -> jdtService, refactoringChangeCache));

        // Sprint 21 (v2.0): the local experience/knowledge store front door.
        // experience(kind=record|...) — writes now, recall/load/maintenance land in
        // later stages. Backed by the store opened in start(), closed in stop().
        // Sprint 21b (item D): keep the reference — autoLoadProjects triggers the
        // automatic staleness refresh once projects are available.
        experienceTool = new ExperienceTool(() -> jdtService, experienceStore,
            this::defaultMemoryRoots);
        toolRegistry.register(experienceTool);
    }

    private void runMessageLoop(TransportConfig config) {
        // Sprint 14a Stage 3: transport.run(handler) drives the I/O loop
        // internally. Stdio loops on its read/write pair; HTTP runs the
        // JDK HttpServer until close() signals shutdown. activeTransport
        // exposes the running transport so GojaApplication.stop() can
        // unblock it on OSGi shutdown.
        try (Transport transport = openTransport(config)) {
            this.activeTransport = transport;
            transport.run(protocolHandler::processMessage);
        } catch (Exception e) {
            log.error("Error in message loop", e);
        } finally {
            this.activeTransport = null;
        }
    }

    /**
     * Sprint 14a Stage 2: construct the Transport per the parsed CLI config.
     * STDIO wraps System.in/out (current behaviour, opt-in only). HTTP
     * resolves the token (generate if absent) and hands off to HttpTransport
     * which throws until Stage 3 implements it.
     */
    private Transport openTransport(TransportConfig config) {
        if (config.getKind() == TransportConfig.Kind.STDIO) {
            log.info("Transport: stdio (opt-in via -transport stdio)");
            return new StdioTransport(System.in, System.out);
        }
        String token = config.getToken() != null
            ? config.getToken()
            : TokenGenerator.generate();
        log.info("Transport: http (default) — port={}, bind={}",
            config.getPort(), config.getBindAddress());
        return new HttpTransport(config.getPort(), config.getBindAddress(), token);
    }

    @Override
    public void stop() {
        log.info("Stop requested");
        Transport t = activeTransport;
        if (t != null) {
            t.close();
        }
        WorkspaceFileWatcher watcher = workspaceWatcher;
        if (watcher != null) {
            watcher.close();
            workspaceWatcher = null;
        }
        ExperienceStore store = experienceStore;
        if (store != null) {
            store.close();
            experienceStore = null;
        }
    }
}
