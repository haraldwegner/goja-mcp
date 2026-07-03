package org.goja.mcp.knowledge;

import org.goja.mcp.domain.Outcome;
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

        List<String> miss = advisor.adviseBefore("compose_method", "com.other.Unrelated");
        assertTrue(miss.isEmpty(), "no fit → empty advice (absence)");
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
