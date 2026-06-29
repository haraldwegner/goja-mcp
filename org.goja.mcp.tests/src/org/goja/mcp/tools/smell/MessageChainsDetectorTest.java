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

/** Sprint 17 — Message Chains detector (fixture com.example.MessageChainTargets). */
class MessageChainsDetectorTest {

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
    private Set<String> symbols(Integer threshold) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "message_chains");
        if (threshold != null) {
            args.put("threshold", threshold.intValue());
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "message_chains must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags the length-4 chain, not the length-2 one")
    void flags_long_chain() {
        Set<String> hits = symbols(null);
        assertTrue(hits.contains("longChain"), "length-4 chain should be flagged: " + hits);
        assertFalse(hits.contains("shortChain"), "length-2 chain must NOT be flagged: " + hits);
    }

    @Test
    @DisplayName("raising the threshold spares the length-4 chain")
    void threshold_respected() {
        assertFalse(symbols(5).contains("longChain"), "length-4 chain clears a threshold of 5");
    }
}
