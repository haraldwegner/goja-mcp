package org.goja.mcp.tools.nullness;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Sprint 15b — source-only nullness family detection (no annotation jars needed). */
class NullnessStyleTest {

    @Test
    @DisplayName("ofImport maps an FQN to its family")
    void ofImport() {
        assertEquals(NullnessStyle.JSPECIFY,
            NullnessStyle.ofImport("org.jspecify.annotations.Nullable").orElseThrow());
        assertEquals(NullnessStyle.ECLIPSE,
            NullnessStyle.ofImport("org.eclipse.jdt.annotation.NonNull").orElseThrow());
        assertEquals(NullnessStyle.JETBRAINS,
            NullnessStyle.ofImport("org.jetbrains.annotations.Nullable").orElseThrow());
        assertTrue(NullnessStyle.ofImport("java.util.List").isEmpty());
    }

    @Test
    @DisplayName("tally counts per family; FQNs are well-formed")
    void tally() {
        Map<NullnessStyle, Integer> t = NullnessStyle.tally(List.of(
            "org.jspecify.annotations.Nullable",
            "org.jspecify.annotations.NonNull",
            "org.jetbrains.annotations.NotNull",
            "java.util.Map"));
        assertEquals(2, t.get(NullnessStyle.JSPECIFY));
        assertEquals(1, t.get(NullnessStyle.JETBRAINS));
        assertFalse(t.containsKey(NullnessStyle.ECLIPSE));

        assertEquals("org.jspecify.annotations.Nullable", NullnessStyle.JSPECIFY.nullableFqn());
        assertEquals("org.jspecify.annotations.NonNull", NullnessStyle.JSPECIFY.nonnullFqn());
        assertEquals(NullnessStyle.JSPECIFY, NullnessStyle.DEFAULT);
    }

    @Test
    @DisplayName("no nullness imports → empty tally (the 'none' case)")
    void noneCase() {
        assertTrue(NullnessStyle.tally(List.of("java.util.List", "com.example.Foo")).isEmpty());
    }
}
