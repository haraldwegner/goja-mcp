package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.DevSimPreset;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D5, C7) — the runtime session spine. Discover, launch, attach,
 * report honestly, and — the part that decides whether this is safe to use at
 * all — always tear down.
 */
class DebugSessionSpineTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool tool;
    private ObjectMapper om;
    private Path targetClasses;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        tool = new DebugTool(() -> service, sessions);
        om = new ObjectMapper();

        // Compile the fixture with -g: without locals, a frame read sees nothing.
        targetClasses = Files.createTempDirectory("jawata-debug-target-");
        Path source = service.getProjectRoot()
            .resolve("src/main/java/com/example/debug/DebugTarget.java");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", targetClasses.toString(), source.toString());
        assertEquals(0, rc, "the debug fixture must compile");
    }

    @AfterEach
    void tearDown() {
        // Whatever a test did, no JVM of ours survives it.
        sessions.closeAll();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode action(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    private ToolResponse launchTarget() {
        ObjectNode args = action("launch");
        args.put("mainClass", "com.example.debug.DebugTarget");
        args.put("classpath", targetClasses.toString());
        return tool.execute(args);
    }

    @Test
    @DisplayName("launch under the preset: ALL SIX capabilities discovered, read from the JVM")
    void launchReportsEveryPresetCapability() {
        ToolResponse r = launchTarget();
        assertTrue(r.isSuccess(), "got: " + r.getError());

        Map<String, Object> session = data(r);
        assertNotNull(session.get("sessionId"));
        assertEquals("launched", session.get("origin"));
        assertEquals("live", session.get("state"));

        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) session.get("capabilities");
        for (String capability : DevSimPreset.CAPABILITIES) {
            assertTrue(capabilities.containsKey(capability),
                "the report must name every preset capability, missing: " + capability
                    + " in " + capabilities.keySet());
            assertEquals(Boolean.TRUE, capabilities.get(capability),
                "a preset-launched JVM has " + capability + ": " + capabilities);
        }
        assertEquals(Boolean.TRUE, capabilities.get("presetPrepared"));

        // And what the DEBUGGER can do here — asked of the VM, never assumed.
        assertTrue(capabilities.containsKey("canRedefineClasses"), "got: " + capabilities);
        assertTrue(capabilities.containsKey("canPopFrames"), "got: " + capabilities);
    }

    @Test
    @DisplayName("detach KILLS a JVM we launched — no orphan, nothing left behind")
    void detachTerminatesWhatWeLaunched() throws Exception {
        ToolResponse launched = launchTarget();
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        String sessionId = (String) data(launched).get("sessionId");
        long pid = ((Number) data(launched).get("pid")).longValue();
        assertTrue(ProcessHandle.of(pid).orElseThrow().isAlive(), "the target is running");

        ObjectNode args = action("detach");
        args.put("sessionId", sessionId);
        ToolResponse detached = tool.execute(args);
        assertTrue(detached.isSuccess(), "got: " + detached.getError());
        assertTrue(String.valueOf(data(detached).get("outcome")).contains("terminated"),
            "we launched it, so we kill it: " + data(detached));

        // The JVM is GONE — this is the whole safety claim.
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline
                && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
            Thread.sleep(200);
        }
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
            "a launched JVM must not survive its session: pid " + pid);
        assertEquals(0, sessions.size(), "and the session is forgotten");
    }

    @Test
    @DisplayName("a JVM started WITHOUT a debug agent is refused — and told why it can never work")
    void undebuggableJvmIsRefusedWithTheReason() throws Exception {
        // No debug flags. OpenJDK's JDWP agent has no attach entry point, so this JVM
        // can NEVER be debugged — not now, not later. Verified against the runtime:
        // "Agent_OnAttach is not available in jdwp". That is not a limitation to work
        // around; it is what makes this sprint's safety model structural. A production
        // JVM, started without the preset, is not debuggable AT ALL.
        Process plain = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", targetClasses.toString(), "com.example.debug.DebugTarget")
            .redirectErrorStream(true)
            .start();
        try {
            Thread.sleep(1500);
            assertTrue(plain.isAlive(), "the plain JVM is running");

            ObjectNode args = action("attach");
            args.put("pid", plain.pid());
            ToolResponse refused = tool.execute(args);

            assertFalse(refused.isSuccess(), "an unprepared JVM cannot be attached to");
            assertEquals("JVM_NOT_DEBUGGABLE", refused.getError().getCode(),
                "refused for the RIGHT reason, not an internal error: " + refused.getError());
            assertTrue(refused.getError().getMessage().contains("not started with a debug agent"),
                "says what is actually true: " + refused.getError().getMessage());
            assertTrue(String.valueOf(refused.getError().getHint()).contains("launch"),
                "and what to do instead: " + refused.getError().getHint());

            // discover says so UP FRONT, so nobody has to learn it by failing.
            ToolResponse discovered = tool.execute(action("discover"));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> jvms =
                (List<Map<String, Object>>) data(discovered).get("jvms");
            Map<String, Object> thisOne = jvms.stream()
                .filter(j -> ((Number) j.get("pid")).longValue() == plain.pid())
                .findFirst().orElseThrow(() -> new AssertionError("discover must list it"));
            assertEquals(Boolean.FALSE, thisOne.get("debuggable"),
                "flagged before you try: " + thisOne);
            assertNotNull(thisOne.get("why"), "with the reason: " + thisOne);
        } finally {
            plain.descendants().forEach(ProcessHandle::destroyForcibly);
            plain.destroyForcibly();
            plain.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("attach to a foreign PREPARED JVM — and detach leaves it running")
    void attachToPreparedForeignJvmAndReleaseWithoutKilling() throws Exception {
        // Someone else's JVM, but started debuggable. This is the ORB/JATS case: a
        // long-running sim, prepared at launch, that we attach to and let go of.
        Process foreign = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
            "-cp", targetClasses.toString(), "com.example.debug.DebugTarget")
            .redirectErrorStream(true)
            .start();
        try {
            Thread.sleep(2000);
            assertTrue(foreign.isAlive(), "the foreign JVM is running");

            ObjectNode args = action("attach");
            args.put("pid", foreign.pid());
            ToolResponse attached = tool.execute(args);
            assertTrue(attached.isSuccess(), "got: " + attached.getError());

            Map<String, Object> session = data(attached);
            assertEquals("attached", session.get("origin"));
            @SuppressWarnings("unchecked")
            Map<String, Object> capabilities = (Map<String, Object>) session.get("capabilities");
            assertEquals(Boolean.TRUE, capabilities.get("debug"),
                "we are talking to it over JDWP: " + capabilities);
            // It was NOT preset-prepared, and it says so rather than pretending.
            assertEquals(Boolean.FALSE, capabilities.get("presetPrepared"),
                "an honest report of a JVM we did not prepare: " + capabilities);

            ObjectNode detachArgs = action("detach");
            detachArgs.put("sessionId", (String) session.get("sessionId"));
            ToolResponse detached = tool.execute(detachArgs);
            assertTrue(String.valueOf(data(detached).get("outcome")).contains("released"),
                "it was never ours: " + data(detached));

            // IT KEEPS RUNNING. Killing a program we did not start would be the worst
            // kind of surprise.
            Thread.sleep(500);
            assertTrue(foreign.isAlive(), "detaching from a foreign JVM must NOT kill it");
        } finally {
            foreign.descendants().forEach(ProcessHandle::destroyForcibly);
            foreign.destroyForcibly();
            foreign.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("discover names the local JVMs, and never offers the debugger itself")
    void discoverExcludesOurselves() {
        ToolResponse r = tool.execute(action("discover"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jvms = (List<Map<String, Object>>) data(r).get("jvms");
        long self = ProcessHandle.current().pid();
        assertTrue(jvms.stream().noneMatch(j -> ((Number) j.get("pid")).longValue() == self),
            "offering to debug the debugger is a trap, not a feature: " + jvms);
    }

    @Test
    @DisplayName("status: one session by handle, or all of them")
    void statusByHandleAndAll() {
        String sessionId = (String) data(launchTarget()).get("sessionId");

        ObjectNode one = action("status");
        one.put("sessionId", sessionId);
        ToolResponse single = tool.execute(one);
        assertTrue(single.isSuccess());
        assertEquals(sessionId, data(single).get("sessionId"));

        ToolResponse all = tool.execute(action("status"));
        assertTrue(all.isSuccess());
        assertEquals(1, ((Number) data(all).get("count")).intValue(), "got: " + data(all));

        // An unknown handle is an honest miss, not a crash.
        ObjectNode unknown = action("status");
        unknown.put("sessionId", "dbg-nope");
        assertFalse(tool.execute(unknown).isSuccess());
    }
}
