package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.mcp.knowledge.ExperienceStore;
import org.goja.mcp.knowledge.H2ExperienceStore;
import org.goja.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 21 Stage 2 — {@code experience(kind=recall)} through the tool front door. */
class ExperienceToolRecallTest {

    private ObjectMapper mapper;
    private ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ObjectNode recordArgs(String type, String summary, String symbol) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", type);
        a.put("summary", summary);
        a.put("symbol", symbol);
        return a;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess());
        return (Map<String, Object>) r.getData();
    }

    @Test
    void recall_with_no_cue_is_absence() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "recall");
        Map<String, Object> d = data(tool.execute(a));
        assertEquals("absence", d.get("result"));
    }

    @Test
    void record_then_recall_by_symbol_returns_match() {
        tool.execute(recordArgs("lesson", "guard the workbench lifecycle",
            "com.example.WorkflowCoordinator"));

        ObjectNode recall = mapper.createObjectNode();
        recall.put("kind", "recall");
        recall.put("symbol", "com.example.WorkflowCoordinator");
        Map<String, Object> d = data(tool.execute(recall));

        assertEquals("match", d.get("result"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) d.get("entries");
        assertEquals(1, entries.size());
        assertEquals("com.example.WorkflowCoordinator", entries.get(0).get("symbol"));
    }

    @Test
    void recall_by_symptom_bridges_paraphrase() {
        ObjectNode rec = recordArgs("failure_mode", "OSGi resolve NPE on plain Maven",
            "com.example.alpol.Gateway");
        rec.putArray("symptoms").add("OSGi NPE").add("null service at startup");
        tool.execute(rec);

        ObjectNode recall = mapper.createObjectNode();
        recall.put("kind", "recall");
        recall.put("symptom", "osgi npe");
        Map<String, Object> d = data(tool.execute(recall));
        assertEquals("match", d.get("result"));
    }
}
