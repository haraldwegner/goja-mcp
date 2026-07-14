package com.example.debug;

/**
 * Sprint 24 (D11/Stage 16) fixture — a DELIBERATELY hot method for the profiling
 * floor's {@code hotspots} action. Unlike {@code DebugTarget#spin}, which sleeps
 * between short bursts (fine for a non-suspending probe proof, useless for CPU
 * sampling — most samples would land in {@code Thread.sleep}, not the loop), this
 * program never sleeps: the hot method dominates CPU time so completely that a
 * short sampling window reliably ranks it #1.
 */
public final class HotLoopTarget {

    public static void main(String[] args) throws Exception {
        // Runs until killed — the test controls the session's lifetime, same as
        // every other long-running debug-target fixture.
        long sink = 0;
        while (true) {
            sink += burnCpu();
            if (sink == Long.MIN_VALUE) {
                // Unreachable; keeps burnCpu() from being optimized away entirely.
                throw new AssertionError("unreachable: " + sink);
            }
        }
    }

    /** The deliberately hot method — CPU-bound, no sleeping, no I/O. */
    static long burnCpu() {
        long total = 0;
        for (int i = 0; i < 5_000_000; i++) {
            total += (i * 31) ^ (i >>> 3);
        }
        return total;
    }

    private HotLoopTarget() {
    }
}
