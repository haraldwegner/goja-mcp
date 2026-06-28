package org.goja.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindNamingViolationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindNamingViolationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindNamingViolationsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindNamingViolationsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Violation Detection Tests ==========

    @Test
    @DisplayName("should find naming violations in file with bad names")
    void findsNamingViolations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int totalViolations = (int) data.get("totalViolations");
        assertTrue(totalViolations > 0, "Should find naming violations in DiAndReflectionPatterns.java");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) data.get("violations");
        assertFalse(violations.isEmpty());

        // Verify violation structure
        Map<String, Object> firstViolation = violations.get(0);
        assertNotNull(firstViolation.get("file"), "Should include file path");
        assertNotNull(firstViolation.get("line"), "Should include line number");
        assertNotNull(firstViolation.get("elementType"), "Should include element type");
        assertNotNull(firstViolation.get("name"), "Should include the name");
        assertNotNull(firstViolation.get("convention"), "Should include expected convention");
    }

    @Test
    @DisplayName("should detect Bad_Field_Name as field naming violation")
    void detectsBadFieldName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) getData(response).get("violations");

        boolean foundFieldViolation = violations.stream()
            .anyMatch(v -> "Bad_Field_Name".equals(v.get("name")) && "field".equals(v.get("elementType")));
        assertTrue(foundFieldViolation, "Should detect Bad_Field_Name as a field naming violation");
    }

    @Test
    @DisplayName("should detect Bad_Method_Name as method naming violation")
    void detectsBadMethodName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) getData(response).get("violations");

        boolean foundMethodViolation = violations.stream()
            .anyMatch(v -> "Bad_Method_Name".equals(v.get("name")) && "method".equals(v.get("elementType")));
        assertTrue(foundMethodViolation, "Should detect Bad_Method_Name as a method naming violation");
    }

    @Test
    @DisplayName("should detect badConstant as constant naming violation")
    void detectsBadConstantName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) getData(response).get("violations");

        boolean foundConstantViolation = violations.stream()
            .anyMatch(v -> "badConstant".equals(v.get("name")) && "constant".equals(v.get("elementType")));
        assertTrue(foundConstantViolation, "Should detect badConstant as a constant naming violation");
    }

    // ========== Clean File Tests ==========

    @Test
    @DisplayName("should return no violations for well-named file")
    void returnsNoViolationsForCleanFile() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/Calculator.java");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(0, data.get("totalViolations"));
    }

    // ========== Project-Wide Scan Tests ==========

    @Test
    @DisplayName("should scan all files when no filePath specified")
    void scansAllFilesWhenNoPathSpecified() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int filesScanned = (int) data.get("filesScanned");
        assertTrue(filesScanned > 1, "Should scan multiple files");
    }
}
