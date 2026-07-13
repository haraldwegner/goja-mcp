package com.example.cov;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Deterministically "flaky": always covers flip(false); covers flip(true)
 * ONLY when -Dcov.flip=true. Two runs of the SAME selection with different
 * vmArgs produce a line-level coverage XOR — the stability probe.
 */
public class FlipTest {

    @Test
    void flipsByProperty() {
        Flip f = new Flip();
        assertEquals(2, f.flip(false));
        if (Boolean.getBoolean("cov.flip")) {
            assertEquals(1, f.flip(true));
        }
    }
}
