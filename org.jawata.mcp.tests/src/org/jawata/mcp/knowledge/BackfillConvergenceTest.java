package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.jawata.mcp.JawataApplication;

/**
 * Sprint 27a Stage 3b — the backfill is a STARTUP RECONCILIATION: it embeds
 * batch after batch until the delta is zero, in ONE resident lifetime.
 *
 * <p>The defect it fixes: the old code embedded one batch of 1,000 per start and
 * stopped, so a store larger than that stayed permanently part-embedded (the
 * live store sat at 1,037 of 2,080). This drives the extracted loop
 * ({@link JawataApplication#reconcileEmbeddings}) directly — the actual
 * deliverable, not a proxy — on a store LARGER than one batch, and proves it
 * reaches zero, stops there without spinning, and exits cleanly (and resumably)
 * on an interrupt.</p>
 */
class BackfillConvergenceTest {

    private H2ExperienceStore store;
    private EmbeddingIndex index;

    @BeforeEach
    void setUp() {
        EmbeddingService svc = EmbeddingService.shared();
        Assumptions.assumeTrue(svc.available(),
            "no embedder — convergence cannot be exercised in this run");
        store = H2ExperienceStore.openMemory();
        index = new EmbeddingIndex(store, svc);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    /** Store N entries and then STRIP their vectors — the real unembedded state,
     *  as a pre-embedding store carries, or as a Stage-4 identity bump leaves. */
    private void seedUnembedded(int n) throws Exception {
        for (int i = 0; i < n; i++) {
            store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "background lesson number " + i,
                    Confidence.MEDIUM).details("some detail for row " + i).build()).build());
        }
        try (var st = store.sharedConnection().createStatement()) {
            st.executeUpdate(
                "UPDATE experience_entry SET embedding = NULL, embedder_identity = NULL");
        }
        assertTrue(index.totalCount("experience_entry")
            - index.embeddedCount("experience_entry") >= n,
            "precondition: all " + n + " seeded rows must be unembedded before reconciling");
    }

    @Test
    void a_store_larger_than_one_batch_reaches_zero_remaining() throws Exception {
        // Batch 100, store 250 — three passes, where the OLD one-batch code left
        // 150 rows permanently without a vector.
        seedUnembedded(250);

        int embedded = JawataApplication.reconcileEmbeddings(index, 100, () -> false);

        assertEquals(250, embedded, "every unembedded row must get a vector in one lifetime");
        assertEquals(0, index.totalCount("experience_entry")
            - index.embeddedCount("experience_entry"),
            "the delta must be CLOSED — zero remaining, not one batch's worth left");
    }

    @Test
    void it_stops_at_delta_zero_and_does_not_spin() throws Exception {
        seedUnembedded(120);
        JawataApplication.reconcileEmbeddings(index, 100, () -> false);   // converge

        // A second run has nothing to do. It must return 0 and TERMINATE — the
        // loop's exit is backfill returning 0, not a condition it re-polls, so a
        // closed delta cannot spin.
        int again = JawataApplication.reconcileEmbeddings(index, 100, () -> false);
        assertEquals(0, again, "a closed delta embeds nothing and the loop ends");
    }

    @Test
    void an_interrupt_ends_the_loop_early_with_a_resumable_partial() throws Exception {
        seedUnembedded(250);

        // Interrupt after exactly one pass: false on the first check, true after.
        BooleanSupplier afterOnePass = new BooleanSupplier() {
            private int checks = 0;
            @Override public boolean getAsBoolean() {
                return checks++ >= 1;
            }
        };
        int embedded = JawataApplication.reconcileEmbeddings(index, 100, afterOnePass);

        assertEquals(100, embedded, "exactly one batch ran before the interrupt");
        long remaining = index.totalCount("experience_entry")
            - index.embeddedCount("experience_entry");
        assertEquals(150, remaining,
            "the rest is left for the next start — resumable, because each embedded "
            + "row was persisted as it went, not lost");
    }
}
