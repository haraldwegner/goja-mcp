package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D8, C10) — probes: watch a running program <b>without stopping it</b>.
 *
 * <p>This is the capability that matters on a live simulation, where suspending the world
 * is precisely what you cannot do. So the central proof is not "values arrived" — it is
 * "values arrived AND the program never stopped", checked while the values are arriving.</p>
 */
class DevProbeTest {

    private static final String TARGET = "com.example.debug.DebugTarget";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool tool;
    private ObjectMapper om;
    private List<String> source;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        tool = new DebugTool(() -> service, sessions);
        om = new ObjectMapper();

        Path file = service.getProjectRoot()
            .resolve("src/main/java/com/example/debug/DebugTarget.java");
        source = Files.readAllLines(file);

        Path classes = Files.createTempDirectory("jawata-debug-probe-");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", classes.toString(), file.toString()));

        ObjectNode launch = action("launch");
        launch.put("mainClass", TARGET);
        launch.put("classpath", classes.toString());
        sessionId = (String) ok(launch).get("sessionId");

        // Start the program and let it get going. A probe watches a RUNNING program, so
        // unlike the breakpoint tests we do not hold it anywhere.
        startAndRunToLoadTheClass();
    }

    @AfterEach
    void tearDown() {
        sessions.closeAll();
    }

    // ------------------------------------------------------------- plumbing

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode action(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    private ObjectNode onSession(String name) {
        ObjectNode args = action(name);
        args.put("sessionId", sessionId);
        return args;
    }

    private Map<String, Object> ok(ObjectNode args) {
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), args.get("action").asText() + " failed: " + r.getError());
        return data(r);
    }

    private int lineOf(String snippet) {
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i).contains(snippet)) {
                return i + 1;
            }
        }
        throw new AssertionError("the fixture no longer contains: " + snippet);
    }

    /**
     * Start the target and let its class load — a probe can only be attached to a class the
     * running program actually has.
     */
    private void startAndRunToLoadTheClass() {
        ObjectNode bootstrap = onSession("breakpoint_set");
        bootstrap.put("kind", "line");
        bootstrap.put("className", TARGET);
        bootstrap.put("line", lineOf("Node head = graph;"));
        String id = (String) ok(bootstrap).get("breakpointId");

        assertTrue(tool.execute(onSession("resume")).isSuccess());

        ObjectNode wait = onSession("wait");
        wait.put("timeoutMillis", 30_000);
        Map<String, Object> hit = ok(wait);
        assertEquals(Boolean.TRUE, hit.get("hit"), "the class must load: " + hit);
        long thread = ((Number) hit.get("threadId")).longValue();

        ObjectNode clear = onSession("breakpoint_clear");
        clear.put("breakpointId", id);
        assertTrue(tool.execute(clear).isSuccess());

        // And let it RUN. From here nothing of ours stops it.
        ObjectNode go = onSession("resume");
        go.put("threadId", thread);
        assertTrue(tool.execute(go).isSuccess());
    }

    private Map<String, Object> probeRead(String probeId) {
        ObjectNode args = onSession("probe_read");
        args.put("probeId", probeId);
        return ok(args);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> eventsOf(String probeId) {
        return (List<Map<String, Object>>) probeRead(probeId).get("events");
    }

    /** Poll until the probe has streamed at least this many events, or fail. */
    private List<Map<String, Object>> awaitEvents(String probeId, int atLeast) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            List<Map<String, Object>> events = eventsOf(probeId);
            if (events.size() >= atLeast) {
                return events;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the probe streamed fewer than " + atLeast + " events in 30s: "
            + eventsOf(probeId).size());
    }

    /** What the VM says the main thread is doing, right now. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mainThread() {
        List<Map<String, Object>> threads =
            (List<Map<String, Object>>) ok(onSession("threads")).get("threads");
        return threads.stream()
            .filter(t -> "main".equals(t.get("name")))
            .findFirst().orElseThrow(() -> new AssertionError("no main thread"));
    }

    // ------------------------------------------------------------ the proof

    @Test
    @DisplayName("THE PROOF: a field probe streams values while the loop provably KEEPS RUNNING")
    void aProbeStreamsValuesWithoutEverStoppingTheProgram() throws Exception {
        ObjectNode probe = onSession("probe_set");
        probe.put("kind", "field_watch");
        probe.put("className", TARGET);
        probe.put("field", "lastSignal");
        Map<String, Object> armed = ok(probe);

        assertEquals(Boolean.FALSE, armed.get("perturbs"),
            "a field watch reads what the EVENT carries — it stops nothing: " + armed);
        assertEquals(Boolean.FALSE, armed.get("suspendsTarget"));
        String probeId = (String) armed.get("probeId");

        // Values stream out of the hot loop.
        List<Map<String, Object>> events = awaitEvents(probeId, 8);

        // ...AND WHILE THEY DO, the loop thread is RUNNING. Checked repeatedly, WHILE the
        // stream is live — this is the whole claim, and a single check could get lucky.
        for (int i = 0; i < 10; i++) {
            Map<String, Object> main = mainThread();
            assertEquals(Boolean.FALSE, main.get("suspended"),
                "a probe must NEVER leave the program suspended — that is the entire point "
                    + "of a probe on a live simulation: " + main);
            Thread.sleep(30);
        }

        // The values are real: the fixture computes iteration*2 + 7, so writes are odd and
        // increase by 2. And both a READ and a WRITE are seen — echo() reads it, main writes.
        List<Map<String, Object>> writes = events.stream()
            .filter(e -> "write".equals(e.get("access"))).toList();
        List<Map<String, Object>> reads = events.stream()
            .filter(e -> "read".equals(e.get("access"))).toList();
        assertFalse(writes.isEmpty(), "the field is written every iteration: " + events);
        assertFalse(reads.isEmpty(), "and read by echo(): " + events);

        assertNotNull(writes.get(0).get("newValue"), "a write carries the value being stored");
        assertEquals("main", writes.get(0).get("threadName"));

        // The stream KEEPS growing — the program really did carry on.
        int soFar = eventsOf(probeId).size();
        Thread.sleep(500);
        assertTrue(eventsOf(probeId).size() > soFar,
            "the program went on running and the probe went on watching");
    }

    @Test
    @DisplayName("method_trace: entry AND exit, with the return value — and nothing is stopped")
    void methodTraceCarriesTheReturnValueForFree() throws Exception {
        ObjectNode probe = onSession("probe_set");
        probe.put("kind", "method_trace");
        probe.put("className", TARGET);
        probe.put("method", "offset");
        Map<String, Object> armed = ok(probe);
        assertEquals(Boolean.FALSE, armed.get("perturbs"), "got: " + armed);
        String probeId = (String) armed.get("probeId");

        List<Map<String, Object>> events = awaitEvents(probeId, 6);

        assertTrue(events.stream().anyMatch(e -> "entry".equals(e.get("trace"))),
            "entries are traced: " + events);
        Map<String, Object> exit = events.stream()
            .filter(e -> "exit".equals(e.get("trace")))
            .filter(e -> "offset".equals(e.get("method")))
            .findFirst().orElseThrow(() -> new AssertionError("no exit of offset(): " + events));

        // The RETURN VALUE rides in the event — no frame read, no stopping.
        assertEquals(7, ((Number) exit.get("returned")).intValue(),
            "offset() returns 7, and the event says so without stopping anything: " + exit);

        assertEquals(Boolean.FALSE, mainThread().get("suspended"),
            "still running while we traced it");
    }

    @Test
    @DisplayName("a capturing logpoint DOES stop the thread — and says so rather than pretending")
    void aCapturingLogpointDeclaresThatItPerturbs() throws Exception {
        ObjectNode probe = onSession("probe_set");
        probe.put("kind", "logpoint");
        probe.put("className", TARGET);
        probe.put("line", lineOf("int doubled = iteration * 2;"));
        probe.putArray("capture").add("iteration").add("iteration * 2 + offset()");
        Map<String, Object> armed = ok(probe);

        // THE HONEST DECLARATION. Locals cannot be read from a running thread, so this one
        // stops it — briefly, resuming itself at once. Saying "non-suspending" here would be
        // a lie, and a lie that silently changes the timing of the race you are hunting.
        assertEquals(Boolean.TRUE, armed.get("perturbs"),
            "capturing expressions means reading a frame, which means stopping: " + armed);
        assertEquals(Boolean.TRUE, armed.get("suspendsTarget"));
        String probeId = (String) armed.get("probeId");

        List<Map<String, Object>> events = awaitEvents(probeId, 4);

        @SuppressWarnings("unchecked")
        Map<String, Object> captured = (Map<String, Object>) events.get(1).get("captured");
        assertNotNull(captured, "the captured expressions are there: " + events.get(1));
        assertNotNull(captured.get("iteration"));
        assertEquals(((Number) captured.get("iteration")).intValue() * 2 + 7,
            ((Number) captured.get("iteration * 2 + offset()")).intValue(),
            "a real expression, evaluated in the live frame: " + captured);

        // THE PROGRAM KEEPS MAKING PROGRESS, on its own, with nobody resuming it.
        int before = eventsOf(probeId).size();
        Thread.sleep(600);
        assertTrue(eventsOf(probeId).size() > before,
            "the program must keep running under a capturing logpoint — the probe stops the "
                + "thread, reads the frame, and lets it go, and nobody has to resume it");

        // WHAT THIS DELIBERATELY DOES NOT ASSERT: that the thread is never *observed*
        // suspended. It genuinely is suspended during each capture — that is precisely what
        // perturbs:true means — and this probe evaluates an expression that INVOKES A METHOD
        // in the target, which is not cheap. On a 25ms loop the thread can therefore be
        // stopped for a large fraction of its time, and a poll will keep landing inside a
        // capture. (It did, in a serial run: 10s of polling never saw it running.) Asserting
        // "never suspended" would be asserting something we have already declared false.
        //
        // The guarantee that actually matters: REMOVE THE PROBE AND THE PROGRAM IS WHOLE. It
        // is not left suspended, and nothing has to rescue it.
        ObjectNode clear = onSession("probe_clear");
        clear.put("probeId", probeId);
        assertTrue(tool.execute(clear).isSuccess());

        long deadline = System.currentTimeMillis() + 15_000;
        while (Boolean.TRUE.equals(mainThread().get("suspended"))
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(Boolean.FALSE, mainThread().get("suspended"),
            "with the probe gone the program runs free again — a probe must never leave the "
                + "target suspended behind it");
    }

    @Test
    @DisplayName("a probe's budget stops it — and it says the stream is not the whole story")
    void aProbeStopsAtItsBudgetAndSaysSo() throws Exception {
        ObjectNode probe = onSession("probe_set");
        probe.put("kind", "field_watch");
        probe.put("className", TARGET);
        probe.put("field", "lastSignal");
        probe.put("budget", 6);          // a hot path would otherwise stream forever
        String probeId = (String) ok(probe).get("probeId");

        awaitEvents(probeId, 6);

        long deadline = System.currentTimeMillis() + 20_000;
        Map<String, Object> read = probeRead(probeId);
        while (read.get("probeStopped") == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            read = probeRead(probeId);
        }

        assertNotNull(read.get("probeStopped"),
            "past its budget the probe must stop itself: " + read);
        assertTrue(String.valueOf(read.get("probeStopped")).contains("not all of them"),
            "and must NOT let the first N events read as the whole stream: "
                + read.get("probeStopped"));

        // The program is unaffected — it kept running; only our watching stopped.
        assertEquals(Boolean.FALSE, mainThread().get("suspended"));
    }
}
