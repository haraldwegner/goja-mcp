package com.example.pathological.slow;

import org.junit.jupiter.api.Test;

/** Second slow class — see {@link SlowAlphaTest}. */
public class SlowBetaTest {

    @Test
    void slowOne() throws InterruptedException {
        Thread.sleep(1500);
    }

    @Test
    void slowTwo() throws InterruptedException {
        Thread.sleep(1500);
    }
}
