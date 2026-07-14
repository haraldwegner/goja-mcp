package com.example.debug;

/**
 * Sprint 24 (D12/Stage 17) fixture — a named seam under a CLOSED-LOOP caller: the
 * next call always starts a fixed planned gap after the PREVIOUS one ended, which
 * is exactly the pattern that suffers from coordinated omission (a slow call
 * silently swallows the calls a real, open-loop caller would still have issued
 * during the stall) — the shape {@code latency_seam}'s correction exists for.
 *
 * <p>With {@code -Djawata.latency.slowdown=true}, 1 call in every {@value
 * #SLOWDOWN_EVERY} pays an extra {@value #SLOWDOWN_EXTRA_MILLIS}ms inside the
 * seam — a controllable, deterministic tail. 2% of calls is comfortably past
 * both the p99 and p999 cutoffs, so both percentiles must move while p50 (deep
 * in the fast 98%) must not.</p>
 */
public final class LatencySeamTarget {

    private static final int PLANNED_GAP_MILLIS = 5;
    private static final int SLOWDOWN_EVERY = 50;
    private static final int SLOWDOWN_EXTRA_MILLIS = 50;

    private static long callCount;

    public static void main(String[] args) throws Exception {
        boolean injectSlowdown = Boolean.getBoolean("jawata.latency.slowdown");
        // -Djawata.latency.hot=true removes the planned gap, so the seam fires as fast as the
        // CPU allows — thousands of calls/s. That is what overflows the bounded probe-event
        // ring, which is what latency_seam must now detect and REPORT rather than silently
        // computing percentiles on a biased subset (Sprint-24 audit T1.5).
        boolean hot = Boolean.getBoolean("jawata.latency.hot");
        while (true) {
            seam(injectSlowdown);
            if (!hot) {
                Thread.sleep(PLANNED_GAP_MILLIS);
            }
        }
    }

    /** The traced seam — a small, deterministic amount of real CPU work every call. */
    static long seam(boolean injectSlowdown) {
        callCount++;
        if (injectSlowdown && callCount % SLOWDOWN_EVERY == 0) {
            sleepQuietly(SLOWDOWN_EXTRA_MILLIS);
        }
        return busyWork();
    }

    /** CPU-bound (not sleep-bound), so the baseline cost is low and stable, not OS-jitter-prone. */
    private static long busyWork() {
        long total = 0;
        for (int i = 0; i < 100_000; i++) {
            total += (i * 7) ^ (i >>> 2);
        }
        return total;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private LatencySeamTarget() {
    }
}
