package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.AbstractTool;
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

/**
 * Sprint 16b/A — routing tests for the parametric {@code generate} front door.
 * Uses a non-resolving caret + auto_apply=false so every kind fails validation
 * identically (no mutation); the error parity proves correct routing, including
 * the getters_setters accessor-kind alias.
 */
class GenerateToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GenerateTool tool;
    private ObjectMapper mapper;
    private String calculatorPath;
    private Map<String, AbstractTool> narrowByKind;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new GenerateTool(() -> service, cache);
        mapper = new ObjectMapper();
        calculatorPath = helper.getFixturePath("simple-maven")
            .resolve("src/main/java/com/example/Calculator.java").toString();
        narrowByKind = new LinkedHashMap<>();
        narrowByKind.put("constructor", new GenerateConstructorTool(() -> service, cache));
        narrowByKind.put("getters_setters", new GenerateGettersSettersTool(() -> service, cache));
        narrowByKind.put("equals_hashcode", new GenerateEqualsHashCodeTool(() -> service, cache));
        narrowByKind.put("tostring", new GenerateToStringTool(() -> service, cache));
        narrowByKind.put("test_skeleton", new GenerateTestSkeletonTool(() -> service, cache));
        narrowByKind.put("override_methods", new OverrideMethodsTool(() -> service, cache));
    }

    private ObjectNode minimal(String kind) {
        ObjectNode n = mapper.createObjectNode();
        if (kind != null) n.put("kind", kind);
        n.put("filePath", calculatorPath);
        n.put("line", 0);          // non-resolving caret (package line)
        n.put("column", 0);
        n.put("auto_apply", false); // never mutate
        return n;
    }

    @Test
    @DisplayName("schema lists all six kinds + accessorKind alias; requires kind")
    @SuppressWarnings("unchecked")
    void schema_lists_kinds() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        List<String> kinds = (List<String>) ((Map<String, Object>) props.get("kind")).get("enum");
        assertTrue(kinds.containsAll(List.of("constructor", "getters_setters", "equals_hashcode",
            "tostring", "test_skeleton", "override_methods")));
        assertTrue(props.containsKey("accessorKind"), "getters_setters accessor exposed as accessorKind");
        assertTrue(((List<String>) schema.get("required")).contains("kind"));
    }

    @Test
    @DisplayName("every kind routes to its narrow delegate (error parity, no mutation)")
    void every_kind_routes() {
        for (Map.Entry<String, AbstractTool> e : narrowByKind.entrySet()) {
            String kind = e.getKey();
            ToolResponse viaParam = tool.execute(minimal(kind));
            assertFalse(viaParam.isSuccess(), "kind=" + kind + " must fail with minimal args (no mutation)");

            ObjectNode narrowArgs = minimal(kind);
            narrowArgs.remove("kind"); // delegate uses its own defaults
            ToolResponse viaNarrow = e.getValue().execute(narrowArgs);
            assertFalse(viaNarrow.isSuccess(), "narrow kind=" + kind + " also fails");
            assertEquals(mapper.valueToTree(viaNarrow.getError()), mapper.valueToTree(viaParam.getError()),
                "kind=" + kind + " error parity (correct delegate)");
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
