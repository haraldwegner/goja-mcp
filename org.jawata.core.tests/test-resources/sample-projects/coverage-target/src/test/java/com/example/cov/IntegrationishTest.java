package com.example.cov;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Run with evidenceKind=integration in the attribution battery — covers
 * alwaysCalled TOO, so the unit-vs-integration ranking has a real subject.
 */
public class IntegrationishTest {

    @Test
    void broadPath() {
        Covered c = new Covered();
        assertEquals(5, c.alwaysCalled(4));
        assertEquals("yes", c.branchy(true));
        assertEquals("no", c.branchy(false));
    }
}
