package com.example.cov;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Exercises EXACTLY the known subset — see Covered's truth table. */
public class CoveredTest {

    @Test
    void exercisesAlwaysCalledAndOneBranchArm() {
        Covered c = new Covered();
        assertEquals(5, c.alwaysCalled(4));
        assertEquals("yes", c.branchy(true));
        assertNotNull(c.lambdaHolder()); // obtained, deliberately NOT run
        assertEquals("alpha", CovEnum.ALPHA.tag());
        assertEquals("helped", CovHelper.help()); // covered — the rename-proof target
    }
}
