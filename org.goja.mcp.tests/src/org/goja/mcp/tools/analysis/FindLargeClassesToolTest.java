package org.goja.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindLargeClassesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindLargeClassesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindLargeClassesTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindLargeClassesTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Threshold Detection Tests ==========

    @Test
    @DisplayName("should find classes exceeding low method threshold")
    void findsClassesExceedingMethodThreshold() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxMethods", 5);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<?> largeClasses = (List<?>) data.get("largeClasses");
        assertFalse(largeClasses.isEmpty(), "Should find classes with more than 5 methods");

        // Verify violation details are present
        @SuppressWarnings("unchecked")
        Map<String, Object> firstClass = (Map<String, Object>) largeClasses.get(0);
        assertNotNull(firstClass.get("file"), "Should include file path");
        assertNotNull(firstClass.get("typeName"), "Should include type name");
        assertNotNull(firstClass.get("methodCount"), "Should include method count");
        assertNotNull(firstClass.get("fieldCount"), "Should include field count");
        assertNotNull(firstClass.get("lineCount"), "Should include line count");
        assertNotNull(firstClass.get("violations"), "Should include violations list");
    }

    @Test
    @DisplayName("should find classes exceeding field threshold")
    void findsClassesExceedingFieldThreshold() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxFields", 2);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<?> largeClasses = (List<?>) data.get("largeClasses");
        assertFalse(largeClasses.isEmpty(), "Should find classes with more than 2 fields");
    }

    @Test
    @DisplayName("should return empty results with very high thresholds")
    void returnsEmptyWithHighThresholds() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxMethods", 1000);
        args.put("maxFields", 1000);
        args.put("maxLines", 100000);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<?> largeClasses = (List<?>) data.get("largeClasses");
        assertTrue(largeClasses.isEmpty(), "No classes should exceed very high thresholds");
        assertTrue((int) data.get("totalClassesScanned") > 0, "Should have scanned classes");
    }

    // ========== Default Threshold Tests ==========

    @Test
    @DisplayName("should use default thresholds when not specified")
    void usesDefaultThresholds() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> thresholds = (Map<String, Object>) data.get("thresholds");
        assertEquals(20, thresholds.get("maxMethods"));
        assertEquals(10, thresholds.get("maxFields"));
        assertEquals(300, thresholds.get("maxLines"));
    }

    // ========== Summary Tests ==========

    @Test
    @DisplayName("should report total classes scanned")
    void reportsTotalClassesScanned() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int totalScanned = (int) data.get("totalClassesScanned");
        assertTrue(totalScanned > 0, "Should scan at least one class");
    }
}
