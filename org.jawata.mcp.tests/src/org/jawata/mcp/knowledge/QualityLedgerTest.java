package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sprint 27 D6 — the quality ledger's own behaviour.
 *
 * <p>The wiring proof (every counter class advancing from a real session with
 * no manual step) is {@code QualityLedgerWiringTest}. This class pins the
 * contract the ledger itself owes: counts persist, an unreadable table is
 * REPORTED rather than returned as zeros, the correlation label cannot be
 * separated from the numbers, and a broken counter never breaks the work.</p>
 */
class QualityLedgerTest {

    private H2ExperienceStore store;
    private QualityLedger ledger;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        ledger = new QualityLedger(store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void counters_start_empty_and_accumulate() {
        assertTrue(ledger.counters().isEmpty(),
            "a fresh store has measured nothing, and says so by being empty");

        ledger.fired(QualityLedger.SURFACE_PRIMER);
        ledger.fired(QualityLedger.SURFACE_PRIMER);
        ledger.fired(QualityLedger.SURFACE_QUESTION_HOOK);

        Map<String, Long> c = ledger.counters();
        assertEquals(2L, c.get("fired.primer"));
        assertEquals(1L, c.get("fired.question_hook"));
    }

    @Test
    void the_gate_counts_checked_and_the_verdict_per_criterion() {
        ledger.gate("symbol", true);
        ledger.gate("symbol", false);
        ledger.gate("symptom", false);

        Map<String, Long> c = ledger.counters();
        assertEquals(2L, c.get("gate.symbol.checked"));
        assertEquals(1L, c.get("gate.symbol.passed"));
        assertEquals(1L, c.get("gate.symbol.rejected"));
        assertEquals(1L, c.get("gate.symptom.checked"));
        assertEquals(1L, c.get("gate.symptom.rejected"));
        assertFalse(c.containsKey("gate.symptom.passed"),
            "a criterion that never passed shows no passed row — absence, not a zero");
    }

    @Test
    void conformity_is_derived_and_labeled_as_derived() {
        ledger.warned();
        ledger.warned();
        ledger.warned();
        ledger.defected();

        @SuppressWarnings("unchecked")
        Map<String, Object> steer =
            (Map<String, Object>) ledger.statsBlock().get("steering");
        assertEquals(3L, steer.get("warned"));
        assertEquals(1L, steer.get("defected"));
        assertEquals(2L, steer.get("warned_and_not_re_attempted"));
        assertTrue(String.valueOf(steer.get("warned_and_not_re_attempted_note"))
            .contains("DERIVED"),
            "conforming is an ABSENCE — the block must not present it as observed");
    }

    @Test
    void the_numbers_never_arrive_without_the_sentence_that_reads_them() {
        ledger.fired(QualityLedger.SURFACE_CHOKE_PRECEDENT);
        Map<String, Object> block = ledger.statsBlock();
        Object label = block.get("how_to_read");
        assertNotNull(label, "a bare table of counts invites the causal reading it "
            + "cannot support; the label is not optional");
        assertTrue(String.valueOf(label).contains("CORRELATION"));
        assertTrue(String.valueOf(label).contains("thin"),
            "and it says the early numbers are too thin to read");
    }

    /** Break the read for real. Closing the store does NOT do it — the store
     *  transparently reopens its connection, and a reopened in-memory database
     *  is legitimately empty. Dropping the table is a genuine read failure. */
    private void breakTheCounterTable() {
        try (java.sql.Statement s = store.sharedConnection().createStatement()) {
            s.execute("DROP TABLE quality_counter");
        } catch (java.sql.SQLException e) {
            throw new AssertionError("could not stage the failure", e);
        }
    }

    @Test
    void an_unreadable_table_is_reported_not_returned_as_nothing() {
        breakTheCounterTable();
        Map<String, Long> c = ledger.counters();
        assertFalse(c.isEmpty(),
            "an empty map here would read as 'nothing measured' — the deepest bug "
            + "class in this codebase. A failure must be IN the result.");
        assertTrue(c.keySet().iterator().next().startsWith("(unavailable"));
    }

    @Test
    void a_broken_counter_never_breaks_the_work_it_measures() {
        breakTheCounterTable();
        // Every write path must swallow, because measurement that can break the
        // thing being measured is worse than no measurement.
        ledger.fired(QualityLedger.SURFACE_SEAT);
        ledger.warned();
        ledger.defected();
        ledger.outcomeAfter("compiled");
        ledger.gate("symbol", true);
    }

    @Test
    void counter_names_are_normalized_so_a_surface_cannot_split_itself() {
        ledger.fired("Choke Advisory");
        ledger.fired("choke_advisory");
        assertEquals(2L, ledger.counters().get("fired.choke_advisory"),
            "one surface, one row — casing or spacing must not fork the count");
        ledger.outcomeAfter(null);
        assertEquals(1L, ledger.counters().get("outcome_after.unknown"),
            "an unnamed outcome is counted as unknown, not silently dropped");
    }

    @Test
    void counts_survive_a_reopen_because_they_ride_the_v7_schema() {
        java.nio.file.Path dir = null;
        try {
            dir = java.nio.file.Files.createTempDirectory("jawata-quality");
            java.nio.file.Path file = dir.resolve("store");
            H2ExperienceStore first = H2ExperienceStore.open(file);
            try {
                new QualityLedger(first).fired(QualityLedger.SURFACE_PRIMER);
            } finally {
                first.close();
            }
            H2ExperienceStore second = H2ExperienceStore.open(file);
            try {
                assertEquals(1L, new QualityLedger(second).counters().get("fired.primer"),
                    "the ledger persists on v7 — no v8, and no loss across a restart");
            } finally {
                second.close();
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("could not create a temp store", e);
        } finally {
            if (dir != null) {
                deleteTree(dir);
            }
        }
    }

    private static void deleteTree(java.nio.file.Path dir) {
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (java.io.IOException ignored) {
                    // best effort on a temp dir
                }
            });
        } catch (java.io.IOException ignored) {
            // best effort on a temp dir
        }
    }
}
