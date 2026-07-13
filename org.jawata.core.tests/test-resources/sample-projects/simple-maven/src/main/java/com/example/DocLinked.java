package com.example;

/** Sprint 23 (D11) fixture — one REAL caller + one Javadoc-only reference. */
public class DocLinked {

    public int callee() {
        return 4;
    }

    public int realCaller() {
        return callee() + 1;
    }
}

/**
 * Documentation-only reference: {@link DocLinked#callee()} — this must NEVER
 * surface as a caller in the incoming call hierarchy.
 */
class DocLinkedNeighbour {
}
