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

/**
 * Sprint 17 — Long Method detector, exercised through the {@code find_quality_issue}
 * projection (proves kind registration + dispatch + the AbstractAstDetector path).
 * Fixture {@code com.example.LongMethodTargets}: {@code longOne} (LOC-gated),
 * {@code branchy} (CC-gated), {@code clean} (neither).
 */
class LongMethodDetectorTest {

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
    private Set<String> longMethodSymbols(Integer threshold) {
        return longMethodSymbols(threshold, false);
    }

    @SuppressWarnings("unchecked")
    private Set<String> longMethodSymbols(Integer threshold, boolean includeTests) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "long_method");
        if (threshold != null) {
            args.put("threshold", threshold.intValue());
        }
        if (includeTests) {
            args.put("includeTests", true);
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "long_method must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream()
            .map(f -> String.valueOf(f.get("symbol")))
            .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("long_method is projected into the kind enum")
    void projected_into_enum() {
        Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
        Map<String, Object> kind = (Map<String, Object>) props.get("kind");
        List<String> kinds = (List<String>) kind.get("enum");
        assertTrue(kinds.contains("long_method"), "long_method must be in the projected enum: " + kinds);
    }

    @Test
    @DisplayName("default thresholds flag the long and the branchy method, not the clean one")
    void flags_smelly_ignores_clean() {
        Set<String> hits = longMethodSymbols(null);
        assertTrue(hits.contains("longOne"), "long straight-line method should be flagged (LOC): " + hits);
        assertTrue(hits.contains("branchy"), "branchy method should be flagged (CC): " + hits);
        assertFalse(hits.contains("clean"), "short simple method must NOT be flagged: " + hits);
    }

    @Test
    @DisplayName("raising the threshold spares the long-but-simple method; CC trigger still fires")
    void threshold_respected() {
        Set<String> hits = longMethodSymbols(1000);
        assertFalse(hits.contains("longOne"), "LOC-gated method should clear a high threshold: " + hits);
        assertTrue(hits.contains("branchy"), "CC trigger is independent of the LOC threshold: " + hits);
        assertFalse(hits.contains("clean"), "clean method never flagged: " + hits);
    }

    @Test
    @DisplayName("test sources are excluded by default, included on opt-in (v1.2.1)")
    void test_sources_excluded_by_default() {
        assertFalse(longMethodSymbols(null).contains("longTestHelper"),
            "a long method in test source must NOT be flagged by default");
        assertTrue(longMethodSymbols(null, true).contains("longTestHelper"),
            "includeTests=true must surface the test-source long method");
    }
}
