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

/** Sprint 17 — Shotgun Surgery detector (fixture com.example.ShotgunTargets). */
class ShotgunSurgeryDetectorTest {

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
        args.put("kind", "shotgun_surgery");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "shotgun_surgery must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags the widely-referenced concrete type, not the unreferenced one or the abstraction")
    void flags_wide_reference_spread() {
        Set<String> hits = symbols();
        assertTrue(hits.contains("ShotgunTarget"),
            "concrete type referenced from > threshold (10) types should be flagged: " + hits);
        assertFalse(hits.contains("LonelyShotgun"),
            "unreferenced type must NOT be flagged: " + hits);
        assertFalse(hits.contains("AbstractWidelyUsed"),
            "an abstract type referenced just as widely must NOT be flagged (DIP): " + hits);
    }
}
