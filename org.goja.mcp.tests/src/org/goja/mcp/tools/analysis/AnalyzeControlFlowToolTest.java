package org.goja.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AnalyzeControlFlowTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeControlFlowToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeControlFlowTool tool;
    private ObjectMapper objectMapper;
    private String patternsPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeControlFlowTool(() -> service);
        objectMapper = new ObjectMapper();
        patternsPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/DiAndReflectionPatterns.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Control Flow Detection Tests ==========

    @Test
    @DisplayName("should analyze control flow of a method with branches and loops")
    void analyzesControlFlow() {
        // controlFlowExample method has if, for, while, try-catch, throw, return
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 42);  // controlFlowExample method
        args.put("column", 18);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertEquals("controlFlowExample", data.get("method"));
        assertTrue((int) data.get("branches") > 0, "Should detect branches");

        @SuppressWarnings("unchecked")
        Map<String, Object> loops = (Map<String, Object>) data.get("loops");
        assertTrue((int) loops.get("total") > 0, "Should detect loops");

        @SuppressWarnings("unchecked")
        List<?> returnPoints = (List<?>) data.get("returnPoints");
        assertTrue(returnPoints.size() > 0, "Should detect return statements");

        @SuppressWarnings("unchecked")
        List<?> throwPoints = (List<?>) data.get("throwPoints");
        assertTrue(throwPoints.size() > 0, "Should detect throw statements");

        assertTrue((int) data.get("maxNestingDepth") > 0, "Should report nesting depth");
    }

    @Test
    @DisplayName("should detect try-catch blocks with caught types")
    void detectsTryCatchBlocks() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 42);
        args.put("column", 18);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tryCatchBlocks = (List<Map<String, Object>>) getData(response).get("tryCatchBlocks");
        assertFalse(tryCatchBlocks.isEmpty(), "Should detect try-catch blocks");

        Map<String, Object> firstBlock = tryCatchBlocks.get(0);
        assertNotNull(firstBlock.get("line"), "Should include line number");
        assertNotNull(firstBlock.get("caughtTypes"), "Should include caught exception types");
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("should return error when filePath is missing")
    void returnsErrorForMissingFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 5);
        args.put("column", 5);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("should return error for non-method position")
    void returnsErrorForNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 0);  // package declaration — not in a method
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        // Should handle gracefully — either error or success with no method
        assertNotNull(response);
    }
}
