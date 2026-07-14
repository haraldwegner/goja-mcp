package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D11, C16) — profiles that name symbols. A deliberately hot method
 * ranks #1, as a compiler-verified symbol, in a paginated summary; an on-demand
 * dump works mid-run; and a target without Flight Recorder gets an honest
 * capability-absent report, never a silently empty one.
 */
class HotspotTest {

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

        targetClasses = Files.createTempDirectory("jawata-hotspot-target-");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example/debug");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", targetClasses.toString(),
            pkg.resolve("DebugTarget.java").toString(),
            pkg.resolve("HotLoopTarget.java").toString());
        assertEquals(0, rc, "the hotspot fixtures must compile");
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

    private String launchAndResume(String mainClass) {
        ObjectNode launch = debugAction("launch");
        launch.put("mainClass", mainClass);
        launch.put("classpath", targetClasses.toString());
        ToolResponse launched = debug.execute(launch);
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        String sessionId = (String) data(launched).get("sessionId");

        ObjectNode resume = debugAction("resume");
        resume.put("sessionId", sessionId);
        assertTrue(debug.execute(resume).isSuccess());
        return sessionId;
    }

    // ========================================================== sample + hotspots

    @Test
    @DisplayName("the deliberately hot method is hotspot #1, symbol-named, paginated, with call counts")
    void hotMethodIsTopHotspot() throws Exception {
        String sessionId = launchAndResume("com.example.debug.HotLoopTarget");

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 3);
        ToolResponse sampled = profile.execute(sample);
        assertTrue(sampled.isSuccess(), "got: " + sampled.getError());
        String artifactId = (String) data(sampled).get("artifactId");
        assertNotNull(artifactId);
        assertTrue(((Number) data(sampled).get("bytes")).longValue() > 0,
            "a real JFR file was written");

        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", artifactId);
        hotspots.put("dimension", "cpu");
        hotspots.put("limit", 5);
        ToolResponse r = profile.execute(hotspots);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("rows");
        assertFalse(rows.isEmpty(), "at least one CPU sample must have been captured");

        Map<String, Object> top = rows.get(0);
        assertEquals(1, ((Number) top.get("rank")).intValue());
        assertEquals("com.example.debug.HotLoopTarget#burnCpu", top.get("symbol"),
            "the deliberately hot method, AS A SYMBOL, ranked #1: " + rows);
        assertTrue(((Number) top.get("samples")).longValue() > 0,
            "call counts (sample-based) must be present: " + top);
        assertTrue(((Number) d.get("totalSamples")).longValue() > 0);
    }

    @Test
    @DisplayName("hotspots pagination is honest: capped rows, TRUE totals reported alongside")
    void hotspotsPaginationIsHonest() throws Exception {
        String sessionId = launchAndResume("com.example.debug.HotLoopTarget");

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 3);
        String artifactId = (String) data(profile.execute(sample)).get("artifactId");

        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", artifactId);
        hotspots.put("dimension", "cpu");
        hotspots.put("limit", 1);
        ToolResponse r = profile.execute(hotspots);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("rows");
        assertEquals(1, rows.size(), "limit must actually cap the returned rows");
        assertNotNull(d.get("totalMethods"), "the TRUE method count must be reported regardless of the cap");
        assertNotNull(d.get("totalSamples"), "the TRUE sample count must be reported regardless of the cap");
    }

    @Test
    @DisplayName("gc dimension: a pause summary, not a fabricated per-method ranking")
    void gcDimensionReportsSummaryNotFakeMethods() throws Exception {
        String sessionId = launchAndResume("com.example.debug.HotLoopTarget");

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 2);
        String artifactId = (String) data(profile.execute(sample)).get("artifactId");

        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", artifactId);
        hotspots.put("dimension", "gc");
        ToolResponse r = profile.execute(hotspots);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        assertNotNull(d.get("pauseCount"), "got: " + d);
        assertNotNull(d.get("totalPauseMillis"), "got: " + d);
        assertNotNull(d.get("maxPauseMillis"), "got: " + d);
        assertFalse(d.containsKey("rows"), "GC has no per-method ranking to fabricate: " + d);
    }

    // ========================================================== jfr_dump (on-demand, mid-run)

    @Test
    @DisplayName("jfr_dump: the continuous recording is dumped ON DEMAND, mid-run")
    void jfrDumpMidRunSucceeds() throws Exception {
        // The dev/sim preset starts a CONTINUOUS recording ("jawata") on every launch —
        // no action=sample needed first.
        String sessionId = launchAndResume("com.example.debug.HotLoopTarget");
        Thread.sleep(1000); // let it accumulate something worth dumping

        ObjectNode dump = profileAction("jfr_dump");
        dump.put("sessionId", sessionId);
        ToolResponse r = profile.execute(dump);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        String artifactId = (String) d.get("artifactId");
        assertNotNull(artifactId);
        assertTrue(((Number) d.get("bytes")).longValue() > 0, "a real JFR file was dumped");

        // And it is USABLE — rank it, same as a targeted sample.
        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", artifactId);
        ToolResponse ranked = profile.execute(hotspots);
        assertTrue(ranked.isSuccess(), "a mid-run dump must be a valid JFR file: " + ranked.getError());
    }

    // ========================================================== capability honesty

    @Test
    @DisplayName("sample: honest capability-absent on a target with Flight Recorder disabled — never empty data")
    void sampleReportsFlightRecorderAbsentHonestly() throws Exception {
        Process foreign = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-XX:-FlightRecorder",
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
            "-cp", targetClasses.toString(), "com.example.debug.DebugTarget")
            .redirectErrorStream(true)
            .start();
        try {
            Thread.sleep(1500);
            assertTrue(foreign.isAlive());

            ObjectNode attach = debugAction("attach");
            attach.put("pid", foreign.pid());
            ToolResponse attached = debug.execute(attach);
            assertTrue(attached.isSuccess(), "got: " + attached.getError());
            String sessionId = (String) data(attached).get("sessionId");

            ObjectNode sample = profileAction("sample");
            sample.put("sessionId", sessionId);
            sample.put("durationSeconds", 1);
            ToolResponse r = profile.execute(sample);
            assertTrue(r.isSuccess(), "capability-absent is a SUCCESSFUL, honest answer: "
                + r.getError());
            Map<String, Object> d = data(r);
            assertEquals(Boolean.FALSE, d.get("enabled"), "got: " + d);
            assertNotNull(d.get("why"), "the absence must be EXPLAINED: " + d);
            assertTrue(String.valueOf(d.get("why")).contains("FlightRecorder"),
                "names the actual cause: " + d.get("why"));
        } finally {
            foreign.descendants().forEach(ProcessHandle::destroyForcibly);
            foreign.destroyForcibly();
            foreign.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("jfr_dump: honest capability-absent when no continuous recording exists (not preset-launched)")
    void jfrDumpAbsentWhenNoContinuousRecording() throws Exception {
        // A foreign JVM with JDWP but no -XX:StartFlightRecording — same shape as the
        // NMT-absent proof in ProfileFloorTest.
        Process foreign = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
            "-cp", targetClasses.toString(), "com.example.debug.DebugTarget")
            .redirectErrorStream(true)
            .start();
        try {
            Thread.sleep(1500);
            ObjectNode attach = debugAction("attach");
            attach.put("pid", foreign.pid());
            String sessionId = (String) data(debug.execute(attach)).get("sessionId");

            ObjectNode dump = profileAction("jfr_dump");
            dump.put("sessionId", sessionId);
            ToolResponse r = profile.execute(dump);
            assertTrue(r.isSuccess(), "capability-absent is a SUCCESSFUL, honest answer: "
                + r.getError());
            Map<String, Object> d = data(r);
            assertEquals(Boolean.FALSE, d.get("enabled"), "got: " + d);
            assertNotNull(d.get("why"), "got: " + d);
        } finally {
            foreign.descendants().forEach(ProcessHandle::destroyForcibly);
            foreign.destroyForcibly();
            foreign.waitFor(10, TimeUnit.SECONDS);
        }
    }

    // ========================================================== error handling

    @Test
    @DisplayName("hotspots on a non-JFR artifact is refused by name, not misread")
    void hotspotsRefusesWrongArtifactKind() throws Exception {
        String sessionId = launchAndResume("com.example.debug.HotLoopTarget");
        ToolResponse dumped = profile.execute(profileAction("heap_dump")
            .put("sessionId", sessionId));
        String heapArtifactId = (String) data(dumped).get("artifactId");

        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", heapArtifactId);
        ToolResponse r = profile.execute(hotspots);
        assertFalse(r.isSuccess());
        assertEquals("NOT_A_JFR_ARTIFACT", r.getError().getCode());
    }

    @Test
    @DisplayName("hotspots on an unknown artifactId is an honest miss")
    void hotspotsUnknownArtifactIsHonestMiss() {
        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", "jfr-sample-nope");
        ToolResponse r = profile.execute(hotspots);
        assertFalse(r.isSuccess());
    }
}
