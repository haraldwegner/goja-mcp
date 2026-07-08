package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.RefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 18 — refactoring(action=inspect_plan|undo_plan): review + revert a plan. */
class RefactorPlanInspectUndoToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RefactoringTool tool;
    private ObjectMapper mapper;
    private Path targetFile;

    @BeforeEach
    void setUp() throws Exception {
        // compile-clean fixture (see RefactorPlanApplyToolTest): apply_plan needs a real
        // 0/0 parity baseline, which simple-maven lost post Sprint 22a P0-a.
        JdtServiceImpl service = helper.loadProjectCopy("compose-clean");
        tool = new RefactoringTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        targetFile = helper.getTempDirectory()
            .resolve("compose-clean/src/main/java/com/example/ComposeMethodTargets.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode section(int startLine, int endLine, String name) {
        ObjectNode s = mapper.createObjectNode();
        s.put("startLine", startLine);
        s.put("startColumn", 8);
        s.put("endLine", endLine);
        s.put("endColumn", 44);
        s.put("methodName", name);
        return s;
    }

    private String plan() {
        ObjectNode a = mapper.createObjectNode();
        a.put("action", "plan");
        a.put("kind", "compose_method");
        a.put("filePath", targetFile.toString());
        ArrayNode arr = a.putArray("sections");
        arr.add(section(13, 13, "writeHeader"));
        arr.add(section(18, 18, "writeFooter"));
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        return (String) getData(r).get("planId");
    }

    private ToolResponse act(String action, String planId) {
        ObjectNode a = mapper.createObjectNode();
        a.put("action", action);
        a.put("planId", planId);
        return tool.execute(a);
    }

    @Test
    @DisplayName("inspect_plan reflects pending steps before apply and applied steps after")
    void inspect_reflectsLifecycle() {
        String planId = plan();

        Map<String, Object> before = getData(act("inspect_plan", planId));
        assertEquals(Boolean.FALSE, before.get("applied"));
        assertEquals(-1, before.get("appliedThrough"));
        List<?> steps = (List<?>) before.get("steps");
        assertEquals(2, steps.size());
        assertTrue(steps.stream().allMatch(s -> "pending".equals(((Map<?, ?>) s).get("status"))));

        assertTrue(act("apply_plan", planId).isSuccess());

        Map<String, Object> after = getData(act("inspect_plan", planId));
        assertEquals(Boolean.TRUE, after.get("applied"));
        assertEquals(1, after.get("appliedThrough"));
        assertTrue(((List<?>) after.get("steps")).stream()
            .allMatch(s -> "applied".equals(((Map<?, ?>) s).get("status"))));
    }

    @Test
    @DisplayName("undo_plan reverts a fully-applied plan byte-identically")
    void undoPlan_restores() throws Exception {
        String original = Files.readString(targetFile);
        String planId = plan();
        assertTrue(act("apply_plan", planId).isSuccess());
        assertFalse(original.equals(Files.readString(targetFile)), "apply changed the file");

        ToolResponse undo = act("undo_plan", planId);
        assertTrue(undo.isSuccess(), () -> String.valueOf(undo.getError()));
        assertEquals(Boolean.TRUE, getData(undo).get("undone"));
        assertEquals(original, Files.readString(targetFile), "undo_plan must restore the original byte-for-byte");

        // The plan is now back to pending.
        assertEquals(-1, getData(act("inspect_plan", planId)).get("appliedThrough"));
    }

    @Test
    @DisplayName("undo_plan on a not-yet-applied plan is a no-op; unknown planIds error")
    void undoPlan_edgeCases() {
        String planId = plan();
        Map<String, Object> noop = getData(act("undo_plan", planId));
        assertEquals(Boolean.FALSE, noop.get("undone"));

        assertFalse(act("undo_plan", "plan-nope").isSuccess());
        assertFalse(act("inspect_plan", "plan-nope").isSuccess());
    }
}
