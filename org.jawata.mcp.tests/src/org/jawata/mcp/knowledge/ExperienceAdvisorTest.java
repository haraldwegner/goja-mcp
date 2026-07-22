package org.jawata.mcp.knowledge;

import org.jawata.mcp.domain.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21 Stage 3 — the store-backed {@link ExperienceAdvisor} filling the Sprint-18
 * seam: terminal recall on {@code adviseBefore}, write-admission on {@code record}.
 */
class ExperienceAdvisorTest {

    private H2ExperienceStore store;
    private ExperienceAdvisor advisor;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        advisor = new ExperienceAdvisor(store, () -> null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void adviseBefore_returns_a_fitting_node_then_absence() {
        store.put(ExperienceEntry.of(
            SymbolFact.of("failure_mode", "compose_method rolled back on a recursive body",
                Confidence.HIGH).symbol("com.example.OrderService").build())
            .operation("compose_method").build());

        List<String> hit = advisor.adviseBefore("compose_method", "com.example.OrderService");
        assertEquals(1, hit.size(), "terminal: at most one fitting node");
        assertTrue(hit.get(0).contains("compose_method rolled back"));

        // Sprint 27a F3: a DIFFERENT symbol, same operation. The entry does not
        // FIT (its symbol is OrderService, the cue's is Unrelated), so it is not
        // vouched — but a compose_method that rolled back once is a precedent
        // worth showing before composing again, and this surface used to discard
        // it. It now surfaces as a labelled ANALOGY, which is exactly the value
        // F3 restores: "this happened on another symbol — judge whether it
        // transfers", never "this is an established fact about Unrelated".
        List<String> nearby = advisor.adviseBefore("compose_method", "com.other.Unrelated");
        assertEquals(1, nearby.size(), "a same-operation precedent surfaces as advice");
        assertTrue(nearby.get(0).contains("compose_method rolled back"),
            "and it is the precedent: " + nearby.get(0));
        assertTrue(nearby.get(0).contains("judge whether it transfers"),
            "labelled as an analogy to judge, never as a vouched fact: " + nearby.get(0));

        // A cue sharing NOTHING — different operation and unrelated symbol —
        // still returns nothing. Absence is still absence.
        List<String> miss = advisor.adviseBefore("rename_symbol", "com.other.Nothing");
        assertTrue(miss.isEmpty(), "no precedent of any kind → empty advice");
    }

    /**
     * Sprint 27a C3 — the wired-not-built check for the pre-advice surface: the
     * quality ledger records BOTH a speak and an abstain, as a side effect of
     * the ordinary {@code adviseBefore} call, with no separate feed to remember.
     *
     * <p>This is the shape v3.4.0 shipped wrong: a capability whose activation
     * depends on someone calling a second method is not wired. Here the counter
     * moves because the surface was USED, and both outcomes are distinguished —
     * a surface that stays silent is not the same as one never consulted, and
     * before this only the speak had a counter.</p>
     */
    @Test
    void the_pre_advice_surface_counts_both_a_speak_and_an_abstain() {
        QualityLedger ledger = new QualityLedger(store);
        advisor.setQualityLedger(ledger);
        store.put(ExperienceEntry.of(
            SymbolFact.of("failure_mode", "compose_method rolled back on a recursive body",
                Confidence.HIGH).symbol("com.example.OrderService").build())
            .operation("compose_method").build());

        // SPEAK: a fitting precedent reaches the agent.
        advisor.adviseBefore("compose_method", "com.example.OrderService");
        assertEquals(1L, ledger.counters().get("fired.choke_advisory"),
            "a speak must increment the surface's fired counter");

        // ABSTAIN: nothing to say, and that silence is counted too.
        advisor.adviseBefore("rename_symbol", "com.other.Nothing");
        assertEquals(1L, ledger.counters().get("silent.choke_advisory"),
            "an abstain must increment the surface's silent counter — without it, "
            + "a surface that never speaks is indistinguishable from one never used");
    }

    @Test
    void record_rolled_back_writes_a_failure_mode_candidate() {
        advisor.record(new Outcome("compose_method", "compose_method", "com.example.OrderService",
            Outcome.ROLLED_BACK, List.of("Order.java"), "undo-123",
            List.of("parity gate failed: compile 2 errors")));

        assertEquals(1L, store.count());
        List<StoredEntry> found = store.query(new RecallQuery("com.example.OrderService", null, null, null, null));
        assertEquals(1, found.size());
        assertEquals("failure_mode", found.get(0).type());
        assertEquals(ExperienceEntry.CANDIDATE, found.get(0).status());
    }

    @Test
    void record_applied_is_dropped_as_jdt_derivable() {
        advisor.record(new Outcome("rename_symbol", "rename_symbol", "com.example.Foo",
            Outcome.APPLIED, List.of("Foo.java"), "undo-9", List.of()));
        assertEquals(0L, store.count(), "a successful apply is not admitted");
    }

    @Test
    void record_flagged_writes_a_hazard() {
        advisor.record(new Outcome("inline_singleton", "inline_singleton", "com.example.Config",
            Outcome.FLAGGED, List.of(), null, List.of("purity check flagged shared state")));
        assertEquals(1L, store.count());
        assertEquals("hazard",
            store.query(new RecallQuery("com.example.Config", null, null, null, null)).get(0).type());
    }

    @Test
    void record_dedups_by_scope_and_summary() {
        Outcome o = new Outcome("compose_method", "compose_method", "com.example.OrderService",
            Outcome.ROLLED_BACK, List.of(), "undo-1", List.of("failed once"));
        advisor.record(o);
        advisor.record(o);   // identical scope + summary → deduped
        assertEquals(1L, store.count());
    }

    @Test
    void adviseBefore_with_non_fqn_target_and_no_match_is_empty() {
        assertFalse(advisor.adviseBefore("compose_method", "com.example.OrderService").size() > 0);
        assertTrue(advisor.adviseBefore("compose_method", "not/a/fqn path").isEmpty());
    }
}
