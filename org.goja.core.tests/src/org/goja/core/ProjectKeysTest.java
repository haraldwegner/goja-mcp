package org.goja.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectKeysTest {

    @Test
    @DisplayName("derive() returns sanitized lowercase last path segment")
    void derive_returnsSanitizedLastSegment() {
        assertEquals("payments-api", ProjectKeys.derive(Paths.get("/home/u/CursorProjects/payments_api")));
        assertEquals("goja-mcp", ProjectKeys.derive(Paths.get("/home/u/CursorProjects/goja-mcp")));
        assertEquals("execsim-java", ProjectKeys.derive(Paths.get("/home/u/CursorProjects/EXECSIM-Java")));
    }

    @Test
    @DisplayName("derive() collapses non-alphanumeric runs into single hyphens")
    void derive_collapsesNonAlphanumeric() {
        assertEquals("foo-bar", ProjectKeys.derive(Paths.get("/x/foo___bar")));
        assertEquals("foo-bar", ProjectKeys.derive(Paths.get("/x/foo  bar")));
        assertEquals("a-b-c", ProjectKeys.derive(Paths.get("/x/a.b.c")));
    }

    @Test
    @DisplayName("derive() trims leading and trailing hyphens")
    void derive_trimsHyphens() {
        assertEquals("foo", ProjectKeys.derive(Paths.get("/x/__foo__")));
        assertEquals("foo-bar", ProjectKeys.derive(Paths.get("/x/--foo-bar--")));
    }

    @Test
    @DisplayName("derive() falls back to 'project' for pathological inputs")
    void derive_fallbackForPathologicalInputs() {
        assertEquals("project", ProjectKeys.derive(Paths.get("/x/___")));
        assertEquals("project", ProjectKeys.derive(Paths.get("/x/...")));
    }

    @Test
    @DisplayName("disambiguate() yields collision-free keys for paths with the same last segment")
    void disambiguate_resolvesCollisions() {
        Path a = Paths.get("/repos/foo/strategies");
        Path b = Paths.get("/repos/bar/strategies");

        String baseA = ProjectKeys.derive(a);
        String baseB = ProjectKeys.derive(b);
        assertEquals(baseA, baseB, "Both paths share the last segment, so base keys collide");

        String keyA = ProjectKeys.disambiguate(baseA, a);
        String keyB = ProjectKeys.disambiguate(baseB, b);
        assertNotEquals(keyA, keyB, "Disambiguated keys must differ across distinct paths");
        assertTrue(keyA.startsWith(baseA + "-"));
        assertTrue(keyB.startsWith(baseB + "-"));
    }

    @Test
    @DisplayName("disambiguate() is deterministic for the same input")
    void disambiguate_isDeterministic() {
        Path p = Paths.get("/repos/foo/strategies");
        String first = ProjectKeys.disambiguate("strategies", p);
        String second = ProjectKeys.disambiguate("strategies", p);
        assertEquals(first, second, "Same path should always produce the same disambiguated key");
    }
}
