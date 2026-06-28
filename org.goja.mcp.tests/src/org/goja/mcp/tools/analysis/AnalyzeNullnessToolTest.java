package org.goja.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AnalyzeNullnessTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 15b — analyze_nullness detect_style. Drives the simple-maven fixture,
 * whose NullnessStyleTarget uses JSpecify annotations (source-only detection).
 */
class AnalyzeNullnessToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private AnalyzeNullnessTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeNullnessTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detect() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "detect_style");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("detect_style identifies the nullness families in use")
    void detectsFamilies() {
        Map<String, Object> d = detect();
        assertEquals("detect_style", d.get("kind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> families = (Map<String, Object>) d.get("families");
        // The shared fixture carries JSpecify (NullnessStyleTarget) — and other sprints'
        // fixtures add more families; assert detection finds JSpecify and picks SOME family.
        assertTrue(families.containsKey("JSPECIFY"), "families: " + families);
        assertNotEquals("none", d.get("detectedStyle"));
        assertFalse(((java.util.List<?>) d.get("evidence")).isEmpty(), "evidence files expected");
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, Object>> findViolations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "find_violations");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("find_violations", data.get("kind"));
        return (java.util.List<Map<String, Object>>) data.get("violations");
    }

    @Test
    @DisplayName("find_violations reports flow null dereferences from the fixture")
    void findsNullDeref() {
        java.util.List<Map<String, Object>> v = findViolations();
        assertFalse(v.isEmpty(), "expected null-pointer findings");
        assertTrue(v.stream().allMatch(f -> String.valueOf(f.get("message")).toLowerCase().contains("null")),
            "every finding mentions null: " + v);
        assertTrue(v.stream().anyMatch(f -> String.valueOf(f.get("filePath")).endsWith("NullnessViolations.java")),
            "the deref fixture should be flagged: " + v);
    }

    @SuppressWarnings("unchecked")
    private java.util.List<Map<String, Object>> inferContracts() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "infer_contracts");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        return (java.util.List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("contracts");
    }

    private Map<String, Object> contract(java.util.List<Map<String, Object>> cs, String symEnds, String target) {
        return cs.stream()
            .filter(c -> String.valueOf(c.get("symbol")).endsWith(symEnds) && target.equals(c.get("target")))
            .findFirst().orElse(null);
    }

    @Test
    @DisplayName("infer_contracts: requireNonNull param → @NonNull, return null → @Nullable")
    void infersContracts() {
        java.util.List<Map<String, Object>> cs = inferContracts();
        Map<String, Object> param = contract(cs, "NullnessContracts#requireParam", "param:key");
        assertNotNull(param, "requireNonNull param contract expected: " + cs);
        assertEquals("nonnull", param.get("nullness"));
        assertEquals("high", param.get("confidence"));

        Map<String, Object> ret = contract(cs, "NullnessContracts#maybeNull", "return");
        assertNotNull(ret, "return-null contract expected: " + cs);
        assertEquals("nullable", ret.get("nullness"));
    }

    @Test
    @DisplayName("infer_contracts: no contract inside a @Generated (risky) type")
    void skipsRiskyType() {
        boolean generatedPresent = inferContracts().stream()
            .anyMatch(c -> String.valueOf(c.get("symbol")).endsWith("GeneratedHolder#alsoNull"));
        assertFalse(generatedPresent, "framework/generated type must be skipped");
    }

    @Test
    @DisplayName("check: focused style + violations + contracts for a symbol's file")
    void checkFocus() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "check");
        args.put("symbol", "com.example.NullnessContracts#maybeNull");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("detectedStyle"));
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> contracts = (java.util.List<Map<String, Object>>) data.get("contracts");
        assertNotNull(contract(contracts, "NullnessContracts#maybeNull", "return"),
            "check should surface the file's contracts: " + contracts);
    }

    @Test
    @DisplayName("unknown kind is rejected")
    void unknownKind() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode().put("kind", "no_such"));
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }
}
