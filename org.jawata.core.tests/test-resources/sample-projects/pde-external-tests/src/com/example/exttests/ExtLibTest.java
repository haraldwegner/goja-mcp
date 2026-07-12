package com.example.exttests;

import com.example.ext.ExtLib;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sprint 23 (D7) — the PDE test bundle: Require-Bundle onto the sibling
 * fixture (workspace pool) whose OWN deps come from the EXTERNAL pool;
 * running through the forked runner proves the resolved PDE classpath
 * end-to-end (jackson is exercised at runtime via jsonEcho).
 */
public class ExtLibTest {

    @Test
    void magicIsSeven() {
        assertEquals(7, new ExtLib().magic());
    }

    @Test
    void jsonEchoRoundtrips() throws Exception {
        assertEquals("{\"a\":1}", new ExtLib().jsonEcho("{ \"a\" : 1 }"));
    }
}
