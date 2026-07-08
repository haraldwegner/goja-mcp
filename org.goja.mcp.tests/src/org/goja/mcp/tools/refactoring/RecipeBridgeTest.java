package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a R.1 (Stage 14) — the recipe bridge. A find_quality_issue SMELL kind
 * (not itself a plan kind) hands straight to {@code refactoring(action=plan, kind=<smell>)},
 * which resolves it to a cure recipe and returns a planId that {@code apply_plan} runs
 * PARITY-GATED. Fixture: {@code recipe-bridge} — a compile-clean GoF singleton, so the
 * non-OCP {@code singleton -> inline_singleton} recipe applies with the compile staying
 * 0/0. A smell with no recipe is rejected honestly (no stub).
 */
class RecipeBridgeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RefactoringTool tool;
    private ObjectMapper mapper;
    private Path registryFile;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("recipe-bridge");
        tool = new RefactoringTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        registryFile = helper.getTempDirectory()
            .resolve("recipe-bridge/src/main/java/com/example/recipe/Registry.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("a non-OCP smell (singleton) resolves to its recipe and apply_plan runs it parity-gated")
    void singletonSmell_resolvesRecipe_andAppliesParityGated() {
        ObjectNode plan = mapper.createObjectNode();
        plan.put("action", "plan");
        plan.put("kind", "singleton");                 // a SMELL kind, not a plan kind
        plan.put("filePath", registryFile.toString());
        plan.put("line", 2);                           // 0-based: `public class Registry`
        plan.put("column", 13);                        // caret on the type name

        ToolResponse pr = tool.execute(plan);
        assertTrue(pr.isSuccess(), () -> String.valueOf(pr.getError()));
        Map<String, Object> pd = data(pr);
        assertEquals("singleton", pd.get("sourceSmell"), () -> "the source smell is recorded: " + pd);
        assertEquals("inline_singleton", pd.get("recipe"), () -> "resolved to the cure recipe: " + pd);
        assertEquals("inline_singleton", pd.get("kind"));
        String planId = (String) pd.get("planId");
        assertNotNull(planId);

        ObjectNode apply = mapper.createObjectNode();
        apply.put("action", "apply_plan");
        apply.put("planId", planId);
        ToolResponse ar = tool.execute(apply);
        assertTrue(ar.isSuccess(), () -> String.valueOf(ar.getError()));
        Map<String, Object> ad = data(ar);
        assertEquals(Boolean.TRUE, ad.get("applied"),
            () -> "the recipe must apply through the parity gate (compile 0/0): " + ad);
        assertNotNull(ad.get("undoChangeId"), "a composed undo handle is returned");
    }

    @Test
    @DisplayName("a smell with no known recipe is rejected honestly, not stubbed")
    void smellWithoutRecipe_isRejected() {
        ObjectNode plan = mapper.createObjectNode();
        plan.put("action", "plan");
        plan.put("kind", "god_class");                 // a real smell, but no cure recipe
        plan.put("filePath", registryFile.toString());
        plan.put("line", 2);
        plan.put("column", 13);

        ToolResponse r = tool.execute(plan);
        assertFalse(r.isSuccess(), "no recipe -> honest rejection");
    }
}
