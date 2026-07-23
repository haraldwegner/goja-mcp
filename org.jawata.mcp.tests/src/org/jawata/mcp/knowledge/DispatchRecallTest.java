package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sprint 27 D4 — dispatch recall: a prose question whose SHAPE resembles a past
 * seat run comes back with what that run decided, and a question resembling
 * nothing comes back empty.
 *
 * <p>The corpus is real, not invented for the test: the studio runner ends every
 * seat run with {@code experience(kind=record)} — type {@code seat_run},
 * operation {@code seat:<name>}, the whole journal entry in {@code details}. The
 * fixtures below are that exact shape, so what passes here is what the live
 * carrier holds.</p>
 */
class DispatchRecallTest {

    private H2ExperienceStore store;
    private ExperienceRetrieval retrieval;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        EmbeddingService svc = EmbeddingService.shared();
        retrieval = svc.available()
            ? new ExperienceRetrieval(store, () -> null, new EmbeddingIndex(store, svc))
            : new ExperienceRetrieval(store, () -> null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /**
     * True when the bundled embedder loaded. A silently-skipped test counts as
     * a pass, which is how a green suite comes to prove nothing — so a skip
     * SAYS so, and the run's output shows how much of this class actually ran.
     */
    private boolean embedderAvailable() {
        boolean up = EmbeddingService.shared().available();
        if (!up) {
            System.out.println("DispatchRecallTest: NOT RUN — no embedder available "
                + "(this class asserts nothing in this run)");
        }
        return up;
    }

    /** One journal line in the runner's own shape (camelCase, schema 1). */
    private static String journal(String seat, String target, String work,
                                  String humanVerdict, String outcome) {
        String verdict = humanVerdict == null ? "null" : "\"" + humanVerdict + "\"";
        return "{\"schema\":1,\"ts\":1750000000,\"runId\":\"" + seat + "-1\","
            + "\"seat\":\"" + seat + "\",\"model\":\"claude\",\"adapter\":\"claude-code\","
            + "\"target\":\"" + target + "\",\"work\":\"" + work + "\","
            + "\"evidence\":\"gates green\",\"gates\":[],\"verdict\":\"Proposed\","
            + "\"humanVerdict\":" + verdict + ",\"outcome\":\"" + outcome + "\","
            + "\"costUsd\":0.4,\"iterations\":2,\"wallSecs\":91}";
    }

    private String seatRun(String seat, String target, String work,
                           String humanVerdict, String outcome) {
        SymbolFact f = SymbolFact.of(DispatchRecall.SEAT_RUN_TYPE,
                "seat " + seat + " on " + target + ": " + outcome + " (" + seat + "-1)",
                Confidence.MEDIUM)
            .details(journal(seat, target, work, humanVerdict, outcome))
            .build();
        return store.put(ExperienceEntry.of(f).operation("seat:" + seat).build());
    }

    private static RecallQuery symptom(String s) {
        return new RecallQuery(null, null, null, s, null);
    }

    /**
     * Sprint 27a C3 — dispatch degrades to keyword UNCONDITIONALLY, embedder off.
     *
     * <p>The other dispatch tests run against whichever retriever {@code setUp}
     * chose, so the keyword path was exercised only in an environment where the
     * embedder failed to load — not pinned. This builds the keyword-only
     * retriever explicitly and proves a seeded seat run is still recalled by
     * word, so the dispatch surface never goes dark because the model is
     * absent.</p>
     */
    @Test
    void dispatch_degrades_to_keyword_when_the_embedder_is_off() {
        ExperienceRetrieval keywordOnly = ExperienceRetrieval.keywordOnly(store, () -> null);
        seatRun("javadoc-writer", "com.example.PurityCheck",
            "document the undocumented public methods", "accepted", "applied");

        // Keyword recall matches the SUMMARY ("seat javadoc-writer on
        // com.example.PurityCheck: applied"), not the journal detail — so the
        // cue is worded from what the keyword path can see. That is the honest
        // degrade contract: without meaning, exact words are all there is.
        Map<String, Object> answer = keywordOnly.recall(symptom(
            "javadoc-writer on com.example.PurityCheck"));
        Map<String, Object> dispatch = firstDispatch(answer);
        assertNotNull(dispatch,
            "with no embedder the seeded seat run must still recall by keyword, "
            + "carrying its dispatch block");
        assertEquals("javadoc-writer", dispatch.get("seat"),
            "and it is the seeded run: " + dispatch);
    }

    /** The dispatch block of the first analogy that carries one, or null. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstDispatch(Map<String, Object> result) {
        for (Map<String, Object> a : analogies(result)) {
            Object d = a.get("dispatch");
            if (d instanceof Map) {
                return (Map<String, Object>) d;
            }
        }
        for (Map<String, Object> e : entries(result)) {
            Object d = e.get("dispatch");
            if (d instanceof Map) {
                return (Map<String, Object>) d;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> analogies(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.getOrDefault("analogies", List.of());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.getOrDefault("entries", List.of());
    }

    // ---- the positive: a shape-near cue returns seat + verdict + outcome ----

    @Test
    void a_prose_cue_near_a_past_run_returns_its_seat_verdict_and_outcome() {
        if (!embedderAvailable()) {
            return;
        }
        seatRun("javadoc-writer", "org.jawata.mcp.refactoring.PurityCheck",
            "document the five undocumented public methods on this type",
            "accepted", "applied");
        seatRun("profiler", "org.jawata.mcp.knowledge.EmbeddingIndex",
            "sample the resident and name the hotspot in the recall path",
            "rejected", "no-change");

        // Prose, no shared vocabulary with the summary line: "undocumented" and
        // "public API" appear only inside the journal's work statement.
        Map<String, Object> out = retrieval.recall(symptom("public methods on a type have no documentation at all"));

        Map<String, Object> dispatch = firstDispatch(out);
        assertNotNull(dispatch, "a question shaped like a past run must find it; "
            + "result was " + out.get("result"));
        assertEquals("javadoc-writer", dispatch.get("seat"));
        assertEquals("accepted", dispatch.get("human_verdict"));
        assertEquals("applied", dispatch.get("outcome"));
    }

    @Test
    void a_novel_shape_returns_honest_nothing() {
        if (!embedderAvailable()) {
            return;
        }
        seatRun("javadoc-writer", "org.jawata.mcp.refactoring.PurityCheck",
            "document the five undocumented public methods on this type",
            "accepted", "applied");

        Map<String, Object> out = retrieval.recall(symptom("the espresso machine leaks water onto the kitchen floor"));

        assertNull(firstDispatch(out),
            "nothing in the journal resembles this, and inventing a resemblance "
            + "is the failure this test exists to catch");
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, out.get("result"));
    }

    @Test
    void an_unjudged_run_says_so_rather_than_implying_acceptance() {
        if (!embedderAvailable()) {
            return;
        }
        seatRun("test-writer", "org.jawata.mcp.knowledge.EmbeddingIndex",
            "write characterization tests for the uncovered nomination path",
            null, "proposed");

        Map<String, Object> out = retrieval.recall(symptom("uncovered code needs characterization tests written for it"));
        Map<String, Object> dispatch = firstDispatch(out);
        assertNotNull(dispatch, "result was " + out.get("result"));
        assertEquals("not yet judged", dispatch.get("human_verdict"),
            "an absent verdict must be STATED — an omitted key reads as approval");
    }

    // ---- the framing: a past run is evidence, never a rule -----------------

    @Test
    void a_past_run_arrives_as_an_analogy_not_as_a_rule() {
        if (!embedderAvailable()) {
            return;
        }
        seatRun("architect", "org.jawata.mcp.knowledge.ExperienceRetrieval",
            "review the recall pipeline against the sprint architecture",
            "accepted", "applied");

        Map<String, Object> out = retrieval.recall(symptom("is this retrieval design consistent with the architecture we agreed"));
        List<Map<String, Object>> as = analogies(out);
        assertFalse(as.isEmpty(), "result was " + out.get("result"));
        Map<String, Object> a = as.get(0);
        assertEquals("analogy — judge whether it transfers", a.get("framing"),
            "the dispatch facts ride the analogy carrier and keep its framing — "
            + "what once happened never becomes what should happen");
        assertNotNull(a.get("basis"), "and it still says WHY it surfaced, in words");

        String text = ExperienceRetrieval.renderText(out);
        assertTrue(text.contains("In a similar situation:"),
            "the rendered line keeps the advisory opening: " + text);
        assertTrue(text.contains("past run: seat architect"),
            "and states the run's facts inline: " + text);
        assertFalse(text.matches(".*\\b0\\.\\d+.*"),
            "no similarity number in any rendering: " + text);
    }

    @Test
    void the_same_facts_arrive_however_the_run_was_found() {
        if (!embedderAvailable()) {
            return;
        }
        seatRun("debugger", "org.jawata.mcp.tools.ExperienceTool",
            "attach and probe the null returned by the dedup hook",
            "accepted", "applied");

        // Found by the SCOPE gate this time (operation cue), not by meaning —
        // the facts must not depend on which nominator did the finding.
        Map<String, Object> out = retrieval.recall(
            new RecallQuery(null, null, "seat:debugger", null, null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, out.get("result"));
        List<Map<String, Object>> es = entries(out);
        assertFalse(es.isEmpty());
        @SuppressWarnings("unchecked")
        Map<String, Object> dispatch = (Map<String, Object>) es.get(0).get("dispatch");
        assertNotNull(dispatch, "a scope-found seat run states the same three facts");
        assertEquals("debugger", dispatch.get("seat"));
        assertEquals("accepted", dispatch.get("human_verdict"));
        assertEquals("applied", dispatch.get("outcome"));

        String text = ExperienceRetrieval.renderText(out);
        assertTrue(text.contains("past run: seat debugger"), text);
        assertFalse(text.contains("\"schema\""),
            "and the raw journal blob does NOT reach the agent's context: " + text);
    }

    // ---- the reader, in isolation -----------------------------------------

    /**
     * The live store holds seat records in TWO shapes: the runner's structured
     * journal (read above) and the seat COMMANDS' own records, which state the
     * seat and the human's verdict in prose. This class reads the structured
     * shape only — deliberately, because pulling a verdict out of free text
     * with a pattern is how a fact gets invented. The prose shape must
     * therefore still arrive by the ordinary analogy path, unparsed and whole.
     */
    @Test
    void the_seat_command_record_shape_is_not_dropped_just_because_it_is_unstructured() {
        if (!embedderAvailable()) {
            return;
        }
        SymbolFact f = SymbolFact.of("lesson",
                "javadoc-writer seat on RecallQuery: added grounded Javadoc to 5 public "
                + "has* methods. Gates: compile 0/0. Verdict: accepted (human yes).",
                Confidence.MEDIUM).build();
        store.put(ExperienceEntry.of(f).operation("seat:javadocs").build());

        Map<String, Object> out = retrieval.recall(
            symptom("undocumented public methods on a type were written up by a seat"));
        List<Map<String, Object>> as = analogies(out);
        assertFalse(as.isEmpty(), "the prose-shaped seat record must still surface; "
            + "result was " + out.get("result"));
        assertTrue(String.valueOf(as.get(0).get("summary")).contains("accepted (human yes)"),
            "with its verdict intact IN the prose, exactly as recorded");
        assertNull(as.get(0).get("dispatch"),
            "and NO structured dispatch block — this shape has no journal to read, "
            + "and guessing one would be an invented fact");
    }

    @Test
    void a_non_seat_entry_has_no_dispatch_facts_to_state() {
        String id = store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "an ordinary lesson", Confidence.MEDIUM).build()).build());
        StoredEntry e = store.byIds(List.of(id)).get(0);
        assertFalse(DispatchRecall.isSeatRun(e));
        assertNull(DispatchRecall.of(e));
        assertEquals("", DispatchRecall.renderLine(null));
    }

    @Test
    void an_unparseable_journal_still_reports_what_the_store_knows() {
        String id = store.put(ExperienceEntry.of(
            SymbolFact.of(DispatchRecall.SEAT_RUN_TYPE,
                    "seat profiler on Foo: applied (profiler-9)", Confidence.MEDIUM)
                .details("{ this is not json").build())
            .operation("seat:profiler").build());
        StoredEntry e = store.byIds(List.of(id)).get(0);
        DispatchRecall.PastRun r = DispatchRecall.of(e);
        assertNotNull(r, "a broken journal is not a missing run");
        assertEquals("profiler", r.seat(), "the operation column is still true");
        assertFalse(r.judged());
        assertTrue(DispatchRecall.renderLine(r).contains("not yet judged"));
    }
}
