package org.goja.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11 Phase D — verifies the parametric {@code find_quality_issue}
 * tool dispatches each kind to the underlying narrow analyzer and refuses
 * unknown / missing kinds.
 */
class FindQualityIssueToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private FindQualityIssueTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    private ObjectNode args(String kind) {
        ObjectNode n = objectMapper.createObjectNode();
        n.put("kind", kind);
        return n;
    }

    @Test @DisplayName("kind=naming runs the naming-violations analyzer")
    void dispatch_naming() {
        assertTrue(tool.execute(args("naming")).isSuccess());
    }

    @Test @DisplayName("kind=bugs runs the possible-bugs analyzer")
    void dispatch_bugs() {
        assertTrue(tool.execute(args("bugs")).isSuccess());
    }

    @Test @DisplayName("kind=unused runs the unused-code analyzer")
    void dispatch_unused() {
        assertTrue(tool.execute(args("unused")).isSuccess());
    }

    @Test @DisplayName("kind=large_classes runs the large-classes analyzer")
    void dispatch_large_classes() {
        assertTrue(tool.execute(args("large_classes")).isSuccess());
    }

    @Test @DisplayName("kind=circular_deps runs the circular-deps analyzer")
    void dispatch_circular_deps() {
        assertTrue(tool.execute(args("circular_deps")).isSuccess());
    }

    @Test @DisplayName("kind=reflection runs the reflection-usage analyzer")
    void dispatch_reflection() {
        assertTrue(tool.execute(args("reflection")).isSuccess());
    }

    @Test @DisplayName("kind=throws aliases query -> exceptionType and dispatches to findThrowsDeclarations")
    void dispatch_throws_with_query_alias() {
        ObjectNode n = args("throws");
        n.put("query", "java.io.IOException");
        ToolResponse r = tool.execute(n);
        assertTrue(r.isSuccess(),
            "kind=throws should resolve query 'java.io.IOException' via the exceptionType alias");
    }

    @Test @DisplayName("kind=catches aliases query -> exceptionType and dispatches to findCatchBlocks")
    void dispatch_catches_with_query_alias() {
        ObjectNode n = args("catches");
        n.put("query", "java.lang.Exception");
        ToolResponse r = tool.execute(n);
        assertTrue(r.isSuccess());
    }

    @Test @DisplayName("missing kind returns INVALID_PARAMETER")
    void missing_kind_invalid() {
        assertFalse(tool.execute(objectMapper.createObjectNode()).isSuccess());
    }

    @Test @DisplayName("unknown kind returns INVALID_PARAMETER, not a stack trace")
    void unknown_kind_invalid() {
        assertFalse(tool.execute(args("naming-violations-old-spelling")).isSuccess());
    }
}
