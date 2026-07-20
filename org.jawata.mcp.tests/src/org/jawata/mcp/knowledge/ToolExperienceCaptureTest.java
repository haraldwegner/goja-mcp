package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.learn.ToolExperience;
import org.jawata.mcp.learn.ToolExperienceRecorder;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 26a D2 (C1): the experience loop's SELECTIVE capture — the positive
 * (outcome-bearing events land labeled rows) AND the negative (routine
 * reads/searches land nothing), plus the loud-on-failure contract.
 */
class ToolExperienceCaptureTest {

    private static ObjectNode args(String... kv) {
        ObjectNode n = new ObjectMapper().createObjectNode();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            n.put(kv[i], kv[i + 1]);
        }
        return n;
    }

    @Test
    void a_refactor_that_compiles_lands_one_compiled_row() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        ToolExperienceStore tes = new ToolExperienceStore(store);
        ToolExperienceRecorder rec = new ToolExperienceRecorder(tes);

        rec.onCall("s1", "rename_symbol", args("symbol", "com.foo.Bar#baz", "newName", "qux"),
            ToolResponse.success(Map.of("filesModified", List.of("Bar.java", "Caller.java"))));
        assertEquals(0L, tes.count(), "a mutate alone is pending, not yet an outcome");

        rec.onCall("s1", "compile_workspace", null,
            ToolResponse.success(Map.of("errorCount", 0)));

        List<ToolExperience> rows = tes.recentMatching("rename_symbol", 10);
        assertEquals(1L, tes.count());
        assertEquals(1, rows.size());
        assertEquals("rename_symbol", rows.get(0).tool());
        assertEquals(ToolExperience.OUTCOME_COMPILED, rows.get(0).outcome());
        assertTrue(rows.get(0).situation().contains("com.foo.Bar#baz"),
            "situation carries the salient arg for keyword retrieval");
        store.close();
    }

    @Test
    void a_refactor_then_a_failing_gate_lands_reverted() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        ToolExperienceStore tes = new ToolExperienceStore(store);
        ToolExperienceRecorder rec = new ToolExperienceRecorder(tes);

        rec.onCall("s1", "extract", args("kind", "method"),
            ToolResponse.success(Map.of("filesModified", List.of("A.java"))));
        rec.onCall("s1", "compile_workspace", null,
            ToolResponse.success(Map.of("errorCount", 3)));

        assertEquals(1L, tes.count());
        assertEquals(ToolExperience.OUTCOME_REVERTED,
            tes.recentMatching("extract", 1).get(0).outcome());
        store.close();
    }

    @Test
    void an_undo_reverts_the_pending_mutate() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        ToolExperienceStore tes = new ToolExperienceStore(store);
        ToolExperienceRecorder rec = new ToolExperienceRecorder(tes);

        rec.onCall("s1", "move", args("symbol", "com.foo.Bar"),
            ToolResponse.success(Map.of("filesModified", List.of("Bar.java"))));
        rec.onCall("s1", "refactoring", args("action", "undo"),
            ToolResponse.success(Map.of("undone", true)));

        assertEquals(1L, tes.count());
        assertEquals(ToolExperience.OUTCOME_REVERTED,
            tes.recentMatching("move", 1).get(0).outcome());
        store.close();
    }

    @Test
    void a_tool_error_lands_one_error_row() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        ToolExperienceStore tes = new ToolExperienceStore(store);
        ToolExperienceRecorder rec = new ToolExperienceRecorder(tes);

        rec.onCall("s1", "rename_symbol", args("symbol", "com.foo.Missing"),
            ToolResponse.error("SYMBOL_NOT_FOUND", "no such symbol", "check the name"));

        assertEquals(1L, tes.count());
        assertEquals(ToolExperience.OUTCOME_ERROR,
            tes.recentMatching("rename_symbol", 1).get(0).outcome());
        store.close();
    }

    @Test
    void a_routine_successful_search_lands_no_row_the_negative() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        ToolExperienceStore tes = new ToolExperienceStore(store);
        ToolExperienceRecorder rec = new ToolExperienceRecorder(tes);

        rec.onCall("s1", "search_symbols", args("query", "*Service"),
            ToolResponse.success(Map.of("results", List.of("A", "B"))));
        rec.onCall("s1", "find_references", args("symbol", "com.foo.Bar#baz"),
            ToolResponse.success(Map.of("results", List.of("X"))));
        rec.onCall("s1", "inspect", args("kind", "type_members", "typeName", "com.foo.Bar"),
            ToolResponse.success(Map.of("data", "stuff")));

        assertEquals(0L, tes.count(),
            "routine reads/searches carry no experience — the negative");
        store.close();
    }

    @Test
    void a_failed_write_is_loud_not_silent() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        ToolExperienceStore tes = new ToolExperienceStore(store);

        // A write that CANNOT land: tool is NOT NULL in the schema, so a null
        // tool fails the INSERT every attempt (deterministic, connection-
        // lifecycle-independent). The contract: swallowed + COUNTED, never
        // thrown and never a silent drop.
        assertDoesNotThrow(() -> tes.append(new ToolExperience("s1",
            "rename_symbol com.foo.Bar", null,
            ToolExperience.OUTCOME_COMPILED, "{}")));
        assertTrue(tes.failedWrites() > 0,
            "a write that cannot land is COUNTED (loud), never silently dropped");
        assertEquals(0L, tes.count(), "the failed write left no partial row");
        store.close();
    }
}
