package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.ToolExperienceStore;
import org.jawata.mcp.learn.ToolExperienceRecorder;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 26a D4 (C3): the edit-switch ML model is RETIRED. Its classes are gone
 * (absence proven), its status kinds answer HONESTLY (say retired — never an
 * empty-result lie, never an error), and the experience loop that replaces it
 * still captures after the retirement.
 */
class EditMlRetirementTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    void the_edit_ml_model_classes_are_gone() {
        for (String fqn : List.of(
                "org.jawata.mcp.learn.OnlineLogreg",
                "org.jawata.mcp.learn.HandTree",
                "org.jawata.mcp.learn.Learner",
                "org.jawata.mcp.learn.RollingRecord",
                "org.jawata.mcp.learn.FeatureVector",
                "org.jawata.mcp.learn.LearnerService")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(fqn),
                fqn + " must be retired (deleted), not merely unused");
        }
    }

    /**
     * v3.3.1: the retired kinds are GONE, not merely unadvertised. v3.3.0 kept
     * them answering "retired" for a lingering caller — but there is no installed
     * base, so that branch served nobody and was dead weight. A kind that no
     * longer exists must say so, and must say what DOES exist.
     */
    @Test
    void the_retired_learning_kinds_are_gone_and_the_refusal_names_the_alternatives() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            ExperienceTool tool = new ExperienceTool(() -> null, store);
            for (String kind : List.of("train", "learner_status", "observe_edit")) {
                ToolResponse r = tool.execute(OM.createObjectNode().put("kind", kind));
                assertFalse(r.isSuccess(), kind + " is retired — it must not answer as a kind");
                assertNotNull(r.getError(), kind + " refuses with a structured error");
                String said = r.getError().getMessage() + " " + r.getError().getHint();
                assertTrue(said.contains("recall"),
                    kind + " refusal points at the kinds that DO exist: " + said);
            }
        } finally {
            store.close();
        }
    }

    /** The advertised schema must not name a capability that no longer exists. */
    @Test
    void the_schema_no_longer_advertises_the_retired_kinds() {
        ExperienceTool tool = new ExperienceTool(() -> null, null);
        String schema = String.valueOf(tool.getInputSchema());
        for (String kind : List.of("train", "learner_status", "observe_edit")) {
            assertFalse(schema.contains(kind),
                "the schema still advertises the retired kind '" + kind + "'");
        }
    }

    @Test
    void the_experience_loop_still_captures_after_retirement() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        ToolExperienceStore tes = new ToolExperienceStore(store);
        ToolExperienceRecorder rec = new ToolExperienceRecorder(tes);
        rec.onCall("s1", "rename_symbol", OM.createObjectNode().put("symbol", "com.foo.Bar"),
            ToolResponse.success(Map.of("filesModified", List.of("Bar.java"))));
        rec.onCall("s1", "compile_workspace", null, ToolResponse.success(Map.of("errorCount", 0)));
        assertEquals(1L, tes.count(),
            "the experience loop still captures the edit→compile outcome with the models gone");
        store.close();
    }
}
