package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.coverage.MechanicalChangeJournal;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.jawata.mcp.tools.Tool;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 26 Stage 7 — the D7 body clause, end to end: a scripted NORMAL
 * session (edits observed, compiles, a mechanical touch, a tool error, a
 * declared slip, a seat outcome) advances every learner counter and the
 * policy dial with ZERO learning actions — training is a side effect of
 * use. {@code experience(kind=learner_status)} then reports the same
 * numbers through the same registry.
 */
class ZeroManualStepTest {

    private static final String STRUCTURAL_BEFORE = "int f(int a) { return a; }";
    private static final String STRUCTURAL_AFTER =
        "int f(int a, int b) { return a + b; } int g() { return 0; }";
    private static final String TRIVIAL_BEFORE = "int f(int a) { return a; }";
    private static final String TRIVIAL_AFTER = "int f(int a) { /* same */ return a; }";

    private H2ExperienceStore store;
    private LearnerEventStore events;
    private LearnerService learners;
    private ServerChecks checks;
    private ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    /** The gate stub's next answer — the script flips it between calls. */
    private int nextCompileErrors;

    @BeforeEach
    void setUp() {
        MechanicalChangeJournal.clear();
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
        learners = new LearnerService(events);
        SessionLedger ledger = new SessionLedger();
        EventTap tap = new EventTap(ledger, events);
        tap.setLearnerService(learners);
        checks = new ServerChecks(events, (s, d) -> { });

        ExperienceTool experience = new ExperienceTool(() -> null, store);
        experience.setLearnerService(learners);

        registry = new ToolRegistry();
        registry.setEventTap(tap);
        registry.setServerChecks(checks);
        registry.register(experience);
        registry.register(stub("compile_workspace",
            () -> ToolResponse.success(Map.of("errorCount", nextCompileErrors))));
        registry.register(stub("rename_symbol",
            () -> ToolResponse.success(Map.of("filesModified", List.of("A.java")))));
        registry.register(stub("failing_tool",
            () -> ToolResponse.internalError("scripted failure")));
    }

    @AfterEach
    void tearDown() throws Exception {
        MechanicalChangeJournal.clear();
        store.close();
    }

    @Test
    @DisplayName("D7: a normal session advances every counter with zero learning actions")
    void a_normal_session_trains_everything_as_a_side_effect() throws Exception {
        String session = "normal-work";
        long editEventsBefore = learners.learner(LearnerService.EDIT_SWITCH).eventsSeen();

        // 1. An observed structural edit … followed by a FAILING compile: the
        //    consequence labels it structural-mishandled.
        call(session, "experience", "{\"kind\":\"observe_edit\",\"before\":"
            + quote(STRUCTURAL_BEFORE) + ",\"after\":" + quote(STRUCTURAL_AFTER) + "}");
        nextCompileErrors = 2;
        call(session, "compile_workspace", "{}");

        // 2. An observed trivial edit … followed by a CLEAN compile: trivial-ok.
        call(session, "experience", "{\"kind\":\"observe_edit\",\"before\":"
            + quote(TRIVIAL_BEFORE) + ",\"after\":" + quote(TRIVIAL_AFTER) + "}");
        nextCompileErrors = 0;
        call(session, "compile_workspace", "{}");

        // 3. Ordinary work: a mechanical rename, a tool error, a declared slip,
        //    a seat outcome (its gate ran above).
        call(session, "rename_symbol", "{}");
        call(session, "failing_tool", "{}");
        call(session, "experience", "{\"kind\":\"record\",\"type\":\"failure_mode\","
            + "\"operation\":\"jawata-fallback-slip\",\"summary\":\"grep over Foo.java\"}");
        call(session, "experience", "{\"kind\":\"record\",\"type\":\"lesson\","
            + "\"operation\":\"seat:javadocs\",\"summary\":\"seat outcome\"}");

        // EVERY event class of a normal session is journaled — no learning call made.
        Map<String, Long> counts = events.countByKind();
        assertEquals(2L, counts.getOrDefault(LearnerEvent.KIND_EDIT_OBSERVED, 0L));
        assertEquals(2L, counts.getOrDefault(LearnerEvent.KIND_EDIT_RESOLVED, 0L));
        assertTrue(counts.getOrDefault(LearnerEvent.KIND_GATE_CALL, 0L) >= 2);
        assertEquals(1L, counts.getOrDefault(LearnerEvent.KIND_MECHANICAL_TOUCH, 0L));
        assertEquals(1L, counts.getOrDefault(LearnerEvent.KIND_TOOL_ERROR, 0L));

        // The edit switch LEARNED from both consequences (rolling record advanced).
        Learner editSwitch = learners.learner(LearnerService.EDIT_SWITCH);
        assertEquals(editEventsBefore + 2, editSwitch.eventsSeen(),
            "both pending edits were consequence-labeled into the switch");
        assertEquals(0, learners.pendingCount(session), "nothing left pending");

        // The policy dial moved on the declared fallback — and because a REAL
        // tool error preceded it in this session, the D3 rule classifies it a
        // legitimate RELAX (not a slip), exactly as designed.
        assertTrue(checks.dial().describe().getOrDefault("fallback", "")
            .contains("relaxes=1"), "the fallback fed the dial: " + checks.dial().describe());

        // learner_status through the SAME registry reports the same numbers.
        ToolResponse status = call(session, "experience", "{\"kind\":\"learner_status\"}");
        Map<String, Object> report = data(status);
        assertEquals(events.totalEvents(), ((Number) report.get("totalEvents")).longValue());
        Map<String, Object> editRow = ((List<Map<String, Object>>) report.get("learners"))
            .stream().filter(l -> LearnerService.EDIT_SWITCH.equals(l.get("learner")))
            .findFirst().orElseThrow();
        assertEquals(editEventsBefore + 2, ((Number) editRow.get("eventsSeen")).longValue());

        // /train's core (kind=train) answers through the same door.
        assertTrue(call(session, "experience", "{\"kind\":\"train\"}").isSuccess());
    }

    @Test
    @DisplayName("observe_edit answers the edit switch's advice; an undo resolves pendings as mishandled")
    void an_undo_is_a_structural_mishandled_consequence() throws Exception {
        String session = "undo-session";
        ToolResponse observed = call(session, "experience",
            "{\"kind\":\"observe_edit\",\"before\":" + quote(STRUCTURAL_BEFORE)
                + ",\"after\":" + quote(STRUCTURAL_AFTER) + "}");
        Map<String, Object> data = data(observed);
        assertEquals(Boolean.TRUE, data.get("observed"));
        assertEquals(Boolean.TRUE, data.get("structural"), "the hand rule sees the new method");
        assertEquals(1, learners.pendingCount(session));

        long before = learners.learner(LearnerService.EDIT_SWITCH).eventsSeen();
        registry.register(stub("refactoring", () -> ToolResponse.success(Map.of("undone", true))));
        call(session, "refactoring", "{\"action\":\"undo\"}");
        assertEquals(before + 1, learners.learner(LearnerService.EDIT_SWITCH).eventsSeen(),
            "the undo consequence-labeled the pending edit");
        assertEquals(0, learners.pendingCount(session));
        assertTrue(events.countByKind().containsKey(LearnerEvent.KIND_EDIT_RESOLVED));
    }

    @Test
    @DisplayName("the hook path: observe_edit with outcome trains immediately, nothing pends")
    void a_labeled_observe_edit_trains_immediately() throws Exception {
        long before = learners.learner(LearnerService.EDIT_SWITCH).eventsSeen();
        ToolResponse r = call("hook-session", "experience",
            "{\"kind\":\"observe_edit\",\"outcome\":\"failed\",\"before\":"
                + quote(STRUCTURAL_BEFORE) + ",\"after\":" + quote(STRUCTURAL_AFTER) + "}");
        Map<String, Object> data = data(r);
        assertEquals(Boolean.TRUE, data.get("labeled"));
        assertEquals(Boolean.TRUE, data.get("actualStructural"),
            "a failed consequence labels the edit structural-mishandled");
        assertEquals(before + 1, learners.learner(LearnerService.EDIT_SWITCH).eventsSeen(),
            "the labeled edit trained without waiting for a gate");
        assertEquals(0, learners.pendingCount("hook-session"), "labeled edits never pend");
        Map<String, Long> counts = events.countByKind();
        assertEquals(1L, counts.getOrDefault(LearnerEvent.KIND_EDIT_OBSERVED, 0L));
        assertEquals(1L, counts.getOrDefault(LearnerEvent.KIND_EDIT_RESOLVED, 0L),
            "a labeled observe journals both halves at once");
    }

    @Test
    @DisplayName("pending edits are session-scoped: another session's gate resolves nothing here")
    void pending_edits_are_session_scoped() throws Exception {
        call("editor", "experience", "{\"kind\":\"observe_edit\",\"before\":"
            + quote(TRIVIAL_BEFORE) + ",\"after\":" + quote(TRIVIAL_AFTER) + "}");
        nextCompileErrors = 0;
        call("someone-else", "compile_workspace", "{}");
        assertEquals(1, learners.pendingCount("editor"),
            "a stranger's gate outcome is not this session's consequence");
    }

    // ---------- harness ----------

    private ToolResponse call(String session, String tool, String json) throws Exception {
        ToolResponse r = registry.callTool(tool, mapper.readTree(json), session);
        if (!"failing_tool".equals(tool)) {
            assertTrue(r.isSuccess(), tool + " failed: " + r.getError());
        }
        return r;
    }

    private String quote(String s) throws Exception {
        return mapper.writeValueAsString(s);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private static Tool stub(String name, java.util.function.Supplier<ToolResponse> answer) {
        return new Tool() {
            @Override public String getName() {
                return name;
            }

            @Override public String getDescription() {
                return "stub " + name;
            }

            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object");
            }

            @Override public ToolResponse execute(JsonNode arguments) {
                return answer.get();
            }
        };
    }
}
