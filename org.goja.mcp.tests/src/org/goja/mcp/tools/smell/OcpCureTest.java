package org.goja.mcp.tools.smell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 19 — the OCP-cure mapping (churn trace -> refactor_to_pattern recipes). */
class OcpCureTest {

    @Test
    @DisplayName("churn traces map to the abstraction recipes")
    void churnTracesMapToRecipes() {
        assertTrue(OcpCure.recipesFor("divergent_change")
            .containsAll(java.util.List.of("refactor_to_state", "refactor_to_command_dispatcher", "form_template_method")));
        assertTrue(OcpCure.recipesFor("shotgun_surgery").contains("refactor_to_state"));
        assertEquals(java.util.List.of(), OcpCure.recipesFor("long_method"));
    }

    @Test
    @DisplayName("the hint points at refactor_to_pattern")
    void hintPointsAtRecipes() {
        assertTrue(OcpCure.HINT.contains("refactor_to_pattern"), OcpCure.HINT);
        assertTrue(OcpCure.HINT.contains("OCP cure"), OcpCure.HINT);
    }
}
