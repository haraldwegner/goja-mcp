package org.goja.core.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.goja.core.IJdtService;
import org.goja.core.JdtServiceImpl;
import org.goja.core.LoadedProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Watches a {@code workspace.json} file in the Eclipse JDT data directory and
 * keeps the {@link JdtServiceImpl}'s project set in sync with its contents.
 *
 * <p>Sprint 10 v1.4.0: the manager (goja-studio) writes
 * {@code workspace.json} into the Eclipse {@code -data} dir at workspace
 * setup and on every project add/remove. goja reads the file on startup
 * (loading the listed projects into a single shared workspace) and reacts to
 * changes via {@link WatchService}, so the agent sees a transparent live
 * update without any subprocess restart.
 *
 * <p>File schema:
 * <pre>{@code
 * {
 *   "version": 1,
 *   "name": "example",
 *   "projects": [
 *     "/abs/path/to/project1",
 *     "/abs/path/to/project2"
 *   ]
 * }
 * }</pre>
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>{@link #start()} performs the initial load synchronously, then spawns
 *       a daemon thread to watch for changes.</li>
 *   <li>{@link #close()} closes the watch service and interrupts the watcher
 *       thread. Idempotent.</li>
 * </ul>
 *
 * <p>Thread-safety: {@code start()} and {@code close()} are not concurrent —
 * the {@link org.goja.mcp.GojaApplication} lifecycle calls them from
 * one thread. The watcher thread reads {@code workspace.json} and mutates the
 * service's project map; the service's add/remove methods are thread-safe.
 */
public class WorkspaceFileWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceFileWatcher.class);
    private static final String WORKSPACE_FILE_NAME = "workspace.json";

    /**
     * Sprint 14 (bugs.md #6): every {@code pollIntervalSeconds} the watcher
     * checks the mtime of {@code workspace.json} as a fallback against missed
     * {@link WatchService} events. Reproducing the production bug locally
     * proved hard (existing tests already cover atomic-rename via
     * {@code Files.move(... ATOMIC_MOVE)}); the periodic poll closes the gap
     * by guaranteeing reconciliation even if WatchService silently misses an
     * event for any reason. 2 s is fast enough that the user perceives "live
     * reload" and slow enough to not burn CPU in idle workspaces.
     */
    private static final long DEFAULT_FALLBACK_POLL_SECONDS = 2;

    /**
     * Debounce window after an event-driven wakeup. The manager may write
     * {@code workspace.json} via a tmp-and-rename pattern (multiple events)
     * or via chunked direct write (read race during write). 50 ms is enough
     * to absorb a tmp+rename burst without adding meaningful user-visible
     * latency.
     */
    private static final long DEBOUNCE_MILLIS = 50;

    private final Path workspaceJson;
    private final Path watchDir;
    private final JdtServiceImpl service;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long fallbackPollSeconds;

    private volatile WatchService watchService;
    private volatile Thread watcherThread;
    private volatile boolean running = false;
    private volatile long lastSeenMtimeMillis = -1;

    /**
     * @param jdtDataDir Eclipse {@code -data} directory. The watcher looks
     *                   for {@code workspace.json} inside this directory and
     *                   registers a {@link WatchService} on it.
     * @param service    JDT service to load/unload projects against. The
     *                   watcher calls {@link IJdtService#addProject(Path)} /
     *                   {@link IJdtService#removeProject(String)} on it.
     */
    public WorkspaceFileWatcher(Path jdtDataDir, JdtServiceImpl service) {
        this(jdtDataDir, service, DEFAULT_FALLBACK_POLL_SECONDS);
    }

    /**
     * Test-only constructor: lets a test set a shorter fallback-poll interval
     * to verify the mtime safety-net path within bounded wait time.
     */
    WorkspaceFileWatcher(Path jdtDataDir, JdtServiceImpl service, long fallbackPollSeconds) {
        if (jdtDataDir == null) {
            throw new IllegalArgumentException("jdtDataDir must not be null");
        }
        if (service == null) {
            throw new IllegalArgumentException("service must not be null");
        }
        if (fallbackPollSeconds <= 0) {
            throw new IllegalArgumentException("fallbackPollSeconds must be positive");
        }
        this.watchDir = jdtDataDir.toAbsolutePath().normalize();
        this.workspaceJson = this.watchDir.resolve(WORKSPACE_FILE_NAME);
        this.service = service;
        this.fallbackPollSeconds = fallbackPollSeconds;
    }

    /** Path to the {@code workspace.json} file this watcher tracks. */
    public Path workspaceJsonPath() {
        return workspaceJson;
    }

    /**
     * Perform the initial load (synchronously) and start the watcher thread.
     * If {@code workspace.json} does not exist on disk, the watcher still
     * starts and will pick up the file when it appears.
     */
    public synchronized void start() throws IOException {
        if (running) {
            log.debug("WorkspaceFileWatcher already running");
            return;
        }

        if (!Files.isDirectory(watchDir)) {
            Files.createDirectories(watchDir);
        }

        // Initial load (best-effort). Missing file = empty workspace.
        if (Files.isRegularFile(workspaceJson)) {
            try {
                List<Path> initialPaths = readWorkspacePaths();
                applyDiff(initialPaths);
                log.info("WorkspaceFileWatcher initial load: {} project(s)", initialPaths.size());
            } catch (Exception e) {
                log.warn("Failed initial workspace.json load at {}: {}",
                    workspaceJson, e.getMessage());
            }
        } else {
            log.info("workspace.json not present at {}; watcher armed for file creation", workspaceJson);
        }

        // Register watch on the parent dir (WatchService can't watch a single
        // file directly — only directories — so we filter events by name).
        watchService = FileSystems.getDefault().newWatchService();
        watchDir.register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY
        );

        running = true;
        watcherThread = new Thread(this::runWatchLoop, "goja-workspace-watcher");
        watcherThread.setDaemon(true);
        watcherThread.start();
    }

    @Override
    public synchronized void close() {
        running = false;
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.debug("Error closing watch service: {}", e.getMessage());
            }
            watchService = null;
        }
        if (watcherThread != null) {
            watcherThread.interrupt();
            watcherThread = null;
        }
    }

    private void runWatchLoop() {
        log.debug("Watch loop started for {}", workspaceJson);
        recordCurrentMtime();
        try {
            while (running) {
                try {
                    // Sprint 14 (bugs.md #6): use poll(timeout) instead of
                    // take() so the loop wakes periodically even when no
                    // events fire. The wakeup runs an mtime-based fallback
                    // reconcile — the production safety net for any silent
                    // WatchService event-miss.
                    WatchKey key = watchService.poll(fallbackPollSeconds, TimeUnit.SECONDS);
                    if (key == null) {
                        // Timed out without events — fallback mtime check.
                        runMtimeFallbackIfChanged();
                        continue;
                    }

                    boolean affected = false;
                    boolean overflowed = false;
                    for (var event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            overflowed = true;
                            continue;
                        }
                        Object ctx = event.context();
                        if (ctx instanceof Path p && WORKSPACE_FILE_NAME.equals(p.toString())) {
                            affected = true;
                        }
                    }

                    if (!key.reset()) {
                        log.warn("WatchKey no longer valid for {}; watcher exiting", watchDir);
                        break;
                    }

                    if (overflowed) {
                        log.warn("WatchService OVERFLOW at {}; OS dropped events — reconciling unconditionally",
                            watchDir);
                    }

                    if (affected || overflowed) {
                        // Debounce: rapid back-to-back events (tmp+rename
                        // pattern, chunked writes) coalesce into one reconcile.
                        // Reading mid-write would observe partial content.
                        Thread.sleep(DEBOUNCE_MILLIS);
                        // Drain any further events that arrived during sleep.
                        WatchKey drain = watchService.poll();
                        while (drain != null) {
                            drain.pollEvents();
                            drain.reset();
                            drain = watchService.poll();
                        }
                        reconcileFromDisk();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (ClosedWatchServiceException e) {
                    break;
                } catch (Throwable t) {
                    // Belt-and-braces: keep the watch loop alive in the face
                    // of any single-event failure. Silent thread death (the
                    // suspected production cause of bugs.md #6) is the bigger
                    // danger than a missed event.
                    log.warn("WorkspaceFileWatcher loop iteration threw; loop continues: {}",
                        t.getMessage(), t);
                }
            }
        } finally {
            log.info("WorkspaceFileWatcher loop stopped for {}", workspaceJson);
        }
    }

    /**
     * Fallback safety-net: if the file's mtime changed since we last
     * reconciled and no event fired, reconcile anyway. Guards against
     * silent WatchService misses (the production cause of bugs.md #6
     * that proved hard to reproduce in tests).
     */
    private void runMtimeFallbackIfChanged() {
        long currentMtime = readCurrentMtime();
        if (currentMtime != lastSeenMtimeMillis) {
            log.debug("workspace.json mtime changed since last reconcile (event missed); reconciling");
            reconcileFromDisk();
        }
    }

    private void reconcileFromDisk() {
        try {
            List<Path> newPaths = readWorkspacePaths();
            applyDiff(newPaths);
        } catch (Exception e) {
            log.warn("Failed to apply workspace.json change at {}: {}",
                workspaceJson, e.getMessage(), e);
        } finally {
            // Always record the latest mtime so the fallback poll's next
            // tick doesn't double-fire reconcile against the file we just
            // read.
            recordCurrentMtime();
        }
    }

    private void recordCurrentMtime() {
        lastSeenMtimeMillis = readCurrentMtime();
    }

    private long readCurrentMtime() {
        try {
            if (!Files.isRegularFile(workspaceJson)) return -1;
            return Files.getLastModifiedTime(workspaceJson).toMillis();
        } catch (IOException e) {
            log.debug("Failed to read mtime of {}: {}", workspaceJson, e.getMessage());
            return -1;
        }
    }

    /**
     * Read the project-paths array from {@code workspace.json}.
     * Missing file → empty list.
     */
    private List<Path> readWorkspacePaths() throws IOException {
        if (!Files.isRegularFile(workspaceJson)) {
            return List.of();
        }
        JsonNode root = mapper.readTree(workspaceJson.toFile());
        JsonNode projects = root.get("projects");
        if (projects == null || !projects.isArray()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (JsonNode entry : projects) {
            String text = entry.asText("").trim();
            if (text.isEmpty()) continue;
            paths.add(Path.of(text).toAbsolutePath().normalize());
        }
        return paths;
    }

    /**
     * Reconcile the service's currently-loaded projects against the desired
     * project-paths list. Adds new ones, removes gone ones. Existing
     * projects that are still listed are left untouched.
     */
    private void applyDiff(List<Path> desired) {
        // Map currently-loaded projects: absolute root -> projectKey.
        Map<Path, String> currentByRoot = new HashMap<>();
        for (LoadedProject loaded : service.allProjects()) {
            currentByRoot.put(loaded.projectRoot().toAbsolutePath().normalize(), loaded.projectKey());
        }

        Set<Path> desiredSet = new HashSet<>(desired);

        // Additions: paths in desired but not currently loaded.
        int loaded = service.allProjects().size();
        for (Path p : desired) {
            if (!currentByRoot.containsKey(p)) {
                if (loaded == 0) {
                    try {
                        service.loadProject(p);
                        log.info("WorkspaceFileWatcher loaded initial project: {}", p);
                        loaded++;
                    } catch (Exception e) {
                        log.warn("Failed to loadProject {}: {}", p, e.getMessage());
                    }
                } else {
                    try {
                        service.addProject(p);
                        log.info("WorkspaceFileWatcher added project: {}", p);
                        loaded++;
                    } catch (Exception e) {
                        log.warn("Failed to addProject {}: {}", p, e.getMessage());
                    }
                }
            }
        }

        // Removals: currently-loaded paths not in desired.
        for (var entry : currentByRoot.entrySet()) {
            if (!desiredSet.contains(entry.getKey())) {
                String key = entry.getValue();
                if (service.removeProject(key)) {
                    log.info("WorkspaceFileWatcher removed project: {} ({})", entry.getKey(), key);
                } else {
                    log.warn("removeProject({}) returned false for path {}", key, entry.getKey());
                }
            }
        }
    }
}
