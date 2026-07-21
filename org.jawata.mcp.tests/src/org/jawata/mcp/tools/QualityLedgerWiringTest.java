package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jawata.mcp.knowledge.Confidence;
import org.jawata.mcp.knowledge.ExperienceEntry;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.QualityLedger;
import org.jawata.mcp.knowledge.SymbolFact;
import org.jawata.mcp.knowledge.ToolExperienceStore;
import org.jawata.mcp.learn.ToolExperience;
import org.jawata.mcp.learn.ToolExperienceRecorder;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 27 D6 (E6) — THE gate: every counter class advances from a real
 * session driven through the real front door, with ZERO manual steps.
 *
 * <p>This test exists because of Sprint 26, where five of seven learners were
 * constructed, listed, persisted and unit-tested — and never wired to observe
 * anything. Every green signal was present except the one that mattered. So
 * nothing here calls a counter directly: the session calls TOOLS, and the
 * counters are read afterwards. A counter that only a test can advance is not
 * shipped.</p>
 */
class QualityLedgerWiringTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private H2ExperienceStore store;
    private QualityLedger quality;
    private ToolRegistry reg;
    private boolean[] ran;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        quality = new QualityLedger(store);
        ran = new boolean[] {false};
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private static Tool mock(String name, Function<JsonNode, ToolResponse> exec) {
        return new Tool() {
            @Override public String getName() {
                return name;
            }
            @Override public String getDescription() {
                return name;
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return exec.apply(arguments);
            }
        };
    }

    private static JsonNode json(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * A registry wired the way the application wires it: a retriever that knows
     * `extract` reverted on com.foo.Bar (an IDENTITY hit) and once went wrong
     * somewhere else (the advisory tier), plus the quality ledger.
     */
    private void wireRegistry() {
        reg = new ToolRegistry();
        reg.setPrecedentRetriever((query, limit) -> List.of(
            new ToolExperience("s1", "extract com.foo.Bar method", "extract",
                ToolExperience.OUTCOME_REVERTED, "{}"),
            new ToolExperience("s1", "extract com.other.Thing block", "extract",
                ToolExperience.OUTCOME_REVERTED, "{}")));
        reg.setQualityLedger(quality);
        reg.register(mock("extract", a -> {
            ran[0] = true;
            return ToolResponse.success(Map.of("ok", true));
        }));
        reg.register(mock("analyze", a -> ToolResponse.success(Map.of("ok", true))));
    }

    private Map<String, Long> counters() {
        return quality.counters();
    }

    @Test
    void a_real_session_advances_the_steering_counters_with_no_manual_step()
            throws Exception {
        wireRegistry();

        // 1. A plain call surfaces the precedent — the choke's own doing.
        ToolResponse warned = reg.callTool("analyze",
            json("{\"typeName\":\"com.foo.Bar\"}"), "s1");
        assertNotNull(warned.getMeta(), "precondition: the push produced steering");
        assertTrue(warned.getMeta().getSteering().contains("⚠ PRECEDENT"),
            "precondition: the negative precedent was SURFACED");

        Map<String, Long> afterWarn = counters();
        assertEquals(1L, afterWarn.get("fired.choke_precedent"),
            "the precedent surface counted itself");
        assertEquals(1L, afterWarn.get("fired.choke_advisory"),
            "and so did the advisory tier — a DIFFERENT target's case was shown");
        assertEquals(1L, afterWarn.get("warned"),
            "the warning was recorded for the conformity derivation");

        // 2. The agent uses the warned tool anyway, paying the justification.
        ToolResponse paid = reg.callTool("extract",
            json("{\"symbol\":\"com.foo.Bar\",\"precedentOverride\":"
                + "\"the earlier revert was a bad range; this block is clean\"}"), "s1");
        assertTrue(paid.isSuccess() && ran[0], "precondition: the paid call ran");

        assertEquals(1L, counters().get("defected"),
            "a PAID override is a defection, and it is counted as one");
    }

    @Test
    void an_unpaid_refusal_is_not_counted_as_a_defection() throws Exception {
        wireRegistry();
        reg.callTool("analyze", json("{\"typeName\":\"com.foo.Bar\"}"), "s1");

        ToolResponse refused = reg.callTool("extract",
            json("{\"symbol\":\"com.foo.Bar\"}"), "s1");
        assertFalse(refused.isSuccess(), "precondition: the unjustified call is refused");
        assertFalse(ran[0], "precondition: the tool did not run");

        assertFalse(counters().containsKey("defected"),
            "a REFUSED attempt is not a defection — nothing was defected from, the "
            + "charge simply was not paid");
    }

    @Test
    void a_real_recall_advances_the_question_hook_and_the_fit_gate_per_criterion() {
        ExperienceTool tool = new ExperienceTool(() -> null, store);
        tool.setQualityLedger(quality);
        store.put(ExperienceEntry.of(SymbolFact.of("lesson",
                "renaming across modules needs a full rebuild", Confidence.MEDIUM)
            .symbol("com.foo.Bar").build()).build());

        var args = OM.createObjectNode();
        args.put("kind", "recall");
        args.put("symbol", "com.foo.Bar");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "precondition: the recall answered");

        Map<String, Long> c = counters();
        assertEquals(1L, c.get("fired.question_hook"),
            "the recall surface counted itself, from the tool front door");
        assertTrue(c.getOrDefault("gate.symbol.checked", 0L) >= 1L,
            "and the fit gate counted the criterion it actually consulted");
        assertTrue(c.containsKey("gate.symbol.passed"),
            "with the verdict, not just the visit");
        assertFalse(c.containsKey("gate.package.checked"),
            "a criterion the cue never carried is not counted — a checked count "
            + "that includes absent criteria measures nothing");
    }

    @Test
    void the_primer_surface_counts_itself() {
        ExperienceTool tool = new ExperienceTool(() -> null, store);
        tool.setQualityLedger(quality);
        // ACCEPTED: the primer only carries accepted domain nodes, so a
        // candidate would leave it empty and the surface would never fire.
        store.put(ExperienceEntry.of(SymbolFact.of("domain_fact",
                "the store's unit is the fact", Confidence.HIGH).build())
            .status(ExperienceEntry.ACCEPTED).build());

        var args = OM.createObjectNode();
        args.put("kind", "primer");
        assertTrue(tool.execute(args).isSuccess());
        assertEquals(1L, counters().get("fired.primer"));
    }

    @Test
    void an_outcome_on_a_steered_target_is_counted_after_the_steer() {
        // The recorder is the capture lane; the join is "was this pair steered?".
        ToolExperienceStore lane = new ToolExperienceStore(store);
        ToolExperienceRecorder recorder = new ToolExperienceRecorder(lane);
        org.jawata.mcp.learn.PrecedentLedger steers =
            new org.jawata.mcp.learn.PrecedentLedger();
        recorder.setQualityLedger(quality, steers);

        // No steer yet: an outcome here must NOT be counted as outcome-after.
        recorder.onCall("s1", "extract", json("{\"symbol\":\"com.unsteered.Thing\"}"),
            ToolResponse.error("BOOM", "failed", "hint"));
        assertFalse(counters().keySet().stream().anyMatch(k -> k.startsWith("outcome_after.")),
            "an outcome on a target nobody steered answers a different question");

        // Now the same pair is steered, and the next outcome on it counts.
        steers.warn("s1", "extract", "com.foo.Bar");
        recorder.onCall("s1", "extract", json("{\"symbol\":\"com.foo.Bar\"}"),
            ToolResponse.error("BOOM", "failed", "hint"));
        assertEquals(1L, counters().get("outcome_after.error"),
            "what happened after the steer, on the steered target");
    }

    @Test
    void the_outcome_join_still_holds_after_the_justification_is_paid() {
        ToolExperienceStore lane = new ToolExperienceStore(store);
        ToolExperienceRecorder recorder = new ToolExperienceRecorder(lane);
        org.jawata.mcp.learn.PrecedentLedger steers =
            new org.jawata.mcp.learn.PrecedentLedger();
        recorder.setQualityLedger(quality, steers);

        steers.warn("s1", "extract", "com.foo.Bar");
        steers.clear("s1", "extract", "com.foo.Bar");     // the cost was paid
        assertFalse(steers.isOutstanding("s1", "extract", "com.foo.Bar"),
            "precondition: paying removes the outstanding charge");

        recorder.onCall("s1", "extract", json("{\"symbol\":\"com.foo.Bar\"}"),
            ToolResponse.error("BOOM", "failed", "hint"));
        assertEquals(1L, counters().get("outcome_after.error"),
            "a defection's outcome is exactly the case worth measuring — losing it "
            + "when the charge clears would drop the most informative half");
    }

    @Test
    void a_seat_naming_itself_advances_the_seat_surface() {
        // A seat's recall and a prompt hook's recall are the same call on the
        // wire. The studio's runner names itself (runner.rs, the seat's
        // recall-before-report); without that this counter would sit at zero
        // forever and read as "seats never recall".
        ExperienceTool tool = new ExperienceTool(() -> null, store);
        tool.setQualityLedger(quality);
        store.put(ExperienceEntry.of(SymbolFact.of("lesson",
                "the seam was already profiled once", Confidence.MEDIUM)
            .symbol("com.foo.Bar").build()).build());

        var args = OM.createObjectNode();
        args.put("kind", "recall");
        args.put("symbol", "com.foo.Bar");
        args.put("surface", "seat");
        assertTrue(tool.execute(args).isSuccess());

        Map<String, Long> c = counters();
        assertEquals(1L, c.get("fired.seat"), "the seat surface counted itself");
        assertFalse(c.containsKey("fired.question_hook"),
            "and did NOT also count as a question hook — one recall, one surface");
    }

    @Test
    void an_unnamed_recall_still_counts_as_the_question_hook() {
        ExperienceTool tool = new ExperienceTool(() -> null, store);
        tool.setQualityLedger(quality);
        store.put(ExperienceEntry.of(SymbolFact.of("lesson", "a lesson",
            Confidence.MEDIUM).symbol("com.foo.Bar").build()).build());

        var args = OM.createObjectNode();
        args.put("kind", "recall");
        args.put("symbol", "com.foo.Bar");
        assertTrue(tool.execute(args).isSuccess());
        assertEquals(1L, counters().get("fired.question_hook"),
            "the default is unchanged — naming the surface is optional");
    }

    @Test
    void the_stats_front_door_carries_the_quality_block_and_its_label() {
        ExperienceTool tool = new ExperienceTool(() -> null, store);
        tool.setQualityLedger(quality);
        quality.fired(QualityLedger.SURFACE_SEAT);

        var args = OM.createObjectNode();
        args.put("kind", "stats");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> block = (Map<String, Object>) data.get("quality");
        assertNotNull(block, "stats is the read surface for D6 — the block ships there");
        assertTrue(String.valueOf(block.get("how_to_read")).contains("CORRELATION"),
            "and never without the sentence that says how to read it");
        assertNotNull(data.get("total"), "the store's own stats are still there");
    }

    @Test
    void without_a_ledger_every_path_behaves_exactly_as_before() throws Exception {
        // Measurement must be invisible when absent: same steering, same refusal,
        // same recall. A counter that changes behaviour measures the counter.
        ToolRegistry plain = new ToolRegistry();
        plain.setPrecedentRetriever((query, limit) -> List.of(
            new ToolExperience("s1", "extract com.foo.Bar method", "extract",
                ToolExperience.OUTCOME_REVERTED, "{}")));
        plain.register(mock("extract", a -> {
            ran[0] = true;
            return ToolResponse.success(Map.of("ok", true));
        }));
        plain.register(mock("analyze", a -> ToolResponse.success(Map.of("ok", true))));

        ToolResponse warned = plain.callTool("analyze",
            json("{\"typeName\":\"com.foo.Bar\"}"), "s1");
        assertTrue(warned.getMeta().getSteering().contains("⚠ PRECEDENT"));
        ToolResponse refused = plain.callTool("extract",
            json("{\"symbol\":\"com.foo.Bar\"}"), "s1");
        assertFalse(refused.isSuccess());
        assertFalse(ran[0]);

        ExperienceTool tool = new ExperienceTool(() -> null, store);
        var args = OM.createObjectNode();
        args.put("kind", "stats");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) tool.execute(args).getData();
        assertFalse(data.containsKey("quality"),
            "and stats shows no quality block rather than an empty one, which would "
            + "read as 'measured nothing' instead of 'not measuring'");
    }
}
