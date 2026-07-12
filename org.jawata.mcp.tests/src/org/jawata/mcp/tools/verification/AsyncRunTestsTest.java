package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 23 (Stage 2) — streaming/progressive results on the forked-runner
 * spine: an async session reports per-class progress BEFORE completion via
 * {@code action=status}; the final summary stays retrievable afterwards;
 * {@code action=cancel} mid-run reaps the runner with no orphan JVM.
 */
class AsyncRunTestsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RunTestsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("runner-pathological");
        tool = new RunTestsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("streaming: status shows per-class progress BEFORE completion, final summary after")
    void asyncRun_progressObservableBeforeCompletion() throws Exception {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "package");
        scope.put("packageName", "com.example.pathological.slow");
        args.put("framework", "junit5");
        args.put("action", "start");
        args.put("timeoutSeconds", 120);

        ToolResponse started = tool.execute(args);
        assertTrue(started.isSuccess(), "start failed: " + started.getError());
        String sessionId = (String) dataOf(started).get("sessionId");
        assertNotNull(sessionId);

        // Poll: the slow suite (4 tests × 1.5 s across 2 classes) must be
        // observably IN PROGRESS at some poll — running state with at least
        // one test already finished — before it completes.
        boolean sawLiveProgress = false;
        Map<String, Object> finalData = null;
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            ToolResponse status = tool.execute(statusArgs(sessionId));
            assertTrue(status.isSuccess(), "status failed: " + status.getError());
            Map<String, Object> d = dataOf(status);
            Map<String, Object> progress = progressOf(d);
            if ("RUNNING".equals(d.get("state"))
                    && ((Number) progress.get("testsFinished")).intValue() >= 1) {
                sawLiveProgress = true;
            }
            if (!"RUNNING".equals(d.get("state"))) {
                finalData = d;
                break;
            }
            Thread.sleep(300);
        }
        assertNotNull(finalData, "session never finished within the poll window");
        assertTrue(sawLiveProgress,
            "no poll observed live progress (state=RUNNING with testsFinished >= 1) — "
                + "streaming is not progressive; final: " + finalData);

        assertEquals("FINISHED", finalData.get("state"));
        Map<String, Object> summary = summaryOf(finalData);
        assertEquals(4, summary.get("total"), "summary: " + summary);
        assertEquals(4, summary.get("passed"), "summary: " + summary);
        assertEquals(Boolean.TRUE, summary.get("evidenceFinalized"));
        // Per-class events made it through the stream.
        assertEquals(2, ((Number) progressOf(finalData).get("classesFinished")).intValue(),
            "both slow classes must report class-finish; final: " + finalData);

        // The summary stays available on a LATER poll after completion.
        Map<String, Object> again = dataOf(tool.execute(statusArgs(sessionId)));
        assertEquals(4, summaryOf(again).get("total"),
            "the finished summary must remain retrievable");
    }

    @Test
    @DisplayName("cancel: mid-run abort reaps the runner, honest partial result, no orphan JVM")
    void asyncRun_cancelMidRunLeavesNoOrphan() throws Exception {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", "com.example.pathological.HangingTest");
        args.put("framework", "junit5");
        args.put("action", "start");
        args.put("timeoutSeconds", 300);

        ToolResponse started = tool.execute(args);
        assertTrue(started.isSuccess(), "start failed: " + started.getError());
        String sessionId = (String) dataOf(started).get("sessionId");

        // Wait until the hang is actually underway (its class started).
        long deadline = System.currentTimeMillis() + 60_000;
        boolean underway = false;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> d = dataOf(tool.execute(statusArgs(sessionId)));
            if (((Number) progressOf(d).get("classesStarted")).intValue() >= 1) {
                underway = true;
                break;
            }
            Thread.sleep(300);
        }
        assertTrue(underway, "HangingTest never reported class-start");

        ObjectNode cancel = statusArgs(sessionId);
        cancel.put("action", "cancel");
        Map<String, Object> cancelled = dataOf(tool.execute(cancel));
        assertEquals("CANCELLED", cancelled.get("state"), "got: " + cancelled);
        Map<String, Object> summary = summaryOf(cancelled);
        assertNotNull(summary, "cancel must return the (partial) result");
        assertEquals(Boolean.FALSE, summary.get("evidenceFinalized"), "summary: " + summary);
        assertEquals(Boolean.TRUE, summary.get("cancelled"), "summary: " + summary);

        List<String> orphans = ProcessHandle.allProcesses()
            .filter(ProcessHandle::isAlive)
            .map(p -> p.info().commandLine().orElse(""))
            .filter(cmd -> cmd.contains("org.jawata.testrunner.Main"))
            .toList();
        assertTrue(orphans.isEmpty(), "orphan runner JVM(s) survived the cancel: " + orphans);
    }

    private ObjectNode statusArgs(String sessionId) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("action", "status");
        n.put("sessionId", sessionId);
        return n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> progressOf(Map<String, Object> data) {
        return (Map<String, Object>) data.get("progress");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summaryOf(Map<String, Object> data) {
        return (Map<String, Object>) data.get("summary");
    }
}
