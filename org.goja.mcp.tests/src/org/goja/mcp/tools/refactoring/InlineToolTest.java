package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.AbstractTool;
import org.goja.mcp.tools.InlineMethodTool;
import org.goja.mcp.tools.InlineTool;
import org.goja.mcp.tools.InlineVariableTool;
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

/** Sprint 16b/A — routing tests for the parametric {@code inline} front door. */
class InlineToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private InlineTool tool;
    private ObjectMapper mapper;
    private String calculatorPath;
    private Map<String, AbstractTool> narrowByKind;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new InlineTool(() -> service, cache);
        mapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        narrowByKind = new LinkedHashMap<>();
        narrowByKind.put("method", new InlineMethodTool(() -> service, cache));
        narrowByKind.put("variable", new InlineVariableTool(() -> service, cache));
    }

    private ObjectNode at(String kind, int line, int column) {
        ObjectNode n = mapper.createObjectNode();
        if (kind != null) n.put("kind", kind);
        n.put("filePath", calculatorPath);
        n.put("line", line);
        n.put("column", column);
        return n;
    }

    @Test
    @DisplayName("schema lists both kinds and requires kind")
    @SuppressWarnings("unchecked")
    void schema_lists_kinds() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> kinds = (List<String>) ((Map<String, Object>) props.get("kind")).get("enum");
        assertTrue(kinds.containsAll(List.of("method", "variable")));
        assertTrue(((List<String>) schema.get("required")).contains("kind"));
    }

    @Test
    @DisplayName("each kind routes to its narrow delegate (parity at a non-inlinable position)")
    void each_kind_routes() {
        for (Map.Entry<String, AbstractTool> e : narrowByKind.entrySet()) {
            String kind = e.getKey();
            ToolResponse viaParam = tool.execute(at(kind, 5, 13));
            ObjectNode narrowArgs = at(kind, 5, 13);
            narrowArgs.remove("kind");
            ToolResponse viaNarrow = e.getValue().execute(narrowArgs);
            assertEquals(viaNarrow.isSuccess(), viaParam.isSuccess(), "kind=" + kind + " success parity");
            JsonNode n = mapper.valueToTree(viaNarrow.isSuccess() ? viaNarrow.getData() : viaNarrow.getError());
            JsonNode p = mapper.valueToTree(viaParam.isSuccess() ? viaParam.getData() : viaParam.getError());
            assertEquals(n, p, "kind=" + kind + " payload parity");
        }
    }

    @Test
    @DisplayName("missing kind returns INVALID_PARAMETER")
    void missing_kind_invalid() {
        assertFalse(tool.execute(at(null, 5, 13)).isSuccess());
    }

    @Test
    @DisplayName("unknown kind returns INVALID_PARAMETER")
    void unknown_kind_invalid() {
        assertFalse(tool.execute(at("typo", 5, 13)).isSuccess());
    }
}
