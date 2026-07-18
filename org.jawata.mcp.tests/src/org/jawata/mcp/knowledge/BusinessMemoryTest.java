package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 26 Stage 6 (D6, the mcp half): business-domain facts are
 * first-class — recorded with a non-Java language tag, they surface in the
 * session primer AND recall by a business keyword, no Java symbol involved.
 */
class BusinessMemoryTest {

    private H2ExperienceStore store;
    private ExperienceTool tool;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    private void recordBusinessFact() throws Exception {
        var r = tool.execute(mapper.readTree("{"
            + "\"kind\":\"record\",\"type\":\"domain_fact\","
            + "\"summary\":\"Invoicing runs monthly on the 3rd; the Meyer account is always net-60\","
            + "\"operation\":\"billing-cycle\",\"language\":\"business\","
            + "\"status\":\"accepted\"}"));
        assertTrue(r.isSuccess(), String.valueOf(r.getError()));
    }

    @Test
    void a_business_fact_appears_in_a_fresh_sessions_primer() throws Exception {
        recordBusinessFact();
        var primer = tool.execute(mapper.readTree("{\"kind\":\"primer\",\"format\":\"text\"}"));
        assertTrue(primer.isSuccess());
        assertTrue(String.valueOf(primer.getData()).contains("net-60"),
            "the business fact rides the always-on primer with no Java anywhere");
    }

    @Test
    void a_business_fact_recalls_by_a_business_keyword() throws Exception {
        recordBusinessFact();
        var recall = tool.execute(mapper.readTree(
            "{\"kind\":\"recall\",\"operation\":\"billing-cycle\"}"));
        assertTrue(recall.isSuccess());
        assertTrue(String.valueOf(recall.getData()).contains("net-60"),
            "recall by the business operation cue, no symbol involved");
        // And by symptom-style free cue phrasing.
        var bySymptom = tool.execute(mapper.readTree(
            "{\"kind\":\"recall\",\"symptom\":\"invoicing\"}"));
        assertTrue(bySymptom.isSuccess(),
            "a business-phrased cue must not error: " + bySymptom.getError());
    }
}
