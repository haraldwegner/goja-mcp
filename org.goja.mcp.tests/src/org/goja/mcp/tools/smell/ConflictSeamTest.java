package org.goja.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a R.1 (Stage 14) — the multi-catalog conflict seam + the &ge;1 net-new
 * catalog kind. A family sweep that flags one location with two detectors surfaces the
 * arbitration INPUTS (detectors + families + a prevalence signal) and decides nothing;
 * {@code forbidden_edge} (Stage 7) is a projected {@code find_quality_issue} kind.
 * Fixture: {@code conflict} — {@code Tangled.knot} is both long_method and
 * long_parameter_list under {@code threshold=3}.
 */
class ConflictSeamTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("conflict");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("two detectors at one location surface arbitration inputs and no decision")
    @SuppressWarnings("unchecked")
    void twoDetectorsOneLocation_surfaceInputs_noDecision() {
        ObjectNode a = mapper.createObjectNode();
        a.put("family", "fowler");
        a.put("threshold", 3);   // knot: 4 params (> 3) AND body over 3 LOC -> both fire

        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> d = data(r);

        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) d.get("conflicts");
        assertNotNull(conflicts, "a family sweep carries a conflicts list");
        assertFalse(conflicts.isEmpty(), () -> "expected a >=2-detector conflict: " + d);

        Map<String, Object> c = conflicts.stream()
            .filter(x -> ((List<?>) x.get("detectors")).contains("long_method")
                && ((List<?>) x.get("detectors")).contains("long_parameter_list"))
            .findFirst().orElse(null);
        assertNotNull(c, () -> "long_method + long_parameter_list collide at one line: " + conflicts);

        assertTrue(((List<?>) c.get("families")).contains("fowler"), () -> "families carried: " + c);
        assertTrue(c.containsKey("decision"), "a decision slot is present");
        assertNull(c.get("decision"), "goja arbitrates nothing — the decision is the caller's");
        Map<String, Object> signal = (Map<String, Object>) c.get("conventionSignal");
        assertNotNull(signal.get("prevalence"), () -> "a neutral prevalence signal is offered: " + signal);
    }

    @Test
    @DisplayName("forbidden_edge (Stage 7) is a projected find_quality_issue kind")
    @SuppressWarnings("unchecked")
    void forbiddenEdge_isAProjectedKind() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) props.get("kind");
        List<String> kinds = (List<String>) kind.get("enum");
        assertTrue(kinds.contains("forbidden_edge"),
            () -> "the >=1 net-new catalog kind must be surfaced in the enum: " + kinds);
    }
}
