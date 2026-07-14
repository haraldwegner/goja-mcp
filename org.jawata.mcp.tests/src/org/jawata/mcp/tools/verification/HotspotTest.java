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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
            pkg.resolve("HotLoopTarget.java").toString(),
            pkg.resolve("WallTimeTarget.java").toString(),
            pkg.resolve("LatencySeamTarget.java").toString(),
            pkg.resolve("DomainEventTarget.java").toString());
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

    @Test
    @DisplayName("wall dimension: a method that BLOCKS ranks above one that only burns CPU")
    void wallDimensionRanksBlockingWallTimeNotJustCpu() throws Exception {
        // Sprint-24 audit T2.1: the spec lists wall time as a ranking dimension beside
        // cpu/alloc/lock/gc; v2.13.0 shipped the enum WITHOUT it, so dimension=wall was an
        // "unknown dimension" error. WallTimeTarget spends most of its ELAPSED time blocked
        // on a contended monitor in waitOnLock() and only a little CPU in burnCpu(). A CPU
        // profile points at burnCpu; a genuine WALL profile must point at waitOnLock — that
        // is where the wall clock goes, and it is invisible to CPU sampling (a blocked thread
        // is not on the CPU).
        String sessionId = launchAndResume("com.example.debug.WallTimeTarget");

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 5);
        String artifactId = (String) data(profile.execute(sample)).get("artifactId");

        ObjectNode wall = profileAction("hotspots");
        wall.put("artifactId", artifactId);
        wall.put("dimension", "wall");
        wall.put("limit", 20);
        ToolResponse r = profile.execute(wall);
        assertTrue(r.isSuccess(), "dimension=wall must be a real ranking, not 'unknown': " + r.getError());
        Map<String, Object> d = data(r);

        assertEquals("wall", d.get("dimension"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> wallRows = (List<Map<String, Object>>) d.get("rows");
        assertFalse(wallRows.isEmpty(), "a running program has wall-time hotspots: " + d);

        // Every row is ranked by ELAPSED milliseconds, not a sample count.
        for (Map<String, Object> row : wallRows) {
            assertNotNull(row.get("wallMillis"), "wall rows are ranked by milliseconds: " + row);
            assertTrue(row.get("symbol").toString().contains("#"), "symbol-named: " + row);
        }
        assertNotNull(d.get("totalWallMillis"));
        assertNotNull(d.get("samplingIntervalMillis"), "the measured on-CPU interval is disclosed");
        assertTrue(((Number) d.get("blockingEvents")).longValue() > 0,
            "the blocking wall time was actually captured, not just CPU samples: " + d);

        // THE PROOF that wall != CPU, made with the tool's OWN two outputs on the SAME
        // recording. burnCpu is a top CPU hotspot; but the #1 WALL method is where the program
        // spends its elapsed time BLOCKED — a place a blocked thread's monitor-wait parks,
        // which CPU sampling never sees (a blocked thread is not on the CPU) and which is NOT
        // the CPU-bound burnCpu.
        ObjectNode cpu = profileAction("hotspots");
        cpu.put("artifactId", artifactId);
        cpu.put("dimension", "cpu");
        cpu.put("limit", 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cpuRows =
            (List<Map<String, Object>>) data(profile.execute(cpu)).get("rows");

        int burnCpuRankInCpu = rankOf(cpuRows, "com.example.debug.WallTimeTarget#burnCpu");
        assertTrue(burnCpuRankInCpu > 0 && burnCpuRankInCpu <= 3,
            "burnCpu is a top CPU hotspot (that is where the CPU goes): " + cpuRows);

        String topWallSymbol = wallRows.get(0).get("symbol").toString();
        assertNotEquals("com.example.debug.WallTimeTarget#burnCpu", topWallSymbol,
            "the #1 WALL method must be where the program BLOCKS, not where it burns CPU — "
                + "that is the whole difference between wall and cpu: " + wallRows);
        assertTrue(topWallSymbol.contains("wait") || topWallSymbol.contains("Wait")
                || topWallSymbol.contains("park") || topWallSymbol.contains("Lock"),
            "and that #1 wall method is a blocking/waiting one: " + wallRows);
    }

    @Test
    @DisplayName("call_counts: REAL per-method invocation counts, not relabeled sample counts")
    void callCountsCountsActualCalls() throws Exception {
        // Sprint-24 audit T2.2: D11 asks for call counts; v2.13.0 relabeled a hotspot's
        // top-of-stack SAMPLE count as if it were one. LatencySeamTarget calls a cheap seam()
        // ~200×/s — so over 3s the REAL call count is in the hundreds, while a CPU sample
        // count of that same cheap, mostly-sleeping method would be a handful. This proves
        // call_counts counts CALLS, not stack-top samples.
        String sessionId = launchAndResume("com.example.debug.LatencySeamTarget");

        ObjectNode args = profileAction("call_counts");
        args.put("sessionId", sessionId);
        args.put("className", "com.example.debug.LatencySeamTarget");
        args.put("durationSeconds", 3);
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "call_counts must be a real action, not unknown: " + r.getError());
        Map<String, Object> d = data(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("rows");
        assertFalse(rows.isEmpty(), "a running program calls methods: " + d);

        long seamCalls = rows.stream()
            .filter(row -> "com.example.debug.LatencySeamTarget#seam".equals(row.get("symbol")))
            .mapToLong(row -> ((Number) row.get("calls")).longValue())
            .findFirst().orElse(0);
        assertTrue(seamCalls > 50,
            "seam() is called ~200x/s — a REAL call count over 3s is in the hundreds, where a "
                + "CPU-sample count of this cheap method would be a handful: got " + seamCalls
                + " from " + rows);

        for (Map<String, Object> row : rows) {
            assertTrue(row.get("symbol").toString().startsWith("com.example.debug.LatencySeamTarget#"),
                "every row is a symbol-named method of the class: " + row);
            assertNotNull(row.get("calls"), "…ranked by real call count: " + row);
        }
        assertNotNull(d.get("totalCalls"));
    }

    @Test
    @DisplayName("domain_events: the TARGET's own JFR events are surfaced, with their fields")
    void domainEventsSurfacesTheApplicationsOwnEvents() throws Exception {
        // Sprint-24 audit T2.3: D12 asks for "the target's own domain events where it emits
        // them"; v2.13.0 surfaced only the JVM's built-in jdk.* events. DomainEventTarget
        // commits its own OrderPlaced JFR event ~50x/s. sample() records into whatever JFR
        // recording is running, so those events land in the same recording jawata profiles,
        // and domain_events must read them back out.
        String sessionId = launchAndResume("com.example.debug.DomainEventTarget");

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 3);
        ToolResponse sampled = profile.execute(sample);
        assertTrue(sampled.isSuccess(), "got: " + sampled.getError());
        String artifactId = (String) data(sampled).get("artifactId");

        ObjectNode domain = profileAction("domain_events");
        domain.put("artifactId", artifactId);
        ToolResponse r = profile.execute(domain);
        assertTrue(r.isSuccess(), "domain_events must be a real action: " + r.getError());
        Map<String, Object> d = data(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) d.get("domainEventTypes");
        assertFalse(types.isEmpty(), "the target's own events must be surfaced: " + d);

        Map<String, Object> order = types.stream()
            .filter(t -> "com.example.debug.OrderPlaced".equals(t.get("eventType")))
            .findFirst().orElse(null);
        assertNotNull(order, "the application's OrderPlaced event, by its own name: " + types);
        assertTrue(((Number) order.get("count")).longValue() > 0, "with a real count: " + order);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) order.get("recent");
        assertFalse(recent.isEmpty(), "and a sample of the events themselves: " + order);
        Map<String, Object> one = recent.get(0);
        assertTrue(one.containsKey("symbol") && one.containsKey("quantity"),
            "carrying the event's OWN declared fields — the target's domain: " + one);
    }

    private static int rankOf(List<Map<String, Object>> rows, String symbol) {
        for (Map<String, Object> row : rows) {
            if (symbol.equals(row.get("symbol"))) {
                return ((Number) row.get("rank")).intValue();
            }
        }
        return -1;
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
