package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.ToolExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 26a D2 (C2): the weighted composer, the target key, and the baseline
 * retriever over the lane — the read half of the loop, unit-level.
 */
class PrecedentComposeTest {

    private static ToolExperience row(String tool, String outcome) {
        return new ToolExperience("s1", tool + " com.foo.Bar", tool, outcome, "{}");
    }

    @Test
    void a_negative_precedent_warns_with_a_justification_cost() {
        String steer = PrecedentSteer.compose("analyze", List.of(
            row("extract", ToolExperience.OUTCOME_REVERTED),
            row("extract", ToolExperience.OUTCOME_ERROR)));
        assertTrue(steer != null && steer.contains("⚠ PRECEDENT"), steer);
        assertTrue(steer.contains("extract"), "names the tool that failed here before");
        assertTrue(steer.contains("2×"), "the weight is the agreement count");
        assertTrue(steer.contains("justification"), "defecting costs a written justification");
    }

    @Test
    void a_consistent_positive_precedent_reinforces_without_a_warning() {
        String steer = PrecedentSteer.compose("analyze", List.of(
            row("rename_symbol", ToolExperience.OUTCOME_COMPILED),
            row("rename_symbol", ToolExperience.OUTCOME_COMPILED)));
        assertTrue(steer != null && steer.contains("PRECEDENT"), steer);
        assertTrue(steer.contains("rename_symbol") && steer.contains("worked"), steer);
        assertTrue(!steer.contains("⚠"), "a positive precedent carries no warning");
    }

    @Test
    void a_single_positive_is_below_the_threshold() {
        assertNull(PrecedentSteer.compose("analyze",
            List.of(row("rename_symbol", ToolExperience.OUTCOME_COMPILED))),
            "one good case is not yet a pattern");
    }

    @Test
    void no_precedents_no_steer() {
        assertNull(PrecedentSteer.compose("analyze", List.of()));
        assertNull(PrecedentSteer.compose("analyze", null));
    }

    @Test
    void the_baseline_retriever_reads_the_lane() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        ToolExperienceStore tes = new ToolExperienceStore(store);
        tes.append(new ToolExperience("s1", "extract com.foo.Bar#baz method",
            "extract", ToolExperience.OUTCOME_REVERTED, "{}"));
        tes.append(new ToolExperience("s1", "rename_symbol com.other.Thing",
            "rename_symbol", ToolExperience.OUTCOME_COMPILED, "{}"));

        PrecedentRetriever r = new KeywordPrecedentRetriever(tes);
        List<ToolExperience> hits = r.retrieve("com.foo.Bar", 10);
        assertEquals(1, hits.size(), "matches on the target, not the unrelated row");
        assertEquals("extract", hits.get(0).tool());
        assertTrue(r.retrieve("", 10).isEmpty(), "a blank target retrieves nothing");
        store.close();
    }

    @Test
    void target_is_the_salient_identifier_tool_independent() {
        ObjectMapper om = new ObjectMapper();
        ObjectNode a = om.createObjectNode();
        a.put("symbol", "com.foo.Bar#baz");
        a.put("kind", "method");
        assertEquals("com.foo.Bar#baz",
            ToolExperienceRecorder.target("rename_symbol", a),
            "target is the thing worked on (symbol), NOT the tool or the kind");
        assertEquals("", ToolExperienceRecorder.target("compile_workspace",
            om.createObjectNode()), "a targetless call yields a blank target");
        // filePath collapses to the basename (a keyword value, not a path).
        ObjectNode f = om.createObjectNode();
        f.put("filePath", "src/main/java/com/foo/Widget.java");
        assertEquals("Widget.java", ToolExperienceRecorder.target("format", f));
    }
}
