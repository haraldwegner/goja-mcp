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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D7, C9) — hypothesis testing: change the running program and see what it
 * does.
 *
 * <p>Each mutation is proven by its EFFECT on the live program, never by the tool's own
 * report of success. "set_value returned ok" proves nothing; the program afterwards
 * computing a different number proves it.</p>
 *
 * <p>And the thing that makes this safe to trust: the session records every change it
 * made. A debugged program is no longer the program you started, and a finding drawn
 * after a mutation is a finding about a program WE edited.</p>
 */
class DebugMutateTest {

    private static final String TARGET = "com.example.debug.DebugTarget";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool tool;
    private ObjectMapper om;
    private Path targetClasses;
    private Path source;
    private List<String> lines;
    private String sessionId;
    private long mainThread;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        tool = new DebugTool(() -> service, sessions);
        om = new ObjectMapper();

        source = service.getProjectRoot()
            .resolve("src/main/java/com/example/debug/DebugTarget.java");
        lines = Files.readAllLines(source);

        targetClasses = Files.createTempDirectory("jawata-debug-mutate-");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", targetClasses.toString(), source.toString()));

        ObjectNode launch = action("launch");
        launch.put("mainClass", TARGET);
        launch.put("classpath", targetClasses.toString());
        ToolResponse launched = tool.execute(launch);
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        sessionId = (String) data(launched).get("sessionId");

        mainThread = runToTopOfMain();
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

    private int lineOf(String snippet) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(snippet)) {
                return i + 1;
            }
        }
        throw new AssertionError("the fixture no longer contains: " + snippet);
    }

    private Map<String, Object> ok(ObjectNode args) {
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), args.get("action").asText() + " failed: " + r.getError());
        return data(r);
    }

    private Map<String, Object> awaitHit() {
        ObjectNode args = onSession("wait");
        args.put("timeoutMillis", 30_000);
        Map<String, Object> hit = ok(args);
        assertEquals(Boolean.TRUE, hit.get("hit"), "nothing hit within 30s: " + hit);
        return hit;
    }

    private long runToTopOfMain() {
        ObjectNode bootstrap = onSession("breakpoint_set");
        bootstrap.put("kind", "line");
        bootstrap.put("className", TARGET);
        bootstrap.put("line", lineOf("Node head = graph;"));
        String id = (String) ok(bootstrap).get("breakpointId");

        assertTrue(tool.execute(onSession("resume")).isSuccess());
        Map<String, Object> hit = awaitHit();

        ObjectNode clear = onSession("breakpoint_clear");
        clear.put("breakpointId", id);
        assertTrue(tool.execute(clear).isSuccess());
        return ((Number) hit.get("threadId")).longValue();
    }

    private Map<String, Object> armAndHit(String kind, Map<String, Object> extra) {
        ObjectNode args = onSession("breakpoint_set");
        args.put("kind", kind);
        args.put("className", TARGET);
        extra.forEach((key, value) -> {
            if (value instanceof Integer number) {
                args.put(key, number);
            } else {
                args.put(key, String.valueOf(value));
            }
        });
        assertEquals(Boolean.TRUE, ok(args).get("bound"));

        ObjectNode go = onSession("resume");
        go.put("threadId", mainThread);
        assertTrue(tool.execute(go).isSuccess());
        return awaitHit();
    }

    private Object evaluate(long threadId, String expression) {
        ObjectNode args = onSession("evaluate");
        args.put("threadId", threadId);
        args.put("expression", expression);
        return ok(args).get("summary");
    }

    // ------------------------------------------------------- the mutations

    @Test
    @DisplayName("set_value on a LOCAL: the program computes a different answer afterwards")
    void setValueOnALocalChangesWhatTheProgramComputes() {
        Map<String, Object> hit = armAndHit("line",
            Map.of("line", lineOf("int doubled = iteration * 2;")));
        long threadId = ((Number) hit.get("threadId")).longValue();

        int before = ((Number) evaluate(threadId, "iteration")).intValue();

        ObjectNode set = onSession("set_value");
        set.put("threadId", threadId);
        set.put("name", "iteration");
        set.put("expression", "1000");
        Map<String, Object> mutation = ok(set);
        assertEquals("local", mutation.get("target"), "got: " + mutation);
        assertEquals(String.valueOf(before), mutation.get("from"), "it says what it replaced");
        assertEquals("1000", mutation.get("to"));

        // THE PROOF: not that the call succeeded, but that the PROGRAM now believes it.
        assertEquals(1000, ((Number) evaluate(threadId, "iteration")).intValue());
        assertEquals(2007, ((Number) evaluate(threadId, "iteration * 2 + offset()")).intValue(),
            "the live program computes from the value WE put there");
    }

    @Test
    @DisplayName("set_value on a static FIELD: the program takes a branch it would not have taken")
    void setValueOnAFieldChangesTheProgramsBehaviour() {
        Map<String, Object> hit = armAndHit("line",
            Map.of("line", lineOf("int echoed = echo();")));
        long threadId = ((Number) hit.get("threadId")).longValue();

        assertEquals(false, evaluate(threadId, "tripped"), "the fixture starts untripped");

        ObjectNode set = onSession("set_value");
        set.put("threadId", threadId);
        set.put("name", "tripped");
        set.put("expression", "true");
        Map<String, Object> mutation = ok(set);
        assertEquals("field", mutation.get("target"), "got: " + mutation);
        assertEquals("false", mutation.get("from"));

        // The branch `if (tripped)` was dead. It is not dead any more — the program will now
        // execute a path it would never have reached on its own.
        assertEquals(true, evaluate(threadId, "tripped"));
    }

    @Test
    @DisplayName("force_return: the method returns OUR value and its remaining work never runs")
    void forceReturnMakesTheMethodReturnWhatWeSay() {
        // Stop on entry to computeSignal, before it has computed anything.
        Map<String, Object> hit = armAndHit("method", Map.of("method", "computeSignal"));
        long threadId = ((Number) hit.get("threadId")).longValue();
        assertEquals("computeSignal", hit.get("method"));

        ObjectNode forced = onSession("force_return");
        forced.put("threadId", threadId);
        forced.put("expression", "999");
        Map<String, Object> mutation = ok(forced);
        assertEquals("computeSignal", mutation.get("method"), "got: " + mutation);
        assertEquals("999", mutation.get("returned"));

        // The JVM has popped the frame, but JDI does not rebuild its cached stack until the
        // thread moves. Reading it now would hand back the method we just abandoned — so it
        // is REFUSED rather than answered with something that is no longer true.
        ObjectNode stale = onSession("snapshot");
        stale.put("threadId", threadId);
        ToolResponse refused = tool.execute(stale);
        assertFalse(refused.isSuccess(), "a stack that no longer exists must not be served");
        assertEquals("STACK_STALE_AFTER_FORCE_RETURN", refused.getError().getCode(),
            "got: " + refused.getError());

        // Step once: the thread moves, the stack becomes real, and we land in the caller.
        ObjectNode step = onSession("step");
        step.put("threadId", threadId);
        step.put("mode", "over");
        assertTrue(tool.execute(step).isSuccess());
        Map<String, Object> landed = awaitHit();
        assertEquals("main", landed.get("method"),
            "the forced return really did drop us into the caller: " + landed);

        // One more step, so main actually completes the assignment it was in the middle of.
        long there = ((Number) landed.get("threadId")).longValue();
        ObjectNode again = onSession("step");
        again.put("threadId", there);
        again.put("mode", "over");
        assertTrue(tool.execute(again).isSuccess());
        awaitHit();

        // THE PROOF: what the CALLER believes — not what our own call reported. main's local
        // now holds 999, a number computeSignal never computed (it would have been 7).
        assertEquals(999, ((Number) evaluate(there, "signal")).intValue(),
            "main received the value WE returned, not one the method produced");
    }

    @Test
    @DisplayName("pop_frame: the thread goes back to the call site and calls it again")
    void popFrameRewindsToTheCallSite() {
        Map<String, Object> hit = armAndHit("method", Map.of("method", "offset"));
        long threadId = ((Number) hit.get("threadId")).longValue();
        assertEquals("offset", hit.get("method"));

        // Two frames deep: offset() called from computeSignal().
        assertEquals("computeSignal", stackAt(threadId, 1),
            "offset() is called by computeSignal()");

        ObjectNode pop = onSession("pop_frame");
        pop.put("threadId", threadId);
        Map<String, Object> mutation = ok(pop);
        assertEquals("offset", mutation.get("popped"), "got: " + mutation);
        assertEquals("computeSignal", mutation.get("nowIn"));

        // The thread is back in computeSignal, ABOUT TO CALL offset() again — so resuming
        // hits the same method breakpoint a second time, from the same call site.
        assertEquals("computeSignal", frameOf(snapshot(threadId)).get("method"));

        ObjectNode go = onSession("resume");
        go.put("threadId", threadId);
        assertTrue(tool.execute(go).isSuccess());

        Map<String, Object> again = awaitHit();
        assertEquals("offset", again.get("method"),
            "it really did call it again — that is what 'try that again' means: " + again);
    }

    @Test
    @DisplayName("redefine: new method bodies take effect on the next call")
    void redefineReplacesTheCode() throws Exception {
        // offset() returns 7. Make a version that returns 42 — a BODY change, which is all
        // HotSpot allows.
        Path patched = Files.createTempDirectory("jawata-debug-redefine-");
        Path patchedSource = patched.resolve("DebugTarget.java");
        Files.createDirectories(patchedSource.getParent());
        Files.writeString(patchedSource,
            Files.readString(source).replace("return 7;", "return 42;"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", patched.toString(), patchedSource.toString()),
            "the patched fixture must compile");

        Map<String, Object> hit = armAndHit("line",
            Map.of("line", lineOf("int doubled = iteration * 2;")));
        long threadId = ((Number) hit.get("threadId")).longValue();
        assertEquals(7, ((Number) evaluate(threadId, "offset()")).intValue(),
            "the running program still has the old code");

        ObjectNode redefine = onSession("redefine");
        redefine.put("className", TARGET);
        redefine.put("classFile",
            patched.resolve("com/example/debug/DebugTarget.class").toString());
        Map<String, Object> mutation = ok(redefine);
        assertEquals(TARGET, mutation.get("class"), "got: " + mutation);
        assertTrue(((Number) mutation.get("breakpointsReinstalled")).intValue() >= 1,
            "our breakpoints must be re-armed against the NEW class, or they silently stop "
                + "firing — which looks exactly like the bug going away: " + mutation);

        // THE PROOF: the live program now runs the new code.
        assertEquals(42, ((Number) evaluate(threadId, "offset()")).intValue(),
            "the method we replaced now returns what we wrote");

        // And the re-installed breakpoint still fires — no silent death.
        ObjectNode go = onSession("resume");
        go.put("threadId", threadId);
        assertTrue(tool.execute(go).isSuccess());
        Map<String, Object> again = awaitHit();
        assertEquals("computeSignal", again.get("method"),
            "the breakpoint survived the redefinition: " + again);
    }

    // ------------------------------------------------- the honest refusal

    @Test
    @DisplayName("THE HONEST REFUSAL: a schema change is refused by name, not as an internal error")
    void redefiningTheShapeOfAClassIsRefusedPlainly() throws Exception {
        // HotSpot allows changed method BODIES and nothing else. Add a METHOD and it must
        // refuse — and say so in a way the caller can act on, rather than surfacing a raw
        // UnsupportedOperationException and leaving them to guess.
        Path patched = Files.createTempDirectory("jawata-debug-schema-");
        Path patchedSource = patched.resolve("DebugTarget.java");
        Files.writeString(patchedSource, Files.readString(source).replace(
            "static int offset() {",
            "static int addedMethodThatChangesTheSchema() { return 1; }\n\n    static int offset() {"));
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", patched.toString(), patchedSource.toString()),
            "the schema-changed fixture must itself compile");

        armAndHit("line", Map.of("line", lineOf("int doubled = iteration * 2;")));

        ObjectNode redefine = onSession("redefine");
        redefine.put("className", TARGET);
        redefine.put("classFile",
            patched.resolve("com/example/debug/DebugTarget.class").toString());
        ToolResponse refused = tool.execute(redefine);

        assertFalse(refused.isSuccess(), "adding a method cannot be hot-swapped");
        assertEquals("REDEFINE_SCHEMA_CHANGE_UNSUPPORTED", refused.getError().getCode(),
            "refused for the RIGHT reason, by name: " + refused.getError());
        assertTrue(refused.getError().getMessage().contains("method BODIES"),
            "and says what IS allowed: " + refused.getError().getMessage());
        assertTrue(refused.getError().getMessage().contains("Restart"),
            "and what to do instead: " + refused.getError().getMessage());
    }

    // ------------------------------------------------------ the mutation log

    @Test
    @DisplayName("the session records every change it made — it is not the same program any more")
    void everyMutationIsRecorded() {
        // Before anything: the program is its own.
        Map<String, Object> clean = ok(onSession("mutations"));
        assertEquals(0, ((Number) clean.get("count")).intValue());
        assertEquals(Boolean.TRUE, clean.get("programIsUnmodified"));

        Map<String, Object> hit = armAndHit("line",
            Map.of("line", lineOf("int doubled = iteration * 2;")));
        long threadId = ((Number) hit.get("threadId")).longValue();

        ObjectNode set = onSession("set_value");
        set.put("threadId", threadId);
        set.put("name", "iteration");
        set.put("expression", "500");
        ok(set);

        ObjectNode forced = onSession("force_return");
        forced.put("threadId", threadId);
        forced.put("expression", "123");
        ok(forced);

        Map<String, Object> log = ok(onSession("mutations"));
        assertEquals(2, ((Number) log.get("count")).intValue(), "got: " + log);
        assertEquals(Boolean.FALSE, log.get("programIsUnmodified"),
            "it is NOT the program we started, and it says so");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> changed = (List<Map<String, Object>>) log.get("mutations");
        assertEquals("set_value", changed.get(0).get("mutation"), "in order: " + changed);
        assertEquals("force_return", changed.get(1).get("mutation"));

        // status carries the same warning, so nobody can read the session without seeing it.
        Map<String, Object> status = ok(onSession("status"));
        assertEquals(2, ((Number) status.get("mutationCount")).intValue());
        assertEquals(Boolean.FALSE, status.get("programIsUnmodified"), "got: " + status);
        assertNotEquals(null, status.get("mutations"));
    }

    @Test
    @DisplayName("evaluating a method-invoking expression is RECORDED — programIsUnmodified stops being a lie")
    void evaluatingASideEffectingExpressionIsRecorded() {
        // Sprint-24 audit: evaluate() recorded nothing, so an agent could run the target's
        // own code — `offset()`, or a real `queue.clear()` — and status would still answer
        // programIsUnmodified:true, from the one field whose whole job is to be trustworthy
        // about that. The spec is explicit: evaluation "may invoke methods and is
        // side-effecting unless proven otherwise", and must be recorded in the session outcome.
        Map<String, Object> hit = armAndHit("line",
            Map.of("line", lineOf("int doubled = iteration * 2;")));
        long threadId = ((Number) hit.get("threadId")).longValue();

        // A PURE evaluation (reads a field, folds operators) leaves the program its own.
        evaluate(threadId, "iteration * 2 + 1");
        Map<String, Object> afterPure = ok(onSession("mutations"));
        assertEquals(Boolean.TRUE, afterPure.get("programIsUnmodified"),
            "reading state and folding operators changes nothing: " + afterPure);

        // Invoking one of the program's OWN methods might have done anything — we cannot
        // prove offset() is pure, so we must not claim the program is untouched.
        assertEquals(7, ((Number) evaluate(threadId, "offset()")).intValue(),
            "sanity: the method really does run in the target");

        Map<String, Object> afterInvoke = ok(onSession("mutations"));
        assertEquals(Boolean.FALSE, afterInvoke.get("programIsUnmodified"),
            "after running the program's own code, it is no longer PROVABLY unmodified: "
                + afterInvoke);
        assertEquals(0, ((Number) afterInvoke.get("count")).intValue(),
            "…yet no STATE mutation was recorded — the two are distinct, and both are told");
        assertEquals(1L, ((Number) afterInvoke.get("evaluationsThatInvokedMethods")).longValue(),
            "the invoking evaluation is on the record: " + afterInvoke);

        // status tells the same truth, so a reader cannot miss it.
        Map<String, Object> status = ok(onSession("status"));
        assertEquals(Boolean.FALSE, status.get("programIsUnmodified"), "got: " + status);
        assertEquals(1L, ((Number) status.get("evaluationsThatInvokedMethods")).longValue());
        assertEquals(0, ((Number) status.get("mutationCount")).intValue());
    }

    // -------------------------------------------------------------- helpers

    private Map<String, Object> snapshot(long threadId) {
        ObjectNode args = onSession("snapshot");
        args.put("threadId", threadId);
        return ok(args);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> frameOf(Map<String, Object> snapshot) {
        return (Map<String, Object>) snapshot.get("frame");
    }

    @SuppressWarnings("unchecked")
    private String stackAt(long threadId, int index) {
        List<Map<String, Object>> stack =
            (List<Map<String, Object>>) snapshot(threadId).get("stack");
        return String.valueOf(stack.get(index).get("method"));
    }
}
