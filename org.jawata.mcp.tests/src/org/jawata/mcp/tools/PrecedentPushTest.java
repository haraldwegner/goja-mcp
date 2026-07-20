package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.knowledge.Confidence;
import org.jawata.mcp.knowledge.ExperienceEntry;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.RecallQuery;
import org.jawata.mcp.knowledge.StoredEntry;
import org.jawata.mcp.knowledge.SymbolFact;
import org.jawata.mcp.knowledge.ToolExperienceStore;
import org.jawata.mcp.learn.EventTap;
import org.jawata.mcp.learn.KeywordPrecedentRetriever;
import org.jawata.mcp.learn.SessionLedger;
import org.jawata.mcp.learn.ToolExperience;
import org.jawata.mcp.learn.ToolExperienceRecorder;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 26a D2 (C2): the loop CLOSED through the real choke — capture →
 * store → retrieve → surface — plus append-only (the tool's own line survives),
 * the swappable retrieval seam (E6), and the store staying general.
 */
class PrecedentPushTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** A minimal mock tool (MockTool is private to ToolRegistryTest). */
    private static Tool mock(String name, Function<JsonNode, ToolResponse> exec) {
        return new Tool() {
            @Override public String getName() {
                return name;
            }
            @Override public String getDescription() {
                return name;
            }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of();
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return exec.apply(arguments);
            }
        };
    }

    private static JsonNode json(String s) {
        try {
            return OM.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void the_loop_closes_end_to_end_through_the_choke() throws Exception {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        ToolExperienceStore tes = new ToolExperienceStore(store);
        ToolRegistry reg = new ToolRegistry();
        EventTap tap = new EventTap(new SessionLedger(), null);
        tap.setToolExperienceRecorder(new ToolExperienceRecorder(tes));
        reg.setEventTap(tap);
        reg.setPrecedentRetriever(new KeywordPrecedentRetriever(tes));

        reg.register(mock("extract",
            a -> ToolResponse.success(Map.of("filesModified", List.of("Bar.java")))));
        reg.register(mock("compile_workspace",
            a -> ToolResponse.success(Map.of("errorCount", 2))));
        reg.register(mock("analyze", a -> ToolResponse.success(Map.of("ok", true))));

        // A mutate on com.foo.Bar, then a failing gate → a reverted precedent is captured.
        reg.callTool("extract", json("{\"symbol\":\"com.foo.Bar#baz\",\"kind\":\"method\"}"), "s1");
        reg.callTool("compile_workspace", json("{}"), "s1");
        assertEquals(1L, tes.count(), "the reverted precedent was captured through the tap");

        // A later call working on com.foo.Bar → the precedent rides the answer.
        ToolResponse resp = reg.callTool("analyze", json("{\"typeName\":\"com.foo.Bar\"}"), "s1");
        String steer = resp.getMeta() == null ? null : resp.getMeta().getSteering();
        assertNotNull(steer, "a matching precedent rides the answer");
        assertTrue(steer.contains("PRECEDENT"), steer);
        assertTrue(steer.contains("extract"), "it names the tool that failed on this target before");
        store.close();
    }

    @Test
    void the_precedent_push_is_append_only() throws Exception {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        ToolExperienceStore tes = new ToolExperienceStore(store);
        tes.append(new ToolExperience("s1", "extract com.foo.Bar#baz method",
            "extract", ToolExperience.OUTCOME_REVERTED, "{}"));
        ToolRegistry reg = new ToolRegistry();
        reg.setPrecedentRetriever(new KeywordPrecedentRetriever(tes));
        reg.register(mock("analyze", a -> {
            ToolResponse r = ToolResponse.success(Map.of("ok", true));
            r.applySteering("TOOL-OWN: the next grounded step");
            return r;
        }));

        ToolResponse resp = reg.callTool("analyze", json("{\"typeName\":\"com.foo.Bar\"}"), "s1");
        String steer = resp.getMeta().getSteering();
        assertTrue(steer.contains("TOOL-OWN: the next grounded step"),
            "the tool's OWN steering line survives — the push never replaces it");
        assertTrue(steer.contains("PRECEDENT"), "the precedent is APPENDED alongside it");
        store.close();
    }

    @Test
    void the_push_reads_through_the_swappable_retriever_seam() throws Exception {
        // A FAKE retriever (a Sprint-27 embedding stand-in) returns a canned
        // precedent; the choke uses the INTERFACE, so the real retriever swaps in
        // without touching the choke.
        ToolRegistry reg = new ToolRegistry();
        reg.setPrecedentRetriever((query, limit) -> List.of(
            new ToolExperience("s1", "move com.foo.Bar", "move",
                ToolExperience.OUTCOME_REVERTED, "{}")));
        reg.register(mock("analyze", a -> ToolResponse.success(Map.of("ok", true))));

        ToolResponse resp = reg.callTool("analyze", json("{\"typeName\":\"com.foo.Bar\"}"), "s1");
        String steer = resp.getMeta().getSteering();
        assertNotNull(steer);
        assertTrue(steer.contains("PRECEDENT") && steer.contains("move"),
            "the choke surfaces whatever the injected retriever returns — the seam is real");
    }

    @Test
    void the_store_stays_general_alongside_the_tool_lane() {
        H2ExperienceStore store = H2ExperienceStore.openMemory();
        String id = store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "widget recall still works", Confidence.MEDIUM).build())
            .status(ExperienceEntry.ACCEPTED).build());
        store.updateSymbolAnchor(id, "com.demo.Widget");
        ToolExperienceStore tes = new ToolExperienceStore(store);
        tes.append(new ToolExperience("s1", "rename_symbol com.demo.Widget",
            "rename_symbol", ToolExperience.OUTCOME_COMPILED, "{}"));

        List<StoredEntry> hits = store.query(new RecallQuery("com.demo.Widget", null, null, null, null));
        assertFalse(hits.isEmpty(), "the general store still recalls");
        assertTrue(hits.stream().anyMatch(h -> "widget recall still works".equals(h.summary())),
            "a NON-tool memory recalls alongside tool_experience rows — the store stayed general");
        store.close();
    }
}
