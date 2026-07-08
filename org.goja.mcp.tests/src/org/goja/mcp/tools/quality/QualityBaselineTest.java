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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a P2-c — baseline / trend diffing on a family sweep: a first diff (no
 * baseline) reports every finding as new; after save, a re-run reports them all
 * unchanged.
 */
class QualityBaselineTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    private Map<String, Object> run(String baseline) {
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "fowler");
        args.put("baseline", baseline);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return data;
    }

    @Test
    @DisplayName("diff→save→diff: first run is all new, after save all unchanged")
    void baselineRoundTrip() {
        // 1. First diff with no saved baseline — every finding is new.
        Map<String, Object> d1 = run("diff");
        int firstNew = ((Number) d1.get("newCount")).intValue();
        assertTrue(firstNew > 0, "the fowler sweep must report findings on simple-maven: " + d1);
        assertEquals(0, ((Number) d1.get("fixedCount")).intValue());
        assertEquals(0, ((Number) d1.get("unchangedCount")).intValue());

        // 2. Save the snapshot.
        Map<String, Object> d2 = run("save");
        assertEquals("saved", d2.get("baseline"));
        assertEquals(firstNew, ((Number) d2.get("baselineSize")).intValue());

        // 3. Diff again against the saved snapshot — no change, all unchanged.
        Map<String, Object> d3 = run("diff");
        assertEquals(0, ((Number) d3.get("newCount")).intValue(),
            "a re-diff with no source change reports nothing new: " + d3);
        assertEquals(firstNew, ((Number) d3.get("unchangedCount")).intValue(),
            "all prior findings are unchanged: " + d3);
    }
}
