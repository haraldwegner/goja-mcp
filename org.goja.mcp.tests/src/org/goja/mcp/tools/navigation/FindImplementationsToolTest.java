package org.goja.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindImplementationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for FindImplementationsTool.
 * Tests finding implementations of types and methods.
 */
class FindImplementationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindImplementationsTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindImplementationsTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getImplementations(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("implementations");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Class implementations returns symbol, isInterface, totalCount, and implementation list")
    void classImplementations_returnsAllFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("symbol"));
        assertEquals(false, data.get("isInterface"));
        assertNotNull(data.get("totalImplementations"));

        List<Map<String, Object>> implementations = getImplementations(data);
        assertNotNull(implementations);
        for (Map<String, Object> impl : implementations) {
            assertNotNull(impl.get("qualifiedName"));
        }
    }

    @Test
    @DisplayName("Method position finds containing type implementations")
    void methodPosition_findsContainingTypeImplementations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("symbol"));
    }

    // ========== Optional Parameters Tests ==========

    @Test
    @DisplayName("maxResults limits number of implementations returned")
    void maxResults_limitsImplementations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);
        args.put("maxResults", 5);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> implementations = getImplementations(data);
        assertTrue(implementations.size() <= 5);
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid parameters return error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 5);
        args1.put("column", 10);
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Negative line
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("line", -1);
        args2.put("column", 10);
        assertFalse(tool.execute(args2).isSuccess());

        // Negative column
        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("filePath", calculatorPath);
        args3.put("line", 5);
        args3.put("column", -1);
        assertFalse(tool.execute(args3).isSuccess());
    }

    // ========== Edge Case Tests ==========

    @Test
    @DisplayName("Field position finds enclosing type implementations")
    void fieldPosition_findsEnclosingTypeImplementations() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 6);
        args.put("column", 16);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("Calculator", data.get("symbol"));
    }

    @Test
    @DisplayName("Position with no symbol handles gracefully")
    void positionWithNoSymbol_handlesGracefully() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);
        args.put("column", 0);

        ToolResponse response = tool.execute(args);

        assertNotNull(response);
    }

    @Test
    @DisplayName("Sprint 14 (bugs.md #12): schema description discloses position-based contract + v1.8.0 FQN overload hint")
    void schemaDescription_disclosesPositionContract() {
        String desc = tool.getDescription();
        assertTrue(desc.contains("filePath, line, column"),
            "description must spell out the (filePath, line, column) triple; got:\n" + desc);
        assertTrue(desc.contains("ZERO-BASED"),
            "description must keep the zero-based coordinate warning; got:\n" + desc);
        assertTrue(desc.contains("v1.8.0"),
            "description must flag the upcoming v1.8.0 FQN overload; got:\n" + desc);
    }

    @Test
    @DisplayName("Sprint 14 Phase B.2 (bugs.md #12 capability half): FQN form via 'symbol' param resolves and runs the implementations search")
    void fqnForm_typeFqn_runsImplementationsSearch() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("symbol", "com.example.Animal");
        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(),
            "FQN form must succeed for a known type; got: " + response.getError());
        assertNotNull(response.getData());
    }
}
