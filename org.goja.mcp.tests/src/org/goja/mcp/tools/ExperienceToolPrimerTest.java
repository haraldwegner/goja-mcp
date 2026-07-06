package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.mcp.knowledge.ExperienceStore;
import org.goja.mcp.knowledge.H2ExperienceStore;
import org.goja.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Sprint 21 Stage 5 — experience(kind=primer) + format=text through the front door. */
class ExperienceToolPrimerTest {

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

    private void recordAcceptedDomain() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "domain_fact");
        a.put("summary", "the system is about orders and fulfilment");
        a.put("status", "accepted");
        a.put("symbol", "com.example.OrderContext");
        assertTrue(tool.execute(a).isSuccess());
    }

    @Test
    void primer_includes_standing_conventions_not_just_domain_facts() {
        // v2.2.3 dogfood find: a real md corpus maps to feedback/user/reference types —
        // zero domain_fact — and the SessionStart primer injected NOTHING from 97 entries.
        for (String type : new String[] {"feedback", "user", "naming_convention"}) {
            ObjectNode a = mapper.createObjectNode();
            a.put("kind", "record");
            a.put("type", type);
            a.put("summary", "standing rule of type " + type);
            a.put("status", "accepted");
            assertTrue(tool.execute(a).isSuccess());
        }
        ObjectNode reference = mapper.createObjectNode();
        reference.put("kind", "record");
        reference.put("type", "reference");
        reference.put("summary", "a pointer — NOT primer material");
        reference.put("status", "accepted");
        assertTrue(tool.execute(reference).isSuccess());

        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "primer");
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) tool.execute(a).getData();
        assertEquals("primer", d.get("result"));
        assertEquals(3, d.get("count"), "feedback/user/naming_convention are primer material; reference is not");
    }

    @Test
    void primer_via_tool_returns_domain_nodes() {
        recordAcceptedDomain();
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "primer");
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) tool.execute(a).getData();
        assertEquals("primer", d.get("result"));
        assertEquals(1, d.get("count"));
    }

    @Test
    void primer_format_text_returns_flat_lines() {
        recordAcceptedDomain();
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "primer");
        a.put("format", "text");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess());
        assertInstanceOf(String.class, r.getData(), "text mode returns a plain string");
        assertTrue(((String) r.getData()).contains("[domain_fact] the system is about orders"));
    }

    @Test
    void recall_format_text_absence_is_a_string_message() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "recall");
        a.put("symbol", "com.nothing.Here");
        a.put("format", "text");
        ToolResponse r = tool.execute(a);
        assertInstanceOf(String.class, r.getData());
        assertEquals("No known knowledge for this cue.", r.getData());
    }
}
