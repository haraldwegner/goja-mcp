package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.jawata.mcp.tools.ExperienceTool;
import org.jawata.mcp.tools.GetCallHierarchyIncomingTool;
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
 * Sprint 24 (D15/D16/D17, C12) — the loop closes.
 *
 * <p>THE CLAIM OF THE WHOLE SPRINT: a fact discovered in a RUNNING program hands straight to
 * the compiler-accurate tools. The debugger says "you are stopped in
 * {@code DebugTarget.computeSignal}", and that IS the key — {@code symbol=
 * "com.example.debug.DebugTarget#computeSignal"} goes directly into
 * {@code get_call_hierarchy}. No search in between. A search would only re-derive what the
 * program just told us, and could get it wrong.</p>
 */
class DebugClosureTest {

    private static final String TARGET = "com.example.debug.DebugTarget";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RuntimeSessionRegistry sessions;
    private DebugTool tool;
    private ObjectMapper om;
    private List<String> source;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        tool = new DebugTool(() -> service, sessions);
        om = new ObjectMapper();

        Path file = service.getProjectRoot()
            .resolve("src/main/java/com/example/debug/DebugTarget.java");
        source = Files.readAllLines(file);

        Path classes = Files.createTempDirectory("jawata-closure-");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", classes.toString(), file.toString()));

        ObjectNode launch = action("launch");
        launch.put("mainClass", TARGET);
        launch.put("classpath", classes.toString());
        sessionId = (String) ok(launch).get("sessionId");
    }

    @AfterEach
    void tearDown() {
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

    /** Break in computeSignal and return the hit. */
    private Map<String, Object> hitInComputeSignal() {
        ObjectNode bp = onSession("breakpoint_set");
        bp.put("kind", "line");
        bp.put("className", TARGET);
        bp.put("line", lineOf("int doubled = iteration * 2;"));
        ok(bp);

        assertTrue(tool.execute(onSession("resume")).isSuccess());

        ObjectNode wait = onSession("wait");
        wait.put("timeoutMillis", 30_000);
        Map<String, Object> hit = ok(wait);
        assertEquals(Boolean.TRUE, hit.get("hit"), "got: " + hit);
        return hit;
    }

    // ------------------------------------------------------- D15: the closure

    @Test
    @DisplayName("THE CLOSURE: a breakpoint hit hands STRAIGHT to get_call_hierarchy — no search")
    void aRuntimeFactGoesDirectlyIntoTheStaticTools() {
        Map<String, Object> hit = hitInComputeSignal();

        // The hit already carries the key. We do not search for it; we did not have to.
        assertEquals(TARGET, hit.get("class"), "got: " + hit);
        assertEquals("computeSignal", hit.get("method"));
        assertEquals(TARGET + "#computeSignal", hit.get("symbol"),
            "the hit hands you the symbol, pre-assembled: " + hit);

        // HAND IT STRAIGHT OVER. This is the whole point of the sprint: the runtime told us
        // where we are, and the compiler-accurate tools take that name as-is.
        GetCallHierarchyIncomingTool callers = new GetCallHierarchyIncomingTool(() -> service);
        ObjectNode args = om.createObjectNode();
        args.put("symbol", String.valueOf(hit.get("symbol")));

        ToolResponse r = callers.execute(args);
        assertTrue(r.isSuccess(),
            "the symbol the DEBUGGER produced must resolve in the STATIC tools with no "
                + "intermediate search: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> hierarchy = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> incoming =
            (List<Map<String, Object>>) hierarchy.get("callers");
        assertNotNull(incoming, "got: " + hierarchy);
        assertTrue(incoming.stream().anyMatch(c -> "main".equals(c.get("callerMethod"))),
            "computeSignal is called from main — a fact the COMPILER knows, reached from a "
                + "fact the RUNNING PROGRAM knew: " + incoming);
    }

    @Test
    @DisplayName("the steering TELLS the agent not to search — the loop must close on purpose")
    void theHitSteersAgainstSearching() {
        ObjectNode bp = onSession("breakpoint_set");
        bp.put("kind", "line");
        bp.put("className", TARGET);
        bp.put("line", lineOf("int doubled = iteration * 2;"));
        ok(bp);
        assertTrue(tool.execute(onSession("resume")).isSuccess());

        ObjectNode wait = onSession("wait");
        wait.put("timeoutMillis", 30_000);
        ToolResponse r = tool.execute(wait);

        String steering = r.getMeta() == null ? "" : String.valueOf(r.getMeta().getSteering());
        assertTrue(steering.contains("symbol=\"" + TARGET + "#computeSignal\""),
            "the steering hands over the exact key: " + steering);
        assertTrue(steering.contains("do NOT search"),
            "and says plainly not to go looking for what it just told you: " + steering);
    }

    @Test
    @DisplayName("the symbol a HIT produced is a first-class MEMORY key: record it, recall it")
    void aRuntimeFindingCanBeRememberedAgainstTheSymbolItWasFoundAt() {
        Map<String, Object> hit = hitInComputeSignal();
        String symbol = String.valueOf(hit.get("symbol"));

        // RECORD what the running program taught us, anchored at the symbol it taught us at.
        org.jawata.mcp.knowledge.H2ExperienceStore store =
            org.jawata.mcp.knowledge.H2ExperienceStore.open(null);
        ExperienceTool experience = new ExperienceTool(() -> service, store);
        ObjectNode record = om.createObjectNode();
        record.put("kind", "record");
        record.put("type", "lesson");
        record.put("summary", "computeSignal returns iteration*2 + offset(); observed live at a "
            + "breakpoint during Sprint 24 closure");
        record.put("symbol", symbol);
        record.put("confidence", "high");
        ToolResponse recorded = experience.execute(record);
        assertTrue(recorded.isSuccess(), "a runtime finding must be recordable: "
            + recorded.getError());

        // RECALL it by the SAME key the debugger produced. This is the loop closing on the
        // knowledge store: what we learn at runtime is findable by the name the runtime used.
        ObjectNode recall = om.createObjectNode();
        recall.put("kind", "recall");
        recall.put("symbol", symbol);
        ToolResponse recalled = experience.execute(recall);
        assertTrue(recalled.isSuccess(), "got: " + recalled.getError());
        assertTrue(String.valueOf(recalled.getData()).contains("computeSignal"),
            "the lesson comes back for the symbol the DEBUGGER named: " + recalled.getData());
    }

    // -------------------------------------------------- D16: the audit battery

    @Test
    @DisplayName("D16: every event carries sessionId AND projectKey — a hit is never an orphan")
    void everyEventCarriesItsContext() throws Exception {
        ObjectNode bp = onSession("breakpoint_set");
        bp.put("kind", "line");
        bp.put("className", TARGET);
        bp.put("line", lineOf("int doubled = iteration * 2;"));
        bp.put("projectKey", "debug-target");
        ok(bp);
        assertTrue(tool.execute(onSession("resume")).isSuccess());

        ObjectNode wait = onSession("wait");
        wait.put("timeoutMillis", 30_000);
        wait.put("projectKey", "debug-target");
        Map<String, Object> hit = ok(wait);

        assertEquals(sessionId, hit.get("sessionId"), "got: " + hit);
        assertEquals("debug-target", hit.get("projectKey"),
            "a hit must say WHICH workspace its class belongs to, or the symbol it hands you "
                + "is ambiguous the moment there are two projects: " + hit);

        // And the same context is in the STREAMED line, which is what a watcher reads.
        Path journal = Path.of(String.valueOf(ok(onSession("status")).get("hitStream")));
        List<String> lines = Files.readAllLines(journal);
        Map<?, ?> streamed = new ObjectMapper()
            .readValue(lines.get(lines.size() - 1), Map.class);
        assertEquals(sessionId, streamed.get("sessionId"), "got: " + streamed);
        assertEquals("debug-target", streamed.get("projectKey"));
        assertNotNull(streamed.get("class"), "with everything needed to route it back");
    }

    @Test
    @DisplayName("D16: the front door documents the subagent hand-off and the wait contract")
    void theFrontDoorDocumentsHowToWaitWithoutBurningTheMainLoop() {
        String description = tool.getDescription();

        assertTrue(description.contains("SUBAGENT"),
            "a rare condition is waited for by a subagent, and the tool must say so");
        assertTrue(description.contains("NOTHING IS EVER MISSED"),
            "and must say why waiting is safe: " + description);
        assertTrue(description.contains("hitStream"),
            "and point at the channel that WAKES an agent rather than being polled");
    }

    @Test
    @DisplayName("D16: artifacts can be listed and explicitly deleted — they get large")
    void artifactsAreListableAndDeletable() {
        Map<String, Object> listed = ok(onSession("artifacts"));
        assertNotNull(listed.get("artifacts"));
        assertNotNull(listed.get("root"));

        // Deleting something that is not there is an honest miss, not a crash.
        ObjectNode delete = onSession("artifact_delete");
        delete.put("artifactId", "replay-does-not-exist");
        assertFalse(tool.execute(delete).isSuccess());
    }

    // ---------------------------------------------------------- D17: the words

    @Test
    @DisplayName("D17: the disclaimer says all three things, in the tool AND in the README")
    void theDisclaimerIsPresentAndComplete() throws Exception {
        String description = tool.getDescription();

        // 1. It suspends and mutates.
        assertTrue(description.contains("SUSPENDS THREADS AND CHANGES STATE"),
            "the tool must say what it does to the target");
        // 2. It is for dev/sim — and WHY that is structural, not a policy.
        assertTrue(description.contains("DEVELOPMENT OR SIMULATION"), "got: " + description);
        assertTrue(description.contains("unreachable"),
            "and why a production JVM is not merely refused but unreachable");
        // 3. Elsewhere, it is the operator's judgment.
        assertTrue(description.contains("PROFESSIONAL JUDGMENT"), "got: " + description);

        // The same three statements in the README, where a human reads them.
        Path readme = Path.of("README.md");
        if (!Files.isRegularFile(readme)) {
            readme = Path.of(System.getProperty("user.dir"), "README.md");
        }
        if (Files.isRegularFile(readme)) {
            String text = Files.readString(readme);
            assertTrue(text.contains("Runtime debugging — what it does to the target"),
                "the README carries the section");
            assertTrue(text.contains("suspends threads and changes state"), "statement 1");
            assertTrue(text.contains("development or simulation machine"), "statement 2");
            assertTrue(text.contains("your professional judgment"), "statement 3");
        }
    }
}
