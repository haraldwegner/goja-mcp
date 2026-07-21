package org.jawata.mcp.embed;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Sprint 27 C2 — measures single-cue embed latency and records it.
 *
 * <p>The plan's budget is p50 ≤ 100 ms on the scalar backend and ≤ 40 ms on the
 * Vector API. This test MEASURES and PRINTS those numbers for the dossier; the
 * budget itself is judged there, from a quiet machine, not asserted here.</p>
 *
 * <p>That split is deliberate. A tight timing assertion inside a sharded suite
 * is a flake generator — the same lesson C0-F1 recorded about a studio test that
 * failed only under load. The assertion below is therefore a CATASTROPHE bound
 * (something is structurally broken, not merely slow), while the real
 * expected-vs-actual comparison happens against a recorded measurement.</p>
 */
class EmbedLatencyTest {

    /** Not the budget — the "something is fundamentally wrong" line. */
    private static final long CATASTROPHE_MS = 2000;

    /** The ONE workload both backends are measured on — mixed lengths, because
     *  cost scales with token count and a single short cue would flatter both. */
    private static final String[] CUES = {
        "never cancel before broker confirms",
        "the app starts but nothing paints inside the frame",
        "how close should two vectors be before we call them the same thing",
        "com.jats2.gateways.alpol.alpaca.orders.OrderProcessor#getAllPositions",
        "the position monitor alarms about a mismatch whenever we bet against a stock",
    };

    private static long[] measure(MiniLmEmbedder e) {
        for (int i = 0; i < 10; i++) {                       // warm the JIT
            e.embed(CUES[i % CUES.length]);
        }
        long[] samples = new long[40];
        for (int i = 0; i < samples.length; i++) {
            long t0 = System.nanoTime();
            e.embed(CUES[i % CUES.length]);
            samples[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        Arrays.sort(samples);
        return samples;
    }

    private static void report(String label, MiniLmEmbedder e, long[] s) {
        System.out.printf("[latency] %-22s precision=%s p50=%dms p95=%dms max=%dms%n",
            label, e.precision(), s[s.length / 2], s[(int) (s.length * 0.95)], s[s.length - 1]);
    }

    @Test
    void single_cue_embed_latency_is_measured_on_the_active_backend() {
        MiniLmEmbedder e = MiniLmEmbedder.bundled();
        long[] s = measure(e);
        report(e.backend(), e, s);
        assertTrue(s[s.length / 2] < CATASTROPHE_MS,
            "single-cue embed p50 " + s[s.length / 2] + "ms is structurally wrong (backend "
            + e.backend() + ")");
    }

    @Test
    void the_scalar_degrade_path_is_measured_on_the_SAME_workload() {
        // The flagless path is what ships by default, so its cost must be known
        // rather than assumed - and measured on the identical workload, or the
        // two numbers are not comparable and the speedup claim is meaningless.
        MiniLmEmbedder e = MiniLmEmbedder.bundled(MatMuls.scalar());
        long[] s = measure(e);
        report("scalar(forced)", e, s);
        assertTrue(s[s.length / 2] < CATASTROPHE_MS);
    }
}
