package com.example.cross;

import com.example.alpha.AlphaLib;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Module-b test exercising module-a code — the C3 cross-module proof. */
public class CrossModuleTest {

    @Test
    void magicComesFromModuleA() {
        assertEquals(42, new AlphaLib().magic());
    }

    @Test
    void tagComesFromModuleA() {
        assertEquals("alpha:x", new AlphaLib().tag("x"));
    }
}
