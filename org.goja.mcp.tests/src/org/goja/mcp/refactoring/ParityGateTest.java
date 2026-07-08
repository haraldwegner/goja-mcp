package org.goja.mcp.refactoring;

import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 18 — the compile half of the parity gate: a clean project passes; an
 * error-severity problem marker fails it.
 *
 * <p>Like {@code CompileWorkspaceToolTest}, this exercises the marker-READ path
 * that {@link ParityGate} owns and attaches a real {@link IMarker#PROBLEM} marker
 * via {@link IFile#createMarker} — the same API JDT's compiler uses — rather than
 * relying on the build job firing (it does not fire in the headless Tycho-test
 * runtime; in the resident, a real build supplies the markers this reads).</p>
 */
class ParityGateTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        // compile-clean: a genuinely-clean 0/0 baseline. simple-maven can no longer serve
        // this — post Sprint 22a P0-a the JavaBuilder fires and surfaces its
        // deliberately-unresolvable imports as real errors, so it is never gate-clean.
        service = helper.loadProjectCopy("compile-clean");
    }

    @Test
    @DisplayName("a clean project passes the gate")
    void cleanProject_passes() {
        ParityGate.Result result = ParityGate.compile(service);
        assertTrue(result.clean(), () -> "expected clean, errors: " + result.errors());
        assertEquals(0, result.errorCount());
    }

    @Test
    @DisplayName("a real compile error fails the gate")
    void errorMarker_failsGate() throws Exception {
        assertTrue(ParityGate.compile(service).clean(), "precondition: clean baseline");

        // Post Sprint 22a P0-a the JavaBuilder fires in the Tycho-test runtime and WIPES
        // synthetic IMarker.PROBLEM markers off the CUs it compiles — so drop a genuinely
        // broken source file (the non-vacuous pattern, per Stage 0) and let the builder
        // surface the error that the gate then reads.
        Path broken = service.getProjectRoot().resolve("src/main/java/com/example/Broken.java");
        Files.writeString(broken,
            "package com.example;\npublic class Broken {\n    int oops() { return ; }\n}\n");

        ParityGate.Result result = ParityGate.compile(service);
        assertFalse(result.clean(), "the real compile error must fail the gate: " + result.errors());
        assertTrue(result.errorCount() >= 1, "at least one error expected");
    }
}
