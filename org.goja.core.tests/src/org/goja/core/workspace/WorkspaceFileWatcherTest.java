package org.goja.core.workspace;

import org.goja.core.JdtServiceImpl;
import org.goja.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link WorkspaceFileWatcher}. Drives the watcher
 * against real JdtServiceImpl + real fixture projects, and writes
 * {@code workspace.json} files to provoke load/add/remove.
 *
 * <p>Uses a polling helper {@link #waitUntil(Duration, java.util.function.BooleanSupplier)}
 * because {@code WatchService} events are inherently asynchronous; we accept
 * a short delay (max 5 s) for the file-watcher thread to react.
 */
class WorkspaceFileWatcherTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private Path dataDir;
    private Path workspaceJson;
    private WorkspaceFileWatcher watcher;
    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        dataDir = Files.createTempDirectory("jl-workspace-watcher-test-");
        workspaceJson = dataDir.resolve("workspace.json");
        service = new JdtServiceImpl();
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.close();
            watcher = null;
        }
        // best-effort cleanup; tempdir cleanup not strictly needed
    }

    @Test
    @DisplayName("initial load reads workspace.json and loads listed projects")
    void initialLoad_loadsAllProjects() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        writeWorkspaceJson(simpleMaven, simpleMavenB);

        watcher = new WorkspaceFileWatcher(dataDir, service);
        watcher.start();

        assertEquals(2, service.allProjects().size(),
            "Both fixture projects should be loaded after initial start()");
        assertTrue(service.defaultProjectKey().isPresent(),
            "Default project key should be set (first project loaded via loadProject)");
    }

    @Test
    @DisplayName("appending a project to workspace.json adds it to the workspace within 5s")
    void addProjectOnFileChange() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        writeWorkspaceJson(simpleMaven);

        watcher = new WorkspaceFileWatcher(dataDir, service);
        watcher.start();
        assertEquals(1, service.allProjects().size());

        // Mutate the file: add simple-maven-b.
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        writeWorkspaceJson(simpleMaven, simpleMavenB);

        boolean grew = waitUntil(Duration.ofSeconds(5), () -> service.allProjects().size() == 2);
        assertTrue(grew,
            "Watcher should have picked up the added path within 5 s. Loaded: " +
            service.projectKeys());
    }

    @Test
    @DisplayName("removing a project from workspace.json drops it from the workspace within 5s")
    void removeProjectOnFileChange() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        writeWorkspaceJson(simpleMaven, simpleMavenB);

        watcher = new WorkspaceFileWatcher(dataDir, service);
        watcher.start();
        assertEquals(2, service.allProjects().size());

        // Mutate the file: drop simple-maven-b.
        writeWorkspaceJson(simpleMaven);

        boolean shrunk = waitUntil(Duration.ofSeconds(5), () -> service.allProjects().size() == 1);
        assertTrue(shrunk,
            "Watcher should have removed the dropped path within 5 s. Loaded: " +
            service.projectKeys());
    }

    @Test
    @DisplayName("bugs.md #6: non-atomic direct write to workspace.json (no tmp+rename) is still picked up")
    void picksUpNonAtomicDirectWrite() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        writeWorkspaceJson(simpleMaven);

        watcher = new WorkspaceFileWatcher(dataDir, service);
        watcher.start();
        assertEquals(1, service.allProjects().size());

        // Bypass the atomic-rename helper: write the new contents directly to
        // workspace.json. This is the production-suspect failure mode for
        // bug #6 — a write pattern that doesn't go through Files.move(...
        // ATOMIC_MOVE) and might fire different (or fewer) WatchService
        // events. The new mtime-fallback poll guarantees reconciliation
        // regardless of event delivery.
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        String body = "{\n  \"version\": 1,\n  \"name\": \"test\",\n  \"projects\": [\n"
            + "    \"" + simpleMaven.toAbsolutePath() + "\",\n"
            + "    \"" + simpleMavenB.toAbsolutePath() + "\"\n"
            + "  ]\n}\n";
        Files.writeString(workspaceJson, body);

        boolean grew = waitUntil(Duration.ofSeconds(5),
            () -> service.allProjects().size() == 2);
        assertTrue(grew,
            "Watcher should pick up a non-atomic direct write within 5 s. Loaded: " +
            service.projectKeys());
    }

    @Test
    @DisplayName("bugs.md #6: short-fallback-poll constructor reconciles within the poll interval even if events were missed")
    void shortFallbackPoll_reconcilesViaMtimeCheck() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        writeWorkspaceJson(simpleMaven);

        // Test-only constructor with 1s fallback poll.
        watcher = new WorkspaceFileWatcher(dataDir, service, 1);
        watcher.start();
        assertEquals(1, service.allProjects().size());

        // Modify via tmp+rename — events should fire AND the fallback would
        // catch it within 1s if events did get missed. Either path produces
        // the expected reconciliation.
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        writeWorkspaceJson(simpleMaven, simpleMavenB);

        boolean grew = waitUntil(Duration.ofSeconds(3),
            () -> service.allProjects().size() == 2);
        assertTrue(grew,
            "Short-fallback-poll watcher should reconcile within 3 s. Loaded: " +
            service.projectKeys());
    }

    @Test
    @DisplayName("malformed JSON write does not crash the watcher")
    void malformedJson_logsAndContinues() throws Exception {
        Path simpleMaven = helper.getFixturePath("simple-maven");
        writeWorkspaceJson(simpleMaven);

        watcher = new WorkspaceFileWatcher(dataDir, service);
        watcher.start();
        assertEquals(1, service.allProjects().size());

        // Corrupt the file. Watcher should log and continue running.
        Files.writeString(workspaceJson, "{ this is not json }");
        Thread.sleep(500); // give the watcher a moment to process the bad write

        // Now write a valid file again. If the watcher survived, it should
        // pick up this change and react.
        Path simpleMavenB = helper.getFixturePath("simple-maven-b");
        writeWorkspaceJson(simpleMaven, simpleMavenB);

        boolean recovered = waitUntil(Duration.ofSeconds(5), () -> service.allProjects().size() == 2);
        assertTrue(recovered,
            "Watcher should still be alive after malformed write. Loaded: " +
            service.projectKeys());
    }

    /**
     * Atomically write a workspace.json with the given project paths.
     * Uses a temp-and-rename pattern matching what the manager will do, so
     * the watcher never observes a half-written file.
     */
    private void writeWorkspaceJson(Path... paths) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n  \"version\": 1,\n  \"name\": \"test\",\n  \"projects\": [\n");
        for (int i = 0; i < paths.length; i++) {
            if (i > 0) sb.append(",\n");
            sb.append("    \"").append(paths[i].toAbsolutePath()).append("\"");
        }
        sb.append("\n  ]\n}\n");

        Path tmp = workspaceJson.resolveSibling("workspace.json.tmp");
        Files.writeString(tmp, sb.toString());
        Files.move(tmp, workspaceJson,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Polling helper. Returns {@code true} once {@code condition} is met,
     * {@code false} after timeout.
     */
    private static boolean waitUntil(Duration timeout, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }
}
