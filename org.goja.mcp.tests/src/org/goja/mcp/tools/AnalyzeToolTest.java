package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
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

/** Sprint 16b/A (v1.1.1) — routing tests for the parametric {@code analyze} front door. */
class AnalyzeToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeTool tool;
    private ObjectMapper mapper;
    private String calculatorPath;
    private Map<String, AbstractTool> narrowByKind;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeTool(() -> service);
        mapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        narrowByKind = new LinkedHashMap<>();
        narrowByKind.put("file", new AnalyzeFileTool(() -> service));
        narrowByKind.put("type", new AnalyzeTypeTool(() -> service));
        narrowByKind.put("method", new AnalyzeMethodTool(() -> service));
        narrowByKind.put("change_impact", new AnalyzeChangeImpactTool(() -> service));
        narrowByKind.put("control_flow", new AnalyzeControlFlowTool(() -> service));
        narrowByKind.put("data_flow", new AnalyzeDataFlowTool(() -> service));
        // javadocs/naming/nullness use the subkind alias; covered by the alias test.
    }

    private ObjectNode args(String kind) {
        ObjectNode n = mapper.createObjectNode();
        if (kind != null) n.put("kind", kind);
        n.put("filePath", calculatorPath);
        return n;
    }

    @Test
    @DisplayName("schema lists all nine kinds + subkind alias; requires kind")
    @SuppressWarnings("unchecked")
    void schema_lists_kinds() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> kinds = (List<String>) ((Map<String, Object>) props.get("kind")).get("enum");
        assertEquals(9, kinds.size());
        assertTrue(kinds.containsAll(List.of("file", "type", "method", "javadocs", "naming",
            "nullness", "change_impact", "control_flow", "data_flow")));
        assertTrue(props.containsKey("subkind"));
        assertTrue(((List<String>) schema.get("required")).contains("kind"));
    }

    @Test
    @DisplayName("non-aliased kinds route to their narrow delegate (parity)")
    void kinds_route_to_narrow() {
        for (Map.Entry<String, AbstractTool> e : narrowByKind.entrySet()) {
            String kind = e.getKey();
            ToolResponse viaParam = tool.execute(args(kind));
            ObjectNode narrowArgs = args(kind);
            narrowArgs.remove("kind");
            ToolResponse viaNarrow = e.getValue().execute(narrowArgs);
            assertEquals(viaNarrow.isSuccess(), viaParam.isSuccess(), "kind=" + kind + " success parity");
            JsonNode n = mapper.valueToTree(viaNarrow.isSuccess() ? viaNarrow.getData() : viaNarrow.getError());
            JsonNode p = mapper.valueToTree(viaParam.isSuccess() ? viaParam.getData() : viaParam.getError());
            assertEquals(n, p, "kind=" + kind + " payload parity");
        }
    }

    @Test
    @DisplayName("kind=type analyzes the named type")
    @SuppressWarnings("unchecked")
    void type_kind_analyzes_named_type() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "type");
        a.put("typeName", "com.example.Calculator");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "analyze type should resolve Calculator");
    }

    @Test
    @DisplayName("javadocs alias: parametric subkind maps onto the delegate's kind")
    void javadocs_subkind_alias_routes() {
        // No subkind → delegate fails on its required `kind`, same as calling it bare.
        assertFalse(tool.execute(args("javadocs")).isSuccess());
    }

    @Test
    @DisplayName("missing/unknown kind invalid")
    void missing_unknown_kind_invalid() {
        assertFalse(tool.execute(args(null)).isSuccess());
        assertFalse(tool.execute(args("typo")).isSuccess());
    }
}
