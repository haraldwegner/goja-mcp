package org.goja.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AnalyzeNamingTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 15a — analyze_naming infer/get (shallow-precise). Drives the
 * simple-maven fixture: UpperCamelCase types (HIGH), a constant convention with
 * a recorded exception (badConstant), and the single-package case yielding no
 * package convention (unclear / min-sample gate).
 */
class AnalyzeNamingToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private AnalyzeNamingTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeNamingTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> conventions(String kind, String category) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", kind);
        if (category != null) {
            args.put("category", category);
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return (List<Map<String, Object>>) data.get("conventions");
    }

    private Map<String, Object> byCategory(List<Map<String, Object>> cs, String category) {
        return cs.stream().filter(c -> category.equals(c.get("category"))).findFirst().orElse(null);
    }

    @Test
    @DisplayName("infer: types are UpperCamelCase with HIGH confidence")
    void typeCasing() {
        Map<String, Object> type = byCategory(conventions("infer", null), "type");
        assertNotNull(type, "a type convention is expected");
        assertEquals("naming_convention", type.get("type"));
        assertEquals("high", type.get("confidence"));
        assertTrue(String.valueOf(type.get("summary")).contains("UpperCamelCase"));
    }

    @Test
    @DisplayName("infer: constant convention records the lowercase violation as an exception")
    void constantConventionWithException() {
        Map<String, Object> c = byCategory(conventions("infer", null), "constant");
        assertNotNull(c, "a constant convention is expected");
        assertTrue(String.valueOf(c.get("summary")).contains("UPPER_SNAKE_CASE"));
        @SuppressWarnings("unchecked")
        List<String> exceptions = (List<String>) c.get("exceptions");
        assertNotNull(exceptions, "the violation should be recorded");
        assertTrue(exceptions.contains("badConstant"), "exceptions: " + exceptions);
    }

    @Test
    @DisplayName("infer: a single package yields no package convention (min-sample → unclear)")
    void singlePackageUnclear() {
        assertNull(byCategory(conventions("infer", null), "package"),
            "one package is below MIN_SAMPLE → no convention asserted");
    }

    @Test
    @DisplayName("get: filters to the requested category")
    void getFiltersByCategory() {
        List<Map<String, Object>> only = conventions("get", "constant");
        assertFalse(only.isEmpty());
        assertTrue(only.stream().allMatch(c -> "constant".equals(c.get("category"))));
    }

    @Test
    @DisplayName("infer is deterministic across calls")
    void deterministic() {
        assertEquals(conventions("infer", null), conventions("infer", null));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("suggest: applies category casing to the caller's intent")
    void suggestAppliesCasing() {
        Map<String, Object> type = data(tool.execute(objectMapper.createObjectNode()
            .put("kind", "suggest").put("category", "type").put("intent", "billing http api client")));
        assertEquals(List.of("BillingHttpApiClient"), type.get("candidates"));

        Map<String, Object> constant = data(tool.execute(objectMapper.createObjectNode()
            .put("kind", "suggest").put("category", "constant").put("intent", "max retry count")));
        assertEquals(List.of("MAX_RETRY_COUNT"), constant.get("candidates"));
    }

    @Test
    @DisplayName("suggest: no intent → convention shape only, no invented stem")
    void suggestNoIntentNoStem() {
        Map<String, Object> d = data(tool.execute(objectMapper.createObjectNode()
            .put("kind", "suggest").put("category", "type")));
        assertEquals(Boolean.TRUE, d.get("needsIntent"));
        assertFalse(d.containsKey("candidates"), "no stem may be invented without intent");
        assertEquals("UpperCamelCase", d.get("convention"));
    }

    @Test
    @DisplayName("suggest: missing category rejected")
    void suggestMissingCategory() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode()
            .put("kind", "suggest").put("intent", "something"));
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("check: flags a mis-cased constant and suggests the corrected form")
    void checkFlagsAndSuggests() {
        Map<String, Object> bad = data(tool.execute(objectMapper.createObjectNode()
            .put("kind", "check").put("category", "constant").put("name", "maxSize")));
        assertEquals(Boolean.FALSE, bad.get("conforms"));
        assertEquals("MAX_SIZE", bad.get("suggestion"));

        Map<String, Object> good = data(tool.execute(objectMapper.createObjectNode()
            .put("kind", "check").put("category", "constant").put("name", "MAX_SIZE")));
        assertEquals(Boolean.TRUE, good.get("conforms"));
        assertFalse(good.containsKey("suggestion"));
    }

    @Test
    @DisplayName("check: missing name rejected")
    void checkMissingName() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode()
            .put("kind", "check").put("category", "constant"));
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("unknown kind is rejected")
    void unknownKind() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode().put("kind", "no_such"));
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }
}
