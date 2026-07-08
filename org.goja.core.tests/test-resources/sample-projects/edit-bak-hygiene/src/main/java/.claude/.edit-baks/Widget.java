package com.example;

/**
 * A STALE editor/agent scratch copy of {@link Widget}, living under
 * {@code src/main/java/.claude/.edit-baks/}. It declares the SAME package and
 * type name, so without the Sprint 22a P0-c source-entry exclusion JDT would
 * index it as a second {@code com.example.Widget} — a duplicate type that
 * pollutes search_symbols and get_call_hierarchy with phantom results and
 * makes the project fail to compile ("type already defined"). The exclusion
 * ({@code **}/.claude/**) must keep this file out of the Java model entirely.
 */
public class Widget {

    private int state;

    public void poke() {
        state++;
    }

    public int state() {
        return state;
    }
}
