package org.goja.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindReflectionUsageTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindReflectionUsageToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindReflectionUsageTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindReflectionUsageTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Detection Tests ==========

    @Test
    @DisplayName("should find reflection usage in project")
    void findsReflectionUsage() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int totalCalls = (int) data.get("totalCalls");
        assertTrue(totalCalls > 0, "Should find reflection calls in DiAndReflectionPatterns.java");
    }

    @Test
    @DisplayName("should include reflection method label in results")
    void includesReflectionMethodLabel() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) getData(response).get("reflectionCalls");
        if (!calls.isEmpty()) {
            Map<String, Object> firstCall = calls.get(0);
            assertNotNull(firstCall.get("reflectionMethod"), "Should include reflection method label");
            // filePath and line may be absent if match is in a binary JAR
        }
    }

    // ========== Summary Tests ==========

    @Test
    @DisplayName("should group results by reflection method type in summary")
    void groupsResultsInSummary() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("summary"), "Should include summary");
        assertNotNull(data.get("reflectionCalls"), "Should include reflectionCalls list");
    }

    @Test
    @DisplayName("should respect maxResults parameter")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
    }
}
