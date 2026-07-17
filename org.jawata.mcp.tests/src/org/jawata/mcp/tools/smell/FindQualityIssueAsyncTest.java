package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 25 Stage 14a (the C0-F2 cure): async family sweeps. A family sweep
 * can outlive a client timeout; the finished result must stay RETRIEVABLE
 * (repeatedly), progress must be visible while running, and cancellation is
 * honest (partial marked partial, never a silent nothing).
 */
class FindQualityIssueAsyncTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private FindQualityIssueTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        return (Map<String, Object>) r.getData();
    }

    private Map<String, Object> awaitFinished(String sweepId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            ObjectNode status = objectMapper.createObjectNode();
            status.put("action", "status");
            status.put("sweepId", sweepId);
            Map<String, Object> d = data(tool.execute(status));
            if (!"running".equals(d.get("state"))) {
                return d;
            }
            // Progress is visible while running.
            assertTrue((int) d.get("kindsTotal") > 0);
            Thread.sleep(100);
        }
        throw new AssertionError("sweep " + sweepId + " never finished");
    }

    @Test
    @DisplayName("start returns a sweepId; status reaches finished; the FULL result is retrievable REPEATEDLY")
    void asyncSweep_fullLifecycle() throws Exception {
        ObjectNode start = objectMapper.createObjectNode();
        start.put("action", "start");
        start.put("family", "quality");
        Map<String, Object> started = data(tool.execute(start));
        String sweepId = (String) started.get("sweepId");
        assertNotNull(sweepId, "start must hand out the sweep handle");
        assertTrue((int) started.get("kindsTotal") > 0);

        Map<String, Object> finished = awaitFinished(sweepId);
        assertEquals("finished", finished.get("state"));
        assertEquals("quality", finished.get("family"));
        assertNotNull(finished.get("findings"), "the FULL sweep result rides the status response");
        assertNotNull(finished.get("count"));

        // The point of the feature: a timed-out client RETRIEVES the completed
        // result — repeatedly, byte-for-byte the same set.
        Map<String, Object> again = awaitFinished(sweepId);
        assertEquals(finished.get("count"), again.get("count"));
        assertEquals("finished", again.get("state"));
    }

    @Test
    @DisplayName("cancel is honest: state=cancelled + partial:true, kindsDone visible")
    void asyncSweep_cancelIsHonestPartial() throws Exception {
        ObjectNode start = objectMapper.createObjectNode();
        start.put("action", "start");
        start.put("family", "fowler"); // the slow family — cancel can land mid-run
        String sweepId = (String) data(tool.execute(start)).get("sweepId");

        ObjectNode cancel = objectMapper.createObjectNode();
        cancel.put("action", "cancel");
        cancel.put("sweepId", sweepId);
        Map<String, Object> cancelled = data(tool.execute(cancel));
        assertTrue(Map.of("cancel_requested", 1, "cancelled", 1, "finished", 1)
                .containsKey(cancelled.get("state")), "state: " + cancelled.get("state"));

        Map<String, Object> end = awaitFinished(sweepId);
        // Raced completion is legitimate; a cancelled run must say partial.
        if ("cancelled".equals(end.get("state"))) {
            assertEquals(Boolean.TRUE, end.get("partial"));
            assertNotNull(end.get("kindsDone"));
        } else {
            assertEquals("finished", end.get("state"));
        }
    }

    @Test
    @DisplayName("unknown sweepId + start without family are loud INVALID_PARAMETER")
    void asyncSweep_invalidInputsAreLoud() {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("action", "status");
        status.put("sweepId", "sweep-never-existed");
        ToolResponse r = tool.execute(status);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());

        ObjectNode start = objectMapper.createObjectNode();
        start.put("action", "start");
        ToolResponse r2 = tool.execute(start);
        assertFalse(r2.isSuccess());
        assertEquals("INVALID_PARAMETER", r2.getError().getCode());

        ObjectNode bad = objectMapper.createObjectNode();
        bad.put("action", "resume");
        ToolResponse r3 = tool.execute(bad);
        assertFalse(r3.isSuccess());
        assertEquals("INVALID_PARAMETER", r3.getError().getCode());
    }

    @Test
    @DisplayName("synchronous path is untouched: action=run behaves like no action")
    void syncPathUnchanged() {
        ObjectNode plain = objectMapper.createObjectNode();
        plain.put("family", "quality");
        plain.put("summary", true);
        Map<String, Object> a = data(tool.execute(plain));

        ObjectNode run = objectMapper.createObjectNode();
        run.put("action", "run");
        run.put("family", "quality");
        run.put("summary", true);
        Map<String, Object> b = data(tool.execute(run));
        assertEquals(a.get("count"), b.get("count"));
    }
}
