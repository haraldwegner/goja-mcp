package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.build.DependencyTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 16b/A (v1.1.1) — routing for the five small action-verb front doors
 * (find_references, refactoring, project, quick_fix, dependency). Each: schema
 * advertises its discriminator values; missing/unknown is rejected; a
 * representative action dispatches (failure-parity with the narrow delegate,
 * or success for the read path).
 */
class ActionVerbRoutingTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        cache = new RefactoringChangeCache();
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<String> enumOf(AbstractTool tool, String prop) {
        Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
        return (List<String>) ((Map<String, Object>) props.get(prop)).get("enum");
    }

    private ObjectNode obj(String disc, String value) {
        ObjectNode n = mapper.createObjectNode();
        n.put(disc, value);
        return n;
    }

    // ---- find_references (read-only, parity per kind) ----
    @Test
    @DisplayName("find_references: kinds present; each routes to its narrow delegate")
    void find_references_routes() {
        FindRefsTool tool = new FindRefsTool(() -> service);
        assertTrue(enumOf(tool, "kind").containsAll(List.of("references", "implementations", "method_references")));
        Map<String, AbstractTool> narrow = Map.of(
            "references", new FindReferencesTool(() -> service),
            "implementations", new FindImplementationsTool(() -> service),
            "method_references", new FindMethodReferencesTool(() -> service));
        for (var e : narrow.entrySet()) {
            ToolResponse p = tool.execute(obj("kind", e.getKey()));     // minimal args → delegate validates
            ObjectNode na = obj("kind", e.getKey()); na.remove("kind");
            ToolResponse n = e.getValue().execute(na);
            assertEquals(n.isSuccess(), p.isSuccess(), "find_references kind=" + e.getKey());
            JsonNode np = mapper.valueToTree(n.isSuccess() ? n.getData() : n.getError());
            JsonNode pp = mapper.valueToTree(p.isSuccess() ? p.getData() : p.getError());
            assertEquals(np, pp, "find_references payload parity kind=" + e.getKey());
        }
        assertFalse(tool.execute(obj("kind", "typo")).isSuccess());
        assertFalse(tool.execute(mapper.createObjectNode()).isSuccess());
    }

    // ---- project (dispatches via execute(); list works on a loaded workspace) ----
    @Test
    @DisplayName("project: list dispatches and succeeds; add/remove present; invalid rejected")
    void project_routes() {
        ProjectTool tool = new ProjectTool(() -> service);
        assertTrue(enumOf(tool, "action").containsAll(List.of("list", "add", "remove")));
        assertTrue(tool.execute(obj("action", "list")).isSuccess(), "list works on a loaded workspace");
        assertFalse(tool.execute(obj("action", "typo")).isSuccess());
        assertFalse(tool.execute(mapper.createObjectNode()).isSuccess());
    }

    // ---- refactoring (apply/undo/inspect; minimal args fail identically) ----
    @Test
    @DisplayName("refactoring: actions present; bad/missing id fails like the narrow tool")
    void refactoring_routes() {
        RefactoringTool tool = new RefactoringTool(() -> service, cache);
        assertTrue(enumOf(tool, "action").containsAll(List.of("apply", "undo", "inspect")));
        // apply with an unknown changeId → not success (same as ApplyRefactoringTool).
        ObjectNode a = obj("action", "apply"); a.put("changeId", "nope");
        assertFalse(tool.execute(a).isSuccess());
        assertFalse(tool.execute(obj("action", "typo")).isSuccess());
        assertFalse(tool.execute(mapper.createObjectNode()).isSuccess());
    }

    // ---- quick_fix ----
    @Test
    @DisplayName("quick_fix: actions present; missing/unknown rejected")
    void quick_fix_routes() {
        QuickFixTool tool = new QuickFixTool(() -> service);
        assertTrue(enumOf(tool, "action").containsAll(List.of("suggest_imports", "list", "apply")));
        assertFalse(tool.execute(obj("action", "typo")).isSuccess());
        assertFalse(tool.execute(mapper.createObjectNode()).isSuccess());
        // list with no filePath → delegate validates → not success.
        assertFalse(tool.execute(obj("action", "list")).isSuccess());
    }

    // ---- dependency ----
    @Test
    @DisplayName("dependency: actions present; missing/unknown rejected")
    void dependency_routes() {
        DependencyTool tool = new DependencyTool(() -> service);
        assertTrue(enumOf(tool, "action").containsAll(List.of("add", "update", "find_unused")));
        assertFalse(tool.execute(obj("action", "typo")).isSuccess());
        assertFalse(tool.execute(mapper.createObjectNode()).isSuccess());
        // add with no coordinates → delegate validates → not success.
        assertFalse(tool.execute(obj("action", "add")).isSuccess());
    }
}
