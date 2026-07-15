package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.jawata.mcp.tools.ProfileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D12, C17) — latency at a named seam. A deliberately hot but PLAIN
 * seam reports low, stable percentiles; the SAME seam under an injected 50ms
 * tail-latency shows p99/p999 move accordingly while p50 stays put. The
 * coordinated-omission correction's actual math is proven separately and
 * deterministically in {@link LatencyCalculatorTest}; this test proves the
 * end-to-end mechanism (JDT resolution, live tracing, pairing, reporting).
 */
class LatencySeamTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool debug;
    private ProfileTool profile;
    private ObjectMapper om;
    private Path targetClasses;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        debug = new DebugTool(() -> service, sessions);
        profile = new ProfileTool(() -> service, sessions);
        om = new ObjectMapper();

        targetClasses = Files.createTempDirectory("jawata-latency-target-");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example/debug");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", targetClasses.toString(),
            pkg.resolve("DebugTarget.java").toString(),
            pkg.resolve("LatencySeamTarget.java").toString());
        assertEquals(0, rc, "the latency fixtures must compile");
    }

    @AfterEach
    void tearDown() {
        sessions.closeAll();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode debugAction(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    private ObjectNode profileAction(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    private String launchAndResume(String mainClass, String... extraJvmArgs) {
        ObjectNode launch = debugAction("launch");
        launch.put("mainClass", mainClass);
        launch.put("classpath", targetClasses.toString());
        if (extraJvmArgs.length > 0) {
            ArrayNode arr = launch.putArray("jvmArgs");
            for (String a : extraJvmArgs) {
                arr.add(a);
            }
        }
        ToolResponse launched = debug.execute(launch);
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        String sessionId = (String) data(launched).get("sessionId");

        ObjectNode resume = debugAction("resume");
        resume.put("sessionId", sessionId);
        assertTrue(debug.execute(resume).isSuccess());
        return sessionId;
    }

    @SuppressWarnings("unchecked")
    private static long millis(Map<String, Object> percentiles, String key) {
        return ((Number) percentiles.get(key)).longValue();
    }

    // ========================================================== the core exit criterion

    @Test
    @DisplayName("an injected 50ms slowdown moves p99/p999 accordingly, while p50 stays put")
    void injectedSlowdownMovesTailPercentilesNotTheMedian() throws Exception {
        String baselineSession = launchAndResume("com.example.debug.LatencySeamTarget");
        ObjectNode baselineArgs = profileAction("latency_seam");
        baselineArgs.put("sessionId", baselineSession);
        baselineArgs.put("className", "com.example.debug.LatencySeamTarget");
        baselineArgs.put("method", "seam");
        baselineArgs.put("durationSeconds", 4);
        ToolResponse baselineR = profile.execute(baselineArgs);
        assertTrue(baselineR.isSuccess(), "got: " + baselineR.getError());
        Map<String, Object> baseline = data(baselineR);

        String slowSession = launchAndResume("com.example.debug.LatencySeamTarget",
            "-Djawata.latency.slowdown=true");
        ObjectNode slowArgs = profileAction("latency_seam");
        slowArgs.put("sessionId", slowSession);
        slowArgs.put("className", "com.example.debug.LatencySeamTarget");
        slowArgs.put("method", "seam");
        slowArgs.put("durationSeconds", 4);
        ToolResponse slowR = profile.execute(slowArgs);
        assertTrue(slowR.isSuccess(), "got: " + slowR.getError());
        Map<String, Object> slow = data(slowR);

        assertTrue(((Number) baseline.get("sampleCount")).intValue() > 100,
            "enough calls observed to trust the percentiles: " + baseline);
        assertTrue(((Number) slow.get("sampleCount")).intValue() > 100, "got: " + slow);

        @SuppressWarnings("unchecked")
        Map<String, Object> baselineRaw = (Map<String, Object>) baseline.get("raw");
        @SuppressWarnings("unchecked")
        Map<String, Object> slowRaw = (Map<String, Object>) slow.get("raw");

        long baselineP50 = millis(baselineRaw, "p50Millis");
        long slowP50 = millis(slowRaw, "p50Millis");
        long baselineP99 = millis(baselineRaw, "p99Millis");
        long slowP99 = millis(slowRaw, "p99Millis");
        long slowP999 = millis(slowRaw, "p999Millis");

        assertTrue(baselineP50 < 20,
            "baseline p50 must be near the seam's real (low) cost: " + baselineRaw);
        assertTrue(slowP50 < 20,
            "p50 (98% of calls) must NOT move — the slowdown is a TAIL event: " + slowRaw);
        assertTrue(slowP99 >= baselineP99 + 30,
            "p99 MUST reflect the injected +50ms tail: baseline=" + baselineRaw + " slow=" + slowRaw);
        assertTrue(slowP999 >= baselineP99 + 30,
            "p999 MUST reflect it too: " + slowRaw);
    }

    @Test
    @DisplayName("corrected percentiles are reported alongside raw, with the synthetic-sample count named")
    void correctedPercentilesAccompanyRaw() throws Exception {
        String sessionId = launchAndResume("com.example.debug.LatencySeamTarget",
            "-Djawata.latency.slowdown=true");
        ObjectNode args = profileAction("latency_seam");
        args.put("sessionId", sessionId);
        args.put("className", "com.example.debug.LatencySeamTarget");
        args.put("method", "seam");
        args.put("durationSeconds", 4);
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        @SuppressWarnings("unchecked")
        Map<String, Object> corrected = (Map<String, Object>) d.get("corrected");
        assertTrue(corrected.containsKey("p50Millis"));
        assertTrue(corrected.containsKey("p99Millis"));
        assertTrue(corrected.containsKey("p999Millis"));
        assertTrue(((Number) corrected.get("syntheticSamplesAdded")).intValue() > 0,
            "an injected stall must have produced backfilled samples: " + corrected);
        assertEquals("milliseconds", d.get("resolution"));
        assertNotNull(d.get("expectedIntervalMillis"));
    }

    @Test
    @DisplayName("a normal window reports eventsLost:0 — the honesty fields are always present")
    void aNormalWindowReportsNoLoss() throws Exception {
        String sessionId = launchAndResume("com.example.debug.LatencySeamTarget");
        ObjectNode args = profileAction("latency_seam");
        args.put("sessionId", sessionId);
        args.put("className", "com.example.debug.LatencySeamTarget");
        args.put("method", "seam");
        args.put("durationSeconds", 4);
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        // The denominator is ALWAYS reported — a caller can always ask "did you see it all?".
        assertNotNull(d.get("eventsGenerated"), "got: " + d);
        assertNotNull(d.get("eventsObserved"));
        assertEquals(0L, ((Number) d.get("eventsLost")).longValue(),
            "a 5ms-gap seam is nowhere near the ring's rate — nothing should be lost: " + d);
        assertFalse(d.containsKey("percentilesReliable"),
            "with no loss, the percentiles are not flagged unreliable: " + d);
    }

    @Test
    @DisplayName("a seam that outruns the event ring REPORTS the loss — it does not hide it")
    void aHotSeamThatOverflowsTheRingReportsTheLoss() throws Exception {
        // Sprint-24 audit T1.5: the probe-event ring holds ~500 events; a seam firing
        // thousands/s overflows it, and v2.13.0 computed percentiles on whatever survived
        // with no hint that anything was dropped — and the loss BIASES the tail. This
        // fixture removes the planned gap so the seam fires flat-out. Any loss that still
        // occurs must be reported honestly (eventsLost > 0, percentilesReliable:false); if
        // the machine is slow enough that even flat-out stays under the ring rate, then
        // eventsLost is genuinely 0 and that is honest too — either way the fields are real.
        String sessionId = launchAndResume("com.example.debug.LatencySeamTarget",
            "-Djawata.latency.hot=true");
        ObjectNode args = profileAction("latency_seam");
        args.put("sessionId", sessionId);
        args.put("className", "com.example.debug.LatencySeamTarget");
        args.put("method", "seam");
        args.put("durationSeconds", 4);
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        long generated = ((Number) d.get("eventsGenerated")).longValue();
        long observed = ((Number) d.get("eventsObserved")).longValue();
        long lost = ((Number) d.get("eventsLost")).longValue();

        assertTrue(generated >= observed, "the true count cannot be less than what we saw: " + d);
        // Loss is measured from GAPS in the observed sequence numbers (maxSequence - observed),
        // not generated-minus-observed — that was the very conflation T1.5 fixed. The shortfall
        // (generated - observed) is an UPPER bound on loss: some of it is ring-drop (real loss,
        // shows as a gap) and the rest is post-window LATE events (the probe fires a beat after
        // the final drain, bumping eventsGenerated but never observed and never lost). So loss
        // is between zero and the shortfall, and zero is honest when the ring kept up.
        assertTrue(lost >= 0 && lost <= generated - observed,
            "eventsLost must be between 0 and the shortfall (the remainder are late, not lost): " + d);
        assertTrue(generated > 500,
            "a flat-out seam over 4s must fire well more than the ring's 500-event capacity, "
                + "so this test genuinely exercises the pressure it exists for: " + d);
        if (lost > 0) {
            assertEquals(Boolean.FALSE, d.get("percentilesReliable"),
                "when events were dropped, the percentiles must be flagged as not reliable: " + d);
            String steering = r.getMeta().getSteering();
            assertTrue(steering.contains("dropped") || steering.contains("budget"),
                "and the steering must SAY the tail is biased, not stay silent: " + steering);
        }
    }

    // ========================================================== JDT resolution + error handling

    @Test
    @DisplayName("an unknown seam is refused with a compiler-accurate reason, before the live JVM is touched")
    void unknownSeamIsRefusedByJdt() throws Exception {
        String sessionId = launchAndResume("com.example.debug.LatencySeamTarget");
        ObjectNode args = profileAction("latency_seam");
        args.put("sessionId", sessionId);
        args.put("className", "com.example.debug.LatencySeamTarget");
        args.put("method", "notAMethodOnThisClass");
        ToolResponse r = profile.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("SEAM_NOT_FOUND", r.getError().getCode());
    }

    @Test
    @DisplayName("a method that is never called in the window is an honest miss, not a fabricated zero")
    void neverCalledSeamReportsHonestMiss() throws Exception {
        // DebugTarget#multiply is only ever invoked via debug(action=evaluate) — never
        // from its own main loop. A short window should observe exactly zero calls.
        String sessionId = launchAndResume("com.example.debug.DebugTarget");
        ObjectNode args = profileAction("latency_seam");
        args.put("sessionId", sessionId);
        args.put("className", "com.example.debug.DebugTarget");
        args.put("method", "multiply");
        args.put("durationSeconds", 1);
        ToolResponse r = profile.execute(args);
        assertFalse(r.isSuccess(), "zero observed calls must be refused, not reported as a clean answer");
        assertEquals("NO_CALLS_OBSERVED", r.getError().getCode());
    }

    @Test
    @DisplayName("missing className or method is refused up front")
    void missingSeamParamsAreRefused() throws Exception {
        String sessionId = launchAndResume("com.example.debug.LatencySeamTarget");
        ObjectNode args = profileAction("latency_seam");
        args.put("sessionId", sessionId);
        ToolResponse r = profile.execute(args);
        assertFalse(r.isSuccess());
    }
}
