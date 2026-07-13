package com.example.debug;

/**
 * Sprint 24 fixture — the app the debugger stages attach to. It runs until it is
 * killed, so every proof is about a LIVE program, not a replayed recording.
 *
 * <p>It deliberately contains one of each thing an interactive debugger must
 * handle: a named seam to break on, a hot loop that must keep RUNNING while a
 * non-suspending probe watches it, a field that changes (so a watchpoint has
 * something to catch), and a site that throws (so an exception breakpoint has
 * something to catch).</p>
 */
public final class DebugTarget {

    /** Changes on every tick — the field a watchpoint watches. */
    private static int lastSignal;

    /** Flipped by the debugger in the hypothesis-testing proofs (D7). */
    private static boolean tripped;

    public static void main(String[] args) throws Exception {
        int iteration = 0;
        while (true) {
            int signal = computeSignal(iteration);   // the named seam
            lastSignal = signal;

            if (signal % 17 == 0) {
                try {
                    riskyStep(signal);               // the exception site
                } catch (IllegalStateException expected) {
                    // Swallowed on purpose: an exception breakpoint should still catch it.
                }
            }
            if (tripped) {
                System.out.println("tripped at iteration " + iteration);
            }
            spin();                                  // the hot loop
            Thread.sleep(25);
            iteration++;
        }
    }

    /** The seam: a method worth breaking on, with an argument and a local. */
    static int computeSignal(int iteration) {
        int doubled = iteration * 2;
        int adjusted = doubled + offset();
        return adjusted;
    }

    static int offset() {
        return 7;
    }

    /** Throws every time it is called — the exception site. */
    static void riskyStep(int signal) {
        throw new IllegalStateException("signal " + signal + " is divisible by 17");
    }

    /**
     * The hot loop. A non-suspending probe (D8) must stream values out of this
     * while it demonstrably keeps running — that is the whole point of the proof.
     */
    static void spin() {
        long burned = 0;
        for (int i = 0; i < 50_000; i++) {
            burned += i % 7;
        }
        if (burned < 0) {
            throw new AssertionError("unreachable; keeps the loop from being optimized away");
        }
    }

    public static int getLastSignal() {
        return lastSignal;
    }

    private DebugTarget() {
    }
}
