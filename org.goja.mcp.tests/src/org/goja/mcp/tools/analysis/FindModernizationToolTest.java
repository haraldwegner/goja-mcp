package org.goja.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindModernizationTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 15 — find_modernization parametric sweep (batch-1 kinds). Drives the
 * simple-maven fixture's ModernizationTargets, which carries one clear
 * candidate per kind.
 */
class FindModernizationToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindModernizationTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindModernizationTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> run(String kind) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", kind);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "kind " + kind + ": " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(kind, data.get("kind"));
        return (List<Map<String, Object>>) data.get("candidates");
    }

    private boolean hitsModernizationTargets(List<Map<String, Object>> candidates) {
        return candidates.stream()
            .anyMatch(c -> String.valueOf(c.get("filePath")).endsWith("ModernizationTargets.java"));
    }

    @Test
    @DisplayName("anon_to_lambda finds the anonymous Runnable")
    void anonToLambda() {
        List<Map<String, Object>> c = run("anon_to_lambda");
        assertFalse(c.isEmpty(), "must find the anonymous Runnable");
        assertTrue(hitsModernizationTargets(c), "ModernizationTargets.makeTask must be a candidate: " + c);
        assertNotNull(c.get(0).get("line"));
        assertNotNull(c.get(0).get("suggestion"));
    }

    @Test
    @DisplayName("switch_to_pattern finds the classic switch")
    void switchToPattern() {
        List<Map<String, Object>> c = run("switch_to_pattern");
        assertTrue(hitsModernizationTargets(c), "ModernizationTargets.describe switch must be a candidate: " + c);
    }

    @Test
    @DisplayName("loop_to_stream finds the accumulation for-each")
    void loopToStream() {
        List<Map<String, Object>> c = run("loop_to_stream");
        assertTrue(hitsModernizationTargets(c), "ModernizationTargets.upper loop must be a candidate: " + c);
    }

    private boolean hitsRecordSealedTargets(List<Map<String, Object>> candidates) {
        return candidates.stream()
            .anyMatch(c -> String.valueOf(c.get("filePath")).endsWith("RecordSealedTargets.java"));
    }

    @Test
    @DisplayName("class_to_record finds the immutable data class")
    void classToRecord() {
        List<Map<String, Object>> c = run("class_to_record");
        assertTrue(hitsRecordSealedTargets(c), "PointData must be a class_to_record candidate: " + c);
    }

    @Test
    @DisplayName("optional finds the nullable reference return")
    void optional() {
        List<Map<String, Object>> c = run("optional");
        assertTrue(hitsRecordSealedTargets(c), "NullableFinder.findOrNull must be an optional candidate: " + c);
    }

    @Test
    @DisplayName("sealed finds the abstract base class")
    void sealed() {
        List<Map<String, Object>> c = run("sealed");
        assertTrue(hitsRecordSealedTargets(c), "ShapeBase must be a sealed candidate: " + c);
    }

    private boolean hitsLombokTargets(List<Map<String, Object>> candidates, String fileSuffix) {
        return candidates.stream()
            .anyMatch(c -> String.valueOf(c.get("filePath")).endsWith(fileSuffix));
    }

    @Test
    @DisplayName("lombok_to_record finds the @Data data carrier")
    void lombokToRecord() {
        List<Map<String, Object>> c = run("lombok_to_record");
        assertTrue(hitsLombokTargets(c, "LombokTargets.java"),
            "LombokPoint (@Data) must be a lombok_to_record candidate: " + c);
        // @Getter-only class is NOT a data carrier → must not appear.
        assertTrue(c.stream().allMatch(x -> String.valueOf(x.get("snippet")).contains("@Data")
            || String.valueOf(x.get("snippet")).contains("@Value")),
            "only @Data/@Value classes qualify: " + c);
    }

    @Test
    @DisplayName("delombok finds every Lombok-annotated class")
    void delombok() {
        List<Map<String, Object>> c = run("delombok");
        assertTrue(hitsLombokTargets(c, "LombokTargets.java"),
            "Lombok-annotated classes must be delombok candidates: " + c);
        // Both @Data and @Getter classes are delombok candidates → at least 2.
        long lombokHits = c.stream()
            .filter(x -> String.valueOf(x.get("filePath")).endsWith("LombokTargets.java"))
            .count();
        assertTrue(lombokHits >= 2, "expected both LombokPoint and LombokBox: " + c);
    }

    @Test
    @DisplayName("unknown kind is rejected")
    void unknownKind() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "no_such_kind");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }
}
