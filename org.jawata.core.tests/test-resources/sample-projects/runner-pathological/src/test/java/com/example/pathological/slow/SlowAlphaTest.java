package com.example.pathological.slow;

import org.junit.jupiter.api.Test;

/**
 * Deliberately slow (but finishing) — with {@link SlowBetaTest} it makes
 * per-class progress observable BEFORE completion through the async
 * run_tests status surface (Stage 2 streaming gate).
 */
public class SlowAlphaTest {

    @Test
    void slowOne() throws InterruptedException {
        Thread.sleep(1500);
    }

    @Test
    void slowTwo() throws InterruptedException {
        Thread.sleep(1500);
    }
}
