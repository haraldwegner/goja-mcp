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

/** Sprint 16b/A (v1.1.1) — routing tests for the parametric {@code inspect} front door (all read-only). */
class InspectToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private InspectTool tool;
    private ObjectMapper mapper;
    private String calculatorPath;
    private Map<String, AbstractTool> narrowByKind;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new InspectTool(() -> service);
        mapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        narrowByKind = new LinkedHashMap<>();
        narrowByKind.put("type_hierarchy", new GetTypeHierarchyTool(() -> service));
        narrowByKind.put("document_symbols", new GetDocumentSymbolsTool(() -> service));
        narrowByKind.put("type_members", new GetTypeMembersTool(() -> service));
        narrowByKind.put("classpath", new GetClasspathInfoTool(() -> service));
        narrowByKind.put("project_structure", new GetProjectStructureTool(() -> service));
        narrowByKind.put("type_usage", new GetTypeUsageSummaryTool(() -> service));
        narrowByKind.put("complexity", new GetComplexityMetricsTool(() -> service));
        narrowByKind.put("dependency_graph", new GetDependencyGraphTool(() -> service));
        narrowByKind.put("di_registrations", new GetDiRegistrationsTool(() -> service));
    }

    private ObjectNode args(String kind) {
        ObjectNode n = mapper.createObjectNode();
        if (kind != null) n.put("kind", kind);
        n.put("typeName", "com.example.Calculator");
        n.put("filePath", calculatorPath);
        return n;
    }

    @Test
    @DisplayName("schema lists all nine kinds; requires kind")
    @SuppressWarnings("unchecked")
    void schema_lists_kinds() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> kinds = (List<String>) ((Map<String, Object>) props.get("kind")).get("enum");
        assertEquals(9, kinds.size());
        assertTrue(((List<String>) schema.get("required")).contains("kind"));
    }

    @Test
    @DisplayName("every kind routes to its narrow delegate (read-only parity)")
    void every_kind_routes() {
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
    @DisplayName("kind=type_members lists Calculator's members")
    void type_members_resolves() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "type_members");
        a.put("typeName", "com.example.Calculator");
        assertTrue(tool.execute(a).isSuccess());
    }

    @Test
    @DisplayName("missing/unknown kind invalid")
    void missing_unknown_kind_invalid() {
        assertFalse(tool.execute(args(null)).isSuccess());
        assertFalse(tool.execute(args("typo")).isSuccess());
    }
}
