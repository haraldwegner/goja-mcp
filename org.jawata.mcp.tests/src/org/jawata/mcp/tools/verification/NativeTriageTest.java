package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.runtime.profile.GdbAdapter;
import org.jawata.mcp.tools.ProfileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
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
 * Sprint 24 (D14, C19) — native boundary triage. No live session (the process
 * that crashed is gone) — these actions read an hs_err_pid<pid>.log file.
 *
 * <p>Grounded against REAL generated crashes, not hand-typed fixture text
 * (Sprint 24 Stage 19): {@code NativeCrashTarget} runs as a genuine
 * subprocess and genuinely SIGSEGVs via an out-of-bounds {@code Unsafe}
 * write, so the hs_err file this test parses is exactly what HotSpot itself
 * produces — including the real resolved native symbol
 * ({@code Unsafe_PutInt+0xa4} in {@code libjvm.so}) and the real Java frames
 * naming this class's own method.</p>
 */
class NativeTriageTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ProfileTool profile;
    private ObjectMapper om;
    private Path classesDir;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        profile = new ProfileTool(() -> service, new RuntimeSessionRegistry());
        om = new ObjectMapper();

        classesDir = Files.createTempDirectory("jawata-native-triage-classes-");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example/debug");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-d", classesDir.toString(),
            pkg.resolve("NativeCrashTarget.java").toString());
        assertEquals(0, rc, "the native-crash fixture must compile");
    }

    /** Launches NativeCrashTarget as a genuine subprocess and returns its hs_err file. */
    private Path crashAndCaptureHsErr(boolean nativeMemoryTracking) throws Exception {
        Path workDir = Files.createTempDirectory("jawata-native-triage-run-");
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-XX:ErrorFile=" + workDir.resolve("hs_err_pid%p.log"));
        if (nativeMemoryTracking) {
            command.add("-XX:NativeMemoryTracking=summary");
        }
        command.add("-cp");
        command.add(classesDir.toString());
        command.add("com.example.debug.NativeCrashTarget");

        Process process = new ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        assertTrue(finished, "the crash fixture must exit (it deliberately SIGSEGVs)");

        File[] hsErrFiles = workDir.toFile().listFiles((dir, name) -> name.startsWith("hs_err_pid"));
        assertNotNull(hsErrFiles, "no hs_err file appeared in " + workDir);
        assertEquals(1, hsErrFiles.length, "expected exactly one hs_err file: "
            + java.util.Arrays.toString(hsErrFiles));
        return hsErrFiles[0].toPath();
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

    // ========================================================== native_hs_err

    @Test
    @DisplayName("native_hs_err: a REAL crash parses with signal, problematic frame, and correlated Java+native frames")
    void hsErrParsesRealCrash() throws Exception {
        Path hsErrFile = crashAndCaptureHsErr(false);

        ObjectNode args = action("native_hs_err");
        args.put("hsErrFile", hsErrFile.toString());
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);

        assertEquals("SIGSEGV", result.get("signal"));
        assertNotNull(result.get("problematicFrame"));
        assertNotNull(result.get("jreVersion"));

        @SuppressWarnings("unchecked")
        Map<String, Object> thread = (Map<String, Object>) result.get("crashingThread");
        assertEquals(Boolean.TRUE, thread.get("javaThread"),
            "an Unsafe-triggered crash happens ON a JavaThread: " + thread);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> javaFrames = (List<Map<String, Object>>) result.get("javaFrames");
        assertTrue(javaFrames.stream().anyMatch(f ->
            "com.example.debug.NativeCrashTarget#triggerCrash".equals(f.get("symbol"))),
            "the crashing method's own name must be in the Java frames: " + javaFrames);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nativeFrames = (List<Map<String, Object>>) result.get("nativeFrames");
        assertTrue(nativeFrames.stream().anyMatch(f -> "vmInternal".equals(f.get("kind"))
            && f.get("resolvedSymbol") != null),
            "libjvm.so resolves its own symbols (e.g. Unsafe_PutInt): " + nativeFrames);
    }

    @Test
    @DisplayName("native_hs_err: missing hsErrFile parameter is refused up front")
    void hsErrMissingParameterIsRefused() {
        ToolResponse r = profile.execute(action("native_hs_err"));
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("native_hs_err: a non-existent path is an honest miss, not a crash")
    void hsErrNonexistentFileIsHonestMiss() {
        ObjectNode args = action("native_hs_err");
        args.put("hsErrFile", "/no/such/hs_err_pid1.log");
        ToolResponse r = profile.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("HSERR_FILE_NOT_FOUND", r.getError().getCode());
    }

    // ========================================================== native_nmt

    @Test
    @DisplayName("native_nmt: absent when the crashed JVM had no -XX:NativeMemoryTracking")
    void nmtAbsentWithoutFlag() throws Exception {
        Path hsErrFile = crashAndCaptureHsErr(false);
        ObjectNode args = action("native_nmt");
        args.put("hsErrFile", hsErrFile.toString());
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);
        assertEquals(Boolean.FALSE, result.get("enabled"));
        assertNotNull(result.get("why"));
    }

    @Test
    @DisplayName("native_nmt: present and categorized when the crashed JVM had NativeMemoryTracking on")
    void nmtPresentWithFlag() throws Exception {
        Path hsErrFile = crashAndCaptureHsErr(true);
        ObjectNode args = action("native_nmt");
        args.put("hsErrFile", hsErrFile.toString());
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);
        assertEquals(Boolean.TRUE, result.get("enabled"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories = (List<Map<String, Object>>) result.get("categories");
        assertFalse(categories.isEmpty(), "the embedded NMT section must yield categories: " + result);
    }

    // ========================================================== native_handoff

    @Test
    @DisplayName("native_handoff: honest capability-absent when no adapter is installed")
    void handoffHonestWhenAdapterAbsent() throws Exception {
        Path hsErrFile = crashAndCaptureHsErr(false);
        ObjectNode args = action("native_handoff");
        args.put("hsErrFile", hsErrFile.toString());
        args.put("adapterCommand", "jawata-nonexistent-debugger-xyz");
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);
        assertEquals(Boolean.FALSE, result.get("available"));
        assertNotNull(result.get("why"));
    }

    @Test
    @DisplayName("native_handoff: the default adapter (gdb) is honestly reported present or absent on THIS machine")
    void handoffDefaultAdapterReportedHonestly() throws Exception {
        Path hsErrFile = crashAndCaptureHsErr(false);
        ObjectNode args = action("native_handoff");
        args.put("hsErrFile", hsErrFile.toString());
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);
        boolean actuallyAvailable = GdbAdapter.isAvailable("gdb");
        assertEquals(actuallyAvailable, result.get("available"),
            "must match ground truth for this machine, not assume either way: " + result);
    }

    // ========================================================== GdbAdapter.parseBacktrace (pure, no gdb needed)

    @Test
    @DisplayName("GdbAdapter.parseBacktrace: the 'correlated evidence' path's parsing logic, proven without needing gdb installed")
    void parseBacktraceHandlesAStandardTranscript() {
        String transcript = """
            Thread 2 (Thread 0x7f8a1c000700 (LWP 5002)):
            #0  0x00007f8a2b400a10 in pthread_cond_wait () from /lib/x86_64-linux-gnu/libpthread.so.0
            #1  0x000055d1a2b30111 in Monitor::wait (this=0x55d1a3000000) at /build/openjdk/src/hotspot/share/runtime/mutex.cpp:210

            Thread 1 (Thread 0x7f8a2c1a5740 (LWP 5001)):
            #0  0x00007f8a2b3c4d5e in raise () from /lib/x86_64-linux-gnu/libc.so.6
            #1  0x00007f8a2b3a6a7f in abort () from /lib/x86_64-linux-gnu/libc.so.6
            #2  0x000055d1a2b3c4e6 in Unsafe_PutInt (env=0x7f8a1c0d5e30, unsafe=..., o=..., offset=2989, x=42) at /build/openjdk/src/hotspot/share/prims/unsafe.cpp:150
            """;

        List<Map<String, Object>> threads = GdbAdapter.parseBacktrace(transcript);
        assertEquals(2, threads.size());

        Map<String, Object> thread2 = threads.get(0);
        assertEquals(2, thread2.get("threadNum"));
        assertEquals(5002, thread2.get("lwp"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> thread2Frames = (List<Map<String, Object>>) thread2.get("frames");
        assertEquals(2, thread2Frames.size());
        assertEquals("pthread_cond_wait", thread2Frames.get(0).get("function"));
        assertEquals("/lib/x86_64-linux-gnu/libpthread.so.0", thread2Frames.get(0).get("library"),
            "a frame resolved only via the dynamic symbol table (no debug info) must still "
                + "carry its library, not be silently dropped");

        Map<String, Object> thread1 = threads.get(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> thread1Frames = (List<Map<String, Object>>) thread1.get("frames");
        assertEquals(3, thread1Frames.size());
        Map<String, Object> unsafeFrame = thread1Frames.get(2);
        assertEquals("Unsafe_PutInt", unsafeFrame.get("function"));
        assertEquals("/build/openjdk/src/hotspot/share/prims/unsafe.cpp", unsafeFrame.get("file"));
        assertEquals(150, unsafeFrame.get("line"));
    }
}
