package com.example.debug;

/**
 * Sprint 24 audit fixture — a trivial class that {@link DoubleLoadTarget} deliberately loads
 * under two different classloaders at once, so the debugger's handling of an ambiguous
 * class name (one FQN, several loaded classes — the ordinary OSGi case) can be tested.
 */
public final class DoubleLoaded {

    private int touched;

    public int touch() {
        return ++touched;
    }
}
