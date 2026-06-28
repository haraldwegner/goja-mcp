package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.AbstractTool;
import org.goja.mcp.tools.MoveInHierarchyTool;
import org.goja.mcp.tools.PullUpTool;
import org.goja.mcp.tools.PushDownTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 16b/A — routing tests for the parametric {@code move_in_hierarchy} front door. */
class MoveInHierarchyToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private MoveInHierarchyTool tool;
    private ObjectMapper mapper;
    private String calculatorPath;
    private Map<String, AbstractTool> narrowByDirection;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new MoveInHierarchyTool(() -> service, cache);
        mapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        narrowByDirection = new LinkedHashMap<>();
        narrowByDirection.put("up", new PullUpTool(() -> service, cache));
        narrowByDirection.put("down", new PushDownTool(() -> service, cache));
    }

    private ObjectNode at(String direction, int line, int column) {
        ObjectNode n = mapper.createObjectNode();
        if (direction != null) n.put("direction", direction);
        n.put("filePath", calculatorPath);
        n.put("line", line);
        n.put("column", column);
        return n;
    }

    @Test
    @DisplayName("schema lists both directions and requires direction")
    @SuppressWarnings("unchecked")
    void schema_lists_directions() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> dirs = (List<String>) ((Map<String, Object>) props.get("direction")).get("enum");
        assertTrue(dirs.containsAll(List.of("up", "down")));
        assertTrue(((List<String>) schema.get("required")).contains("direction"));
    }

    @Test
    @DisplayName("each direction routes to its narrow delegate (parity)")
    void each_direction_routes() {
        for (Map.Entry<String, AbstractTool> e : narrowByDirection.entrySet()) {
            String dir = e.getKey();
            ToolResponse viaParam = tool.execute(at(dir, 5, 13));
            ObjectNode narrowArgs = at(dir, 5, 13);
            narrowArgs.remove("direction");
            ToolResponse viaNarrow = e.getValue().execute(narrowArgs);
            assertEquals(viaNarrow.isSuccess(), viaParam.isSuccess(), "direction=" + dir + " success parity");
            JsonNode n = mapper.valueToTree(viaNarrow.isSuccess() ? viaNarrow.getData() : viaNarrow.getError());
            JsonNode p = mapper.valueToTree(viaParam.isSuccess() ? viaParam.getData() : viaParam.getError());
            assertEquals(n, p, "direction=" + dir + " payload parity");
        }
    }

    @Test
    @DisplayName("missing direction returns INVALID_PARAMETER")
    void missing_direction_invalid() {
        assertFalse(tool.execute(at(null, 5, 13)).isSuccess());
    }

    @Test
    @DisplayName("unknown direction returns INVALID_PARAMETER")
    void unknown_direction_invalid() {
        assertFalse(tool.execute(at("sideways", 5, 13)).isSuccess());
    }
}
