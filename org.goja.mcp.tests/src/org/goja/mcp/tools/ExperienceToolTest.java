package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.mcp.knowledge.ExperienceEntry;
import org.goja.mcp.knowledge.ExperienceStore;
import org.goja.mcp.knowledge.H2ExperienceStore;
import org.goja.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21 Stage 1 — the {@code experience(kind=record)} front door. A store write does
 * not need a loaded project, so the tool runs with a {@code null} JDT service and an
 * in-memory store.
 */
class ExperienceToolTest {

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

    @Test
    void schema_advertises_record_and_requires_kind() {
        assertEquals("experience", tool.getName());
        Map<String, Object> schema = tool.getInputSchema();
        assertTrue(schema.get("required") instanceof List<?>);
        assertTrue(((List<?>) schema.get("required")).contains("kind"));
    }

    @Test
    void record_persists_a_candidate_and_returns_id() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "record");
        args.put("type", "lesson");
        args.put("summary", "guard the workbench lifecycle before disposing views");
        args.put("confidence", "high");
        args.put("symbol", "com.example.WorkflowCoordinator");
        args.put("details", "double-dispose crashes when a job is still running");
        ArrayNode symptoms = args.putArray("symptoms");
        symptoms.add("double dispose");
        symptoms.add("view already disposed");
        ArrayNode links = args.putArray("links");
        ObjectNode link = links.addObject();
        link.put("rel", "fixed_by");
        link.put("target", "check isDisposed() first");

        ToolResponse resp = tool.execute(args);
        assertTrue(resp.isSuccess(), "record succeeds");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) resp.getData();
        String id = (String) data.get("id");
        assertNotNull(id);
        assertEquals(ExperienceEntry.CANDIDATE, data.get("status"));

        assertEquals(1L, store.count());
        Optional<Map<String, Object>> got = store.get(id);
        assertTrue(got.isPresent());
        assertEquals("lesson", got.get().get("type"));
        assertEquals("com.example.WorkflowCoordinator", got.get().get("symbol"));
        assertEquals(2, ((List<?>) got.get().get("symptoms")).size());
        assertEquals(1, ((List<?>) got.get().get("links")).size());
    }

    @Test
    void record_accepts_scope_when_no_symbol() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "record");
        args.put("type", "domain_fact");
        args.put("summary", "billing DTOs keep no-arg constructors for legacy XML");
        args.putArray("packages").add("com.example.billing");

        ToolResponse resp = tool.execute(args);
        assertTrue(resp.isSuccess());
        assertEquals(1L, store.count());
    }

    @Test
    void record_requires_type_and_summary() {
        ObjectNode noType = mapper.createObjectNode();
        noType.put("kind", "record");
        noType.put("summary", "has summary but no type");
        assertFalse(tool.execute(noType).isSuccess(), "missing type rejected");

        ObjectNode noSummary = mapper.createObjectNode();
        noSummary.put("kind", "record");
        noSummary.put("type", "lesson");
        assertFalse(tool.execute(noSummary).isSuccess(), "missing summary rejected");

        assertEquals(0L, store.count(), "nothing stored on rejection");
    }

    @Test
    void unknown_kind_is_rejected() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "teleport");
        assertFalse(tool.execute(args).isSuccess());
    }
}
