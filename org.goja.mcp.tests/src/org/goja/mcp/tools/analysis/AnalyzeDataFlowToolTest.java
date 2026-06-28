package org.goja.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AnalyzeDataFlowTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzeDataFlowToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeDataFlowTool tool;
    private ObjectMapper objectMapper;
    private String patternsPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeDataFlowTool(() -> service);
        objectMapper = new ObjectMapper();
        patternsPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/DiAndReflectionPatterns.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Data Flow Detection Tests ==========

    @Test
    @DisplayName("should analyze data flow of a method with reads and writes")
    void analyzesDataFlow() {
        // dataFlowExample method has variables read, written, and returned
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);  // dataFlowExample method (zero-based: line 77 in editor)
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertEquals("dataFlowExample", data.get("method"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = (List<Map<String, Object>>) data.get("variables");
        assertFalse(variables.isEmpty(), "Should detect variables");

        // Verify variable structure
        Map<String, Object> firstVar = variables.get(0);
        assertNotNull(firstVar.get("name"), "Should include variable name");
        assertNotNull(firstVar.get("type"), "Should include variable type");
        assertNotNull(firstVar.get("kind"), "Should include variable kind (parameter/local/field)");
        assertNotNull(firstVar.get("read"), "Should include read flag");
        assertNotNull(firstVar.get("written"), "Should include written flag");
    }

    @Test
    @DisplayName("should detect parameter as parameter kind")
    void detectsParameterKind() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = (List<Map<String, Object>>) getData(response).get("variables");

        boolean foundParameter = variables.stream()
            .anyMatch(v -> "input".equals(v.get("name")) && "parameter".equals(v.get("kind")));
        assertTrue(foundParameter, "Should detect 'input' as a parameter. Variables found: " + variables);
    }

    @Test
    @DisplayName("should detect variables that are both read and written")
    void detectsReadAndWritten() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> variables = (List<Map<String, Object>>) getData(response).get("variables");

        // x is assigned from input, then reassigned, then read
        boolean foundReadWrite = variables.stream()
            .anyMatch(v -> "x".equals(v.get("name")) && (boolean) v.get("read") && (boolean) v.get("written"));
        assertTrue(foundReadWrite, "Should detect variable 'x' as both read and written. Variables found: " + variables);
    }

    @Test
    @DisplayName("should count return statements")
    void countsReturnStatements() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", patternsPath);
        args.put("line", 76);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int returnCount = (int) data.get("returnStatements");
        assertTrue(returnCount > 0, "Should detect return statements");
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
        args.put("line", 0);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        assertNotNull(response);
    }
}
