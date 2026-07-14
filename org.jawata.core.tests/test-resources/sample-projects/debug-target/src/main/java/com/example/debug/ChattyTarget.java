package com.example.debug;

/**
 * Sprint 24 audit (2026-07-14, T1.6) fixture — a program that prints a LOT to stdout, then
 * keeps running and does something observable.
 *
 * <p>A launched target's stdout was read only until the JDWP "Listening for transport …"
 * banner and then never drained again. A pipe holds ~64KB; past that, the target blocks in
 * {@code write()} forever — so any program that logs (a JATS journal replay logging per
 * event, exactly the D9 flagship use case) stalls before doing anything the debugger came
 * to see. This fixture writes far more than a pipe's worth up front, and only THEN sets the
 * field a watchpoint can catch — so if stdout is not being drained, {@code counter} never
 * moves and the proof fails.</p>
 */
public final class ChattyTarget {

    /** Only starts advancing AFTER the flood — a watchpoint here proves the flood drained. */
    static volatile int counter;

    public static void main(String[] args) throws Exception {
        // ~1 MB of output — comfortably more than any pipe buffer. If nothing is draining
        // the far end, the target blocks in println() somewhere in here and never returns.
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            line.append("chatter-");
        }
        String chunk = line.toString();
        for (int i = 0; i < 2000; i++) {
            System.out.println(i + " " + chunk);
        }
        System.out.flush();

        // Reached only if the flood drained. Now advance forever.
        while (true) {
            counter++;
            Thread.sleep(20);
        }
    }

    private ChattyTarget() {
    }
}
