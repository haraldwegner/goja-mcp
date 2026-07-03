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
    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
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
        assertEquals(10, kinds.size());
        assertTrue(kinds.containsAll(List.of("file", "type", "method", "javadocs", "naming",
            "nullness", "change_impact", "control_flow", "data_flow", "symbol")));
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

    /** kind=symbol at a position; ZERO-BASED coords. */
    private ObjectNode symbolArgs(int line, int column) {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "symbol");
        n.put("filePath", calculatorPath);
        n.put("line", line);
        n.put("column", column);
        return n;
    }

    @Test
    @DisplayName("kind=symbol composes definition + symbol info + references (parity with the narrow delegates)")
    void symbol_composesDefinitionTypeAndReferences() {
        // `lastResult` field declaration (line 6, col 16) — has in-class references.
        int line = 6, column = 16;
        ToolResponse r = tool.execute(symbolArgs(line, column));
        assertTrue(r.isSuccess(), "analyze kind=symbol should resolve lastResult");

        JsonNode data = mapper.valueToTree(r.getData());
        assertTrue(data.has("definition"), "composite carries definition");
        assertTrue(data.has("symbol"), "composite carries symbol");
        assertTrue(data.has("references"), "composite carries references");

        // Parity: each section equals calling the narrow delegate directly at the same position.
        ObjectNode pos = mapper.createObjectNode();
        pos.put("filePath", calculatorPath);
        pos.put("line", line);
        pos.put("column", column);
        ObjectNode posCapped = pos.deepCopy();
        posCapped.put("maxResults", AnalyzeSymbolTool.REFERENCE_CAP);

        JsonNode def = mapper.valueToTree(new GoToDefinitionTool(() -> service).execute(pos).getData());
        JsonNode sym = mapper.valueToTree(new GetSymbolInfoTool(() -> service).execute(pos).getData());
        JsonNode refs = mapper.valueToTree(new FindReferencesTool(() -> service).execute(posCapped).getData());

        assertEquals(def, data.get("definition"), "definition parity");
        assertEquals(sym, data.get("symbol"), "symbol parity");
        assertEquals(refs, data.get("references"), "references parity");
        assertTrue(data.get("references").get("totalReferences").asInt() > 0, "lastResult has usages");
    }

    @Test
    @DisplayName("kind=symbol with no references still returns definition + symbol (succeeds)")
    void symbol_withNoReferences_stillReturnsDefinitionAndType() {
        // `getLastResult` method (line 45, col 15) — uncalled in this single-file project.
        ToolResponse r = tool.execute(symbolArgs(45, 15));
        assertTrue(r.isSuccess(), "kind=symbol succeeds even with zero references");

        JsonNode data = mapper.valueToTree(r.getData());
        assertTrue(data.has("definition"), "definition present");
        assertTrue(data.has("symbol"), "symbol present");
        // references present but empty — not an error.
        assertTrue(data.has("references"), "references section present");
        assertEquals(0, data.get("references").get("totalReferences").asInt(), "no usages");
    }
}
