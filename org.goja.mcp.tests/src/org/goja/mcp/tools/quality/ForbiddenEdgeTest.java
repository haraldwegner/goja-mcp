package org.goja.mcp.tools.quality;

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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a P1-d — the forbidden_edge detector on the {@code forbidden-edge}
 * fixture: a declared {from, forbidden} package rule reports the offending
 * import; the reverse rule and the no-rule case report nothing.
 */
class ForbiddenEdgeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("forbidden-edge");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findings(ToolResponse r) {
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return (List<Map<String, Object>>) data.get("findings");
    }

    private ToolResponse run(String from, String forbidden) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "forbidden_edge");
        if (from != null) {
            args.put("from", from);
        }
        if (forbidden != null) {
            args.put("forbidden", forbidden);
        }
        return tool.execute(args);
    }

    @Test
    @DisplayName("forbidden_edge is a registered kind (projected from the catalog)")
    void forbiddenEdge_isRegisteredKind() {
        // A structurally-valid call must not be rejected as an unknown kind.
        ToolResponse r = run("com.example.api", "com.example.internal");
        assertTrue(r.isSuccess(), "forbidden_edge must be a known kind; got: " + r.getError());
    }

    @Test
    @DisplayName("declared rule api -> internal reports the offending import")
    void reportsViolation_forDeclaredRule() {
        List<Map<String, Object>> fs = findings(run("com.example.api", "com.example.internal"));
        assertEquals(1, fs.size(), "exactly one forbidden edge expected; got: " + fs);
        Map<String, Object> f = fs.get(0);
        assertTrue(String.valueOf(f.get("filePath")).endsWith("Service.java"),
            "the violation must point at Service.java: " + f);
        assertEquals("forbidden_edge", f.get("kind"));
    }

    @Test
    @DisplayName("the reverse rule internal -> api reports nothing")
    void reverseRule_reportsNothing() {
        assertEquals(0, findings(run("com.example.internal", "com.example.api")).size());
    }

    @Test
    @DisplayName("no rule reports nothing (safe inside a family sweep)")
    void noRule_reportsNothing() {
        assertEquals(0, findings(run(null, null)).size());
    }
}
