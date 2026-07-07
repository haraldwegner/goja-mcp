package org.goja.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.ExperienceTool;
import org.goja.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sprint 21e Stage 3 (item B) — the classify-against-closed-set contract: recall MATCH
 * responses steer the agent to CLASSIFY ("match one with evidence or declare new"),
 * absence responses keep the generic steering, and the hook-parsed {@code format=text}
 * payload is blind to meta entirely.
 */
class ExperienceClassifySteeringTest {

    private static final String CLASSIFY =
        "Match the observation to ONE of these with evidence, or declare it genuinely new"
        + " — do not generate a novel cause.";

    private H2ExperienceStore store;
    private ToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        registry = new ToolRegistry();
        registry.register(new ExperienceTool(() -> null, store));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void putLesson(String summary, String... symptoms) {
        ExperienceEntry.Builder b = ExperienceEntry.of(
            SymbolFact.of("lesson", summary, Confidence.MEDIUM).build())
            .status(ExperienceEntry.ACCEPTED);
        for (String s : symptoms) {
            b.addSymptom(s);
        }
        store.put(b.build());
    }

    private ToolResponse recall(String symptom) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "recall");
        args.put("symptom", symptom);
        return registry.callTool("experience", args);
    }

    @Test
    @DisplayName("symptom-recall MATCH carries the classify mandate in meta.steering — through dispatch")
    void match_carries_classify_steering_through_dispatch() throws Exception {
        putLesson("lock retry on open", "lock file recently modified");
        ToolResponse r = recall("lock file recently modified");
        assertNotNull(r.getMeta(), "match responses carry meta");
        assertEquals(CLASSIFY, r.getMeta().getSteering(),
            "the per-response classify line wins over the generic per-tool steering");
    }

    @Test
    @DisplayName("absence keeps the GENERIC steering — the mandate never appears without a closed set")
    void absence_keeps_generic_steering() throws Exception {
        ToolResponse r = recall("something nobody ever recorded");
        assertNotNull(r.getMeta(), "the envelope layer still steers");
        assertNotNull(r.getMeta().getSteering());
        assertNotEquals(CLASSIFY, r.getMeta().getSteering(),
            "no closed set → nothing to classify against");
    }

    @Test
    @DisplayName("format=text payload is blind to meta — the hook-peeled bytes never change")
    void render_text_is_blind_to_meta() {
        putLesson("lock retry on open", "lock file recently modified");
        ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);
        Map<String, Object> result =
            retrieval.recall(new RecallQuery(null, null, null, "lock file recently modified", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, result.get("result"));

        String withoutMeta = ExperienceRetrieval.renderText(result);
        result.put("meta", Map.of("steering", CLASSIFY));
        String withMeta = ExperienceRetrieval.renderText(result);

        assertEquals(withoutMeta, withMeta, "renderText never renders meta");
        assertFalse(withoutMeta.contains("do not generate a novel cause"),
            "the mandate never leaks into the hook-parsed text");
    }

    @Test
    @DisplayName("format=text through dispatch: the data payload stays the flat lines, steering rides meta only")
    void text_format_keeps_flat_lines_payload() throws Exception {
        putLesson("lock retry on open", "lock file recently modified");
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "recall");
        args.put("symptom", "lock file recently modified");
        args.put("format", "text");
        ToolResponse r = registry.callTool("experience", args);
        String firstLine = String.valueOf(r.getData()).lines().findFirst().orElse("");
        assertEquals(true, firstLine.startsWith("[lesson] lock retry on open"),
            "flat line payload unchanged: " + firstLine);
        assertFalse(String.valueOf(r.getData()).contains("do not generate a novel cause"),
            "the mandate rides meta, never the text payload");
        assertEquals(CLASSIFY, r.getMeta().getSteering());
    }
}
