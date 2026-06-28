package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.AbstractTool;
import org.goja.mcp.tools.ExtractConstantTool;
import org.goja.mcp.tools.ExtractInterfaceTool;
import org.goja.mcp.tools.ExtractMethodTool;
import org.goja.mcp.tools.ExtractTool;
import org.goja.mcp.tools.ExtractVariableTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 16b/A — routing tests for the parametric {@code extract} front door.
 * Strategy: discriminator-stripped failure-parity. Minimal args make each
 * delegate fail validation on its own required param; the parametric output must
 * equal the narrow delegate's (with the {@code kind} discriminator removed),
 * which proves correct routing without applying any refactoring.
 */
class ExtractToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractTool tool;
    private ObjectMapper mapper;
    private String calculatorPath;
    private Map<String, AbstractTool> narrowByKind;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new ExtractTool(() -> service, cache);
        mapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        narrowByKind = new LinkedHashMap<>();
        narrowByKind.put("method", new ExtractMethodTool(() -> service, cache));
        narrowByKind.put("variable", new ExtractVariableTool(() -> service, cache));
        narrowByKind.put("constant", new ExtractConstantTool(() -> service, cache));
        narrowByKind.put("interface", new ExtractInterfaceTool(() -> service, cache));
    }

    private ObjectNode minimal(String kind) {
        ObjectNode n = mapper.createObjectNode();
        if (kind != null) n.put("kind", kind);
        n.put("filePath", calculatorPath);
        return n;
    }

    @Test
    @DisplayName("schema lists all four kinds and requires kind")
    @SuppressWarnings("unchecked")
    void schema_lists_kinds() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> kinds = (List<String>) ((Map<String, Object>) props.get("kind")).get("enum");
        assertTrue(kinds.containsAll(List.of("method", "variable", "constant", "interface")));
        assertTrue(((List<String>) schema.get("required")).contains("kind"));
    }

    @Test
    @DisplayName("every kind routes to its narrow delegate (failure parity)")
    void every_kind_routes() {
        for (Map.Entry<String, AbstractTool> e : narrowByKind.entrySet()) {
            String kind = e.getKey();
            ToolResponse viaParam = tool.execute(minimal(kind));
            ObjectNode narrowArgs = minimal(kind);
            narrowArgs.remove("kind");
            ToolResponse viaNarrow = e.getValue().execute(narrowArgs);
            assertEquals(viaNarrow.isSuccess(), viaParam.isSuccess(), "kind=" + kind + " success parity");
            JsonNode n = mapper.valueToTree(viaNarrow.isSuccess() ? viaNarrow.getData() : viaNarrow.getError());
            JsonNode p = mapper.valueToTree(viaParam.isSuccess() ? viaParam.getData() : viaParam.getError());
            assertEquals(n, p, "kind=" + kind + " payload parity (correct delegate)");
        }
    }

    @Test
    @DisplayName("missing kind returns INVALID_PARAMETER")
    void missing_kind_invalid() {
        assertFalse(tool.execute(minimal(null)).isSuccess());
    }

    @Test
    @DisplayName("unknown kind returns INVALID_PARAMETER")
    void unknown_kind_invalid() {
        assertFalse(tool.execute(minimal("typo")).isSuccess());
    }
}
