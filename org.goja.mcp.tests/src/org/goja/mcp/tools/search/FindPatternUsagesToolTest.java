package org.goja.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindPatternUsagesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11 Phase D — verifies the parametric {@code find_pattern_usages}
 * tool dispatches correctly to each underlying SearchService kind, plus
 * input-validation guards.
 */
class FindPatternUsagesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindPatternUsagesTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindPatternUsagesTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    private ObjectNode args(String kind, String query) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("kind", kind);
        n.put("query", query);
        return n;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("kind=annotation dispatches to findAnnotationUsages and reports kind+query in the response")
    void dispatch_annotation() {
        ToolResponse r = tool.execute(args("annotation", "java.lang.Override"));
        assertTrue(r.isSuccess(), "annotation kind should succeed for a real annotation type");
        assertEquals("annotation", data(r).get("kind"));
        assertEquals("java.lang.Override", data(r).get("query"));
    }

    @Test @DisplayName("kind=instantiation succeeds for a real type (java.util.ArrayList)")
    void dispatch_instantiation() {
        ToolResponse r = tool.execute(args("instantiation", "java.util.ArrayList"));
        assertTrue(r.isSuccess());
        assertEquals("instantiation", data(r).get("kind"));
    }

    @Test @DisplayName("kind=type_argument succeeds")
    void dispatch_type_argument() {
        ToolResponse r = tool.execute(args("type_argument", "java.lang.String"));
        assertTrue(r.isSuccess());
        assertEquals("type_argument", data(r).get("kind"));
    }

    @Test @DisplayName("kind=cast succeeds (project type — same fixture FindCastsToolTest uses)")
    void dispatch_cast() {
        ToolResponse r = tool.execute(args("cast", "com.example.Calculator"));
        assertTrue(r.isSuccess(),
            "kind=cast should resolve to a project type the fixture exercises");
        assertEquals("cast", data(r).get("kind"));
    }

    @Test @DisplayName("kind=instanceof succeeds (project type)")
    void dispatch_instanceof() {
        ToolResponse r = tool.execute(args("instanceof", "com.example.Animal"));
        assertTrue(r.isSuccess());
        assertEquals("instanceof", data(r).get("kind"));
    }

    @Test @DisplayName("missing kind returns INVALID_PARAMETER")
    void missing_kind_invalid() {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("query", "java.util.ArrayList");
        assertFalse(tool.execute(n).isSuccess());
    }

    @Test @DisplayName("unknown kind returns INVALID_PARAMETER, not a stack trace")
    void unknown_kind_invalid() {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("kind", "totally-bogus");
        n.put("query", "java.util.ArrayList");
        assertFalse(tool.execute(n).isSuccess());
    }

    @Test @DisplayName("missing query returns INVALID_PARAMETER")
    void missing_query_invalid() {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("kind", "annotation");
        assertFalse(tool.execute(n).isSuccess());
    }
}
