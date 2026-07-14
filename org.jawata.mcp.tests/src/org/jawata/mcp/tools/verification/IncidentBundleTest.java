package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D13, C18) — one declared alarm, one local incident bundle. All seven
 * parts present, the summary names the alarming symbol the TARGET declared, and
 * a log-level change with an expiry reverts itself.
 */
class IncidentBundleTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool debug;
    private ProfileTool profile;
    private RuntimeArtifactStore artifacts;
    private ObjectMapper om;
    private Path targetClasses;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        artifacts = new RuntimeArtifactStore(Files.createTempDirectory("jawata-incident-artifacts-"));
        debug = new DebugTool(() -> service, sessions, artifacts);
        profile = new ProfileTool(() -> service, sessions, artifacts);
        om = new ObjectMapper();

        targetClasses = Files.createTempDirectory("jawata-incident-target-");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example/debug");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", targetClasses.toString(),
            pkg.resolve("DebugTarget.java").toString(),
            pkg.resolve("IncidentTarget.java").toString());
        assertEquals(0, rc, "the incident fixtures must compile");
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

    private String launchAndResume(String mainClass, List<String> programArgs) {
        ObjectNode launch = debugAction("launch");
        launch.put("mainClass", mainClass);
        launch.put("classpath", targetClasses.toString());
        ArrayNode argsArr = launch.putArray("args");
        programArgs.forEach(argsArr::add);
        ToolResponse launched = debug.execute(launch);
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        String sessionId = (String) data(launched).get("sessionId");

        ObjectNode resume = debugAction("resume");
        resume.put("sessionId", sessionId);
        assertTrue(debug.execute(resume).isSuccess());
        return sessionId;
    }

    // ========================================================== the core exit criterion

    @Test
    @DisplayName("all SEVEN bundle parts present, and the summary NAMES the alarming symbol")
    void bundleHasAllSevenPartsAndNamesTheAlarmingSymbol() throws Exception {
        Path logFile = targetClasses.resolve("app.log");
        Path alarmFile = targetClasses.resolve("alarm.json");

        String sessionId = launchAndResume("com.example.debug.IncidentTarget",
            List.of(logFile.toString(), alarmFile.toString()));

        ObjectNode arm = profileAction("incident_arm");
        arm.put("sessionId", sessionId);
        arm.put("alarmFile", alarmFile.toString());
        arm.put("logFile", logFile.toString());
        arm.put("jfrSliceSeconds", 5);
        ToolResponse armed = profile.execute(arm);
        assertTrue(armed.isSuccess(), "got: " + armed.getError());
        String incidentId = (String) data(armed).get("incidentId");
        assertNotNull(incidentId);

        // Poll until the alarm fires (IncidentTarget alarms at iteration 15, ~1.5s in)
        // and the bundle is captured — a few seconds is generous headroom.
        Map<String, Object> status = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            ObjectNode statusArgs = profileAction("incident_status");
            statusArgs.put("incidentId", incidentId);
            ToolResponse r = profile.execute(statusArgs);
            assertTrue(r.isSuccess(), "got: " + r.getError());
            status = data(r);
            if (Boolean.TRUE.equals(status.get("bundleReady"))) {
                break;
            }
            Thread.sleep(300);
        }
        assertNotNull(status);
        assertTrue(Boolean.TRUE.equals(status.get("bundleReady")), "bundle never became ready: " + status);

        ObjectNode getArgs = profileAction("incident_get");
        getArgs.put("incidentId", incidentId);
        ToolResponse gotten = profile.execute(getArgs);
        assertTrue(gotten.isSuccess(), "got: " + gotten.getError());
        Map<String, Object> bundle = data(gotten);

        assertEquals("com.example.debug.IncidentTarget#checkThreshold", bundle.get("alarmSymbol"),
            "the TARGET's own declared alarming symbol must be named: " + bundle);
        assertNotNull(bundle.get("alarmReason"));

        @SuppressWarnings("unchecked")
        List<String> present = (List<String>) bundle.get("partsPresent");
        assertTrue(present.contains("threadDump"), "got: " + present);
        assertTrue(present.contains("heapHistogram"), "got: " + present);
        assertTrue(present.contains("heapDump"), "got: " + present);
        assertTrue(present.contains("logSlice"), "got: " + present);
        assertTrue(present.contains("replayDescriptor"), "got: " + present);
        assertTrue(present.contains("jfrSlice"),
            "the dev/sim preset enables continuous JFR by default: " + present);
        assertEquals(6, present.size(), "all six PRESENT parts, plus the summary itself = 7: " + present);
        assertFalse(bundle.containsKey("partsAbsent"), "nothing should be missing here: " + bundle);

        String artifactId = (String) bundle.get("artifactId");
        Path dir = artifacts.root().resolve(artifactId);
        assertTrue(Files.exists(dir.resolve("threads.txt")));
        assertTrue(Files.exists(dir.resolve("histogram.json")));
        assertTrue(Files.exists(dir.resolve("heap.hprof")));
        assertTrue(Files.exists(dir.resolve("log-slice.txt")));
        assertTrue(Files.exists(dir.resolve("replay-descriptor.json")));
        assertTrue(Files.exists(dir.resolve("jfr-slice.jfr")));
        assertTrue(Files.exists(dir.resolve("summary.json")));
        assertTrue(Files.size(dir.resolve("heap.hprof")) > 0, "a real HPROF, not an empty placeholder");

        // A second status poll must NOT re-capture (idempotent) — same artifactId back.
        ObjectNode secondPoll = profileAction("incident_status");
        secondPoll.put("incidentId", incidentId);
        ToolResponse second = profile.execute(secondPoll);
        assertEquals(artifactId, data(second).get("artifactId"), "must not re-capture");
    }

    @Test
    @DisplayName("a NON-JSON alarm still yields a bundle — the fallback must not crash the capture it exists to save")
    void aNonJsonAlarmIsCapturedWithAnHonestReason() throws Exception {
        // Sprint-24 audit (2026-07-14): the catch block written to tolerate an unparseable
        // alarm file used Map.of(..., null, ...) — which THROWS on the null value. So a
        // target that declared its alarm as plain text permanently WEDGED its own incident:
        // every poll re-threw, the bundle was never captured, and the capture window (a live
        // process, at the moment it is in trouble) was lost. The whole point of an incident
        // bundle is that it survives a target behaving unexpectedly.
        Path logFile = targetClasses.resolve("app-nonjson.log");
        Path alarmFile = targetClasses.resolve("alarm-nonjson.txt");
        String sessionId = launchAndResume("com.example.debug.DebugTarget", List.of());

        ObjectNode arm = profileAction("incident_arm");
        arm.put("sessionId", sessionId);
        arm.put("alarmFile", alarmFile.toString());
        arm.put("logFile", logFile.toString());
        String incidentId = (String) data(profile.execute(arm)).get("incidentId");

        // The target declares its alarm — but not in JSON. It is allowed to do that.
        Files.writeString(alarmFile, "OVERLOAD: queue depth 40000\n");

        ObjectNode statusArgs = profileAction("incident_status");
        statusArgs.put("incidentId", incidentId);
        ToolResponse r = profile.execute(statusArgs);
        assertTrue(r.isSuccess(), "an unparseable alarm must NOT fail the capture: " + r.getError());
        Map<String, Object> status = data(r);
        assertEquals(Boolean.TRUE, status.get("fired"), "got: " + status);
        assertEquals(Boolean.TRUE, status.get("bundleReady"),
            "the bundle must still be captured — the process is in trouble NOW: " + status);

        ObjectNode getArgs = profileAction("incident_get");
        getArgs.put("incidentId", incidentId);
        Map<String, Object> bundle = data(profile.execute(getArgs));
        assertNull(bundle.get("alarmSymbol"),
            "no symbol was declared, so none is invented: " + bundle);
        assertTrue(String.valueOf(bundle.get("alarmReason")).contains("could not be parsed"),
            "and the reason says plainly why, quoting the target's own words: " + bundle);

        @SuppressWarnings("unchecked")
        List<String> present = (List<String>) bundle.get("partsPresent");
        assertTrue(present.contains("threadDump") && present.contains("heapDump"),
            "the evidence is captured regardless of how the alarm was phrased: " + present);
    }

    @Test
    @DisplayName("before the alarm fires: an honest 'not yet', never a fabricated bundle")
    void statusBeforeAlarmReportsNotFired() throws Exception {
        Path logFile = targetClasses.resolve("app2.log");
        Path alarmFile = targetClasses.resolve("alarm2.json"); // IncidentTarget has not run long enough to write it
        String sessionId = launchAndResume("com.example.debug.IncidentTarget",
            List.of(logFile.toString(), alarmFile.toString()));

        ObjectNode arm = profileAction("incident_arm");
        arm.put("sessionId", sessionId);
        arm.put("alarmFile", alarmFile.toString());
        ToolResponse armed = profile.execute(arm);
        String incidentId = (String) data(armed).get("incidentId");

        // Immediately — well before iteration 15 (~1.5s) can have happened.
        ObjectNode statusArgs = profileAction("incident_status");
        statusArgs.put("incidentId", incidentId);
        ToolResponse r = profile.execute(statusArgs);
        assertTrue(r.isSuccess());
        Map<String, Object> status = data(r);
        assertEquals(Boolean.FALSE, status.get("fired"));
        assertEquals(Boolean.FALSE, status.get("bundleReady"));

        ObjectNode getArgs = profileAction("incident_get");
        getArgs.put("incidentId", incidentId);
        ToolResponse gotten = profile.execute(getArgs);
        assertFalse(gotten.isSuccess(), "must refuse — there is no bundle yet");
        assertEquals("INCIDENT_NOT_FIRED", gotten.getError().getCode());
    }

    @Test
    @DisplayName("TWO pollers at the same instant capture exactly ONE bundle")
    void concurrentPollsCaptureExactlyOnce() throws Exception {
        // Sprint-24 audit: capturedSummary was a bare volatile with no lock, so two callers
        // arriving together both passed the null check and both captured — two full bundles,
        // TWO HEAP DUMPS against a JVM already in distress, and one artifactId orphaned when
        // the second overwrote the first. And this is not an exotic case: the tool's own
        // description tells you to hand the sessionId to a subagent for the watch, so a main
        // agent and its subagent polling the same incident is the ADVERTISED usage.
        Path alarmFile = targetClasses.resolve("alarm-concurrent.json");
        String sessionId = launchAndResume("com.example.debug.DebugTarget", List.of());

        ObjectNode arm = profileAction("incident_arm");
        arm.put("sessionId", sessionId);
        arm.put("alarmFile", alarmFile.toString());
        String incidentId = (String) data(profile.execute(arm)).get("incidentId");

        Files.writeString(alarmFile, "{\"symbol\":\"com.example.debug.DebugTarget#spin\","
            + "\"reason\":\"concurrent poll drill\"}");

        int pollers = 4;
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        java.util.List<java.util.concurrent.Future<ToolResponse>> results = new java.util.ArrayList<>();
        java.util.concurrent.ExecutorService pool =
            java.util.concurrent.Executors.newFixedThreadPool(pollers);
        try {
            for (int i = 0; i < pollers; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    ObjectNode statusArgs = profileAction("incident_status");
                    statusArgs.put("incidentId", incidentId);
                    return profile.execute(statusArgs);
                }));
            }
            go.countDown();

            java.util.Set<Object> artifactIds = new java.util.HashSet<>();
            for (java.util.concurrent.Future<ToolResponse> f : results) {
                ToolResponse r = f.get(60, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(r.isSuccess(), "got: " + r.getError());
                artifactIds.add(data(r).get("artifactId"));
            }
            assertEquals(1, artifactIds.size(),
                "every poller must be handed the SAME one bundle: " + artifactIds);
        } finally {
            pool.shutdownNow();
        }

        try (var dirs = Files.list(artifacts.root())) {
            long bundles = dirs.filter(d -> d.getFileName().toString().startsWith("incident-")).count();
            assertEquals(1, bundles, "and exactly one bundle exists on disk — not four");
        }
    }

    @Test
    @DisplayName("a FAILED capture leaves nothing behind — no invisible, unprunable directory")
    void aFailedCaptureLeavesNoArtifactBehind() throws Exception {
        // Sprint-24 audit: only the JFR part was failure-guarded. A jcmd failure at the
        // thread dump / histogram / heap dump aborted the capture with the artifact dir
        // already created and its manifest NOT yet written — so it was invisible to
        // `artifacts` (no manifest = not evidence), undeletable by id (the caller never
        // learned the id), and never expired (expiry lives in the manifest). One leak per
        // failed poll, each potentially holding a partial multi-GB heap dump.
        Path alarmFile = targetClasses.resolve("alarm-dead-target.json");
        String sessionId = launchAndResume("com.example.debug.DebugTarget", List.of());
        long pid = ((Number) data(debug.execute(
            debugAction("status").put("sessionId", sessionId))).get("pid")).longValue();

        ObjectNode arm = profileAction("incident_arm");
        arm.put("sessionId", sessionId);
        arm.put("alarmFile", alarmFile.toString());
        String incidentId = (String) data(profile.execute(arm)).get("incidentId");

        // The target dies — and only THEN does the alarm file appear. jcmd will fail.
        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        Thread.sleep(1500);
        Files.writeString(alarmFile, "{\"symbol\":\"com.example.debug.DebugTarget#spin\"}");

        ObjectNode statusArgs = profileAction("incident_status");
        statusArgs.put("incidentId", incidentId);
        ToolResponse r = profile.execute(statusArgs);
        assertFalse(r.isSuccess(), "a capture against a dead target must FAIL, loudly: " + r.getData());

        try (var dirs = Files.list(artifacts.root())) {
            assertEquals(0, dirs.filter(d -> d.getFileName().toString().startsWith("incident-")).count(),
                "and it must leave NO directory behind — not even an invisible one");
        }
    }

    @Test
    @DisplayName("the armed-incident bound is REAL: refuse rather than grow without limit")
    void theArmedBoundIsEnforced() throws Exception {
        // Sprint-24 audit: eviction only ever removed an ALREADY-CAPTURED incident, and then
        // put() ran unconditionally — so with 50 armed watchers that had not fired (exactly
        // the state a bound exists for) the map grew to 51, 52, … The "bounded, same
        // discipline as sessions" claim was decorative.
        String sessionId = launchAndResume("com.example.debug.DebugTarget", List.of());

        ToolResponse last = null;
        for (int i = 0; i < 60; i++) {
            ObjectNode arm = profileAction("incident_arm");
            arm.put("sessionId", sessionId);
            arm.put("alarmFile", targetClasses.resolve("never-fires-" + i + ".json").toString());
            last = profile.execute(arm);
            if (!last.isSuccess()) {
                break;
            }
        }
        assertNotNull(last);
        assertFalse(last.isSuccess(),
            "arming past the bound must be REFUSED, not silently accepted: " + last.getData());
        assertEquals("TOO_MANY_ARMED_INCIDENTS", last.getError().getCode());
        assertTrue(last.getError().getHint().contains("incident_disarm"),
            "and the refusal must be ACTIONABLE — say how to free a slot, or it is a dead "
                + "end: " + last.getError().getHint());

        // Disarming frees a slot — the refusal is actionable, not a dead end.
        ObjectNode disarm = profileAction("incident_disarm");
        disarm.put("incidentId", "incident-does-not-exist");
        assertFalse(profile.execute(disarm).isSuccess(), "disarming nothing is an honest miss");
    }

    @Test
    @DisplayName("an unknown incidentId is an honest miss")
    void unknownIncidentIsHonestMiss() {
        ObjectNode statusArgs = profileAction("incident_status");
        statusArgs.put("incidentId", "incident-nope");
        assertFalse(profile.execute(statusArgs).isSuccess());
    }

    @Test
    @DisplayName("incident_arm without alarmFile is refused up front")
    void missingAlarmFileIsRefused() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget", List.of());
        ObjectNode arm = profileAction("incident_arm");
        arm.put("sessionId", sessionId);
        ToolResponse r = profile.execute(arm);
        assertFalse(r.isSuccess());
    }

    // ========================================================== jmx_read + log_level(expiry)

    @Test
    @DisplayName("jmx_read: a scalar attribute and a CompositeData attribute, both structured")
    void jmxReadReturnsStructuredValues() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget", List.of());

        ObjectNode scalarArgs = profileAction("jmx_read");
        scalarArgs.put("sessionId", sessionId);
        scalarArgs.put("objectName", "java.lang:type=Runtime");
        scalarArgs.put("attribute", "Uptime");
        ToolResponse scalarR = profile.execute(scalarArgs);
        assertTrue(scalarR.isSuccess(), "got: " + scalarR.getError());
        assertTrue(((Number) data(scalarR).get("value")).longValue() > 0);

        ObjectNode compositeArgs = profileAction("jmx_read");
        compositeArgs.put("sessionId", sessionId);
        compositeArgs.put("objectName", "java.lang:type=Memory");
        compositeArgs.put("attribute", "HeapMemoryUsage");
        ToolResponse compositeR = profile.execute(compositeArgs);
        assertTrue(compositeR.isSuccess(), "got: " + compositeR.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> heap = (Map<String, Object>) data(compositeR).get("value");
        assertTrue(heap.containsKey("used"), "CompositeData flattened into fields: " + heap);
        assertTrue(heap.containsKey("committed"), "got: " + heap);
    }

    @Test
    @DisplayName("jmx_read: an unknown MBean is refused by name")
    void jmxReadUnknownMBeanIsRefused() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget", List.of());
        ObjectNode args = profileAction("jmx_read");
        args.put("sessionId", sessionId);
        args.put("objectName", "com.example:type=NoSuchThing");
        args.put("attribute", "Whatever");
        ToolResponse r = profile.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("MBEAN_NOT_FOUND", r.getError().getCode());
    }

    @Test
    @DisplayName("log_level with expirySeconds: the level REVERTS automatically")
    void logLevelExpiryReverts() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget", List.of());

        ObjectNode first = profileAction("log_level");
        first.put("sessionId", sessionId);
        first.put("level", "FINE");
        first.put("expirySeconds", 2);
        ToolResponse firstR = profile.execute(first);
        assertTrue(firstR.isSuccess(), "got: " + firstR.getError());
        Map<String, Object> firstData = data(firstR);
        String baseline = (String) firstData.get("previousLevel");
        assertEquals("FINE", firstData.get("newLevel"));

        // Wait past the expiry with margin, then observe the revert via a SECOND
        // call's own reported previousLevel — if the background revert worked, this
        // reads BACK to `baseline`; if it silently failed, it would still read "FINE".
        Thread.sleep(3_500);

        ObjectNode second = profileAction("log_level");
        second.put("sessionId", sessionId);
        second.put("level", "FINE");
        ToolResponse secondR = profile.execute(second);
        assertTrue(secondR.isSuccess(), "got: " + secondR.getError());
        assertEquals(baseline, data(secondR).get("previousLevel"),
            "the expiry must have reverted the level BACK to baseline by now");
        assertNotEquals("FINE", baseline, "sanity: the baseline must differ from what we set, "
            + "or this test cannot tell a revert from a no-op");
    }

    @Test
    @DisplayName("STACKED log_level calls still revert to the ORIGINAL level, not to an intermediate one")
    void stackedLogLevelCallsRevertToTheTrueBaseline() throws Exception {
        // Sprint-24 audit: each call captured "the level as it is right now" as its revert
        // target and fired an independent timer. So INFO --(t0)--> FINE --(t0+0.5s)--> FINEST
        // reverted to INFO at t0+expiry and then BACK TO FINE at t0+0.5s+expiry — the second
        // reverter restoring the FIRST call's temporary level. The target was left verbose
        // indefinitely, by the very mechanism whose promise is "a diagnostic verbosity bump
        // cannot outlive the reason you set it".
        String sessionId = launchAndResume("com.example.debug.DebugTarget", List.of());

        ObjectNode first = profileAction("log_level");
        first.put("sessionId", sessionId);
        first.put("level", "FINE");
        first.put("expirySeconds", 2);
        ToolResponse firstR = profile.execute(first);
        assertTrue(firstR.isSuccess(), "got: " + firstR.getError());
        String baseline = (String) data(firstR).get("previousLevel");
        assertEquals(baseline, data(firstR).get("revertsTo"), "got: " + data(firstR));

        Thread.sleep(500);

        // A second bump while the first is still pending — its `previousLevel` is FINE, the
        // temporary level, NOT the baseline. The revert target must not follow it down.
        ObjectNode second = profileAction("log_level");
        second.put("sessionId", sessionId);
        second.put("level", "FINEST");
        second.put("expirySeconds", 2);
        ToolResponse secondR = profile.execute(second);
        assertTrue(secondR.isSuccess(), "got: " + secondR.getError());
        assertEquals("FINE", data(secondR).get("previousLevel"),
            "sanity: the second call really does observe the first call's temporary level");
        assertEquals(baseline, data(secondR).get("revertsTo"),
            "but it must revert to the TRUE baseline, not to FINE: " + data(secondR));

        Thread.sleep(4_000);   // well past both expiries

        ObjectNode probe = profileAction("log_level");
        probe.put("sessionId", sessionId);
        probe.put("level", "INFO");
        ToolResponse probeR = profile.execute(probe);
        assertTrue(probeR.isSuccess(), "got: " + probeR.getError());
        assertEquals(baseline, data(probeR).get("previousLevel"),
            "after BOTH expiries the logger is back where it started — not stranded at an "
                + "intermediate level by one reverter racing another");
    }

    @Test
    @DisplayName("log_level without expirySeconds: permanent, stated as such")
    void logLevelWithoutExpiryIsPermanent() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget", List.of());
        ObjectNode args = profileAction("log_level");
        args.put("sessionId", sessionId);
        args.put("level", "WARNING");
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(data(r).containsKey("expirySeconds"));
    }
}
