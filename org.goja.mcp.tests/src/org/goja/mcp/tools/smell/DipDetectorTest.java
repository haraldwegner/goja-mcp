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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 20 — Dependency Inversion detector (fixture com.example.DipTargets). */
class DipDetectorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Set<String> symbols() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "dip");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "dip must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags the concrete field used only via an interface; spares concrete-only + already-abstract")
    void flags_concrete_used_as_interface() {
        Set<String> hits = symbols();
        assertTrue(hits.contains("items"),
            "ArrayList field used only via List methods should be flagged: " + hits);
        assertFalse(hits.contains("tuned"),
            "field calling a concrete-only method (ensureCapacity) must NOT be flagged: " + hits);
        assertFalse(hits.contains("already"),
            "field already declared as the interface must NOT be flagged: " + hits);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("dip is in the solid family")
    void dip_in_solid_family() {
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "solid");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess());
        List<String> kinds = (List<String>) ((Map<String, Object>) r.getData()).get("kinds");
        assertTrue(kinds.contains("dip"), "dip must be tagged solid: " + kinds);
    }
}
