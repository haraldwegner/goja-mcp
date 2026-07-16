package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 25 (spec D1a item 5) — PARITY BATTERY for the {@code apply_cleanup}
 * migration onto JDT's fix cores ({@code VariableDeclarationFixCore} for
 * add_final, {@code RedundantModifiersCleanUp} for redundant_modifiers) inside
 * the unchanged SourceScan sweep shell. Goldens archived from the OLD
 * hand-rolled rewrites; see {@code parity/apply-cleanup/DIVERGENCES.md}.
 */
class ApplyCleanupParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> HEADER = List.of("kind", "editCount", "filesChanged");

    @Test
    @DisplayName("parity: add_final over CleanupTargets.java")
    void parityAddFinal() throws Exception {
        runParity("add-final-cleanup-targets", "add_final");
    }

    @Test
    @DisplayName("parity: redundant_modifiers over CleanupTargets.java")
    void parityRedundantModifiers() throws Exception {
        runParity("redundant-modifiers-cleanup-targets", "redundant_modifiers");
    }

    private void runParity(String id, String kind) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectPath = helper.getTempDirectory().resolve("simple-maven");
        Path file = projectPath.resolve("src/main/java/com/example/CleanupTargets.java");

        RefactoringChangeCache cache = new RefactoringChangeCache();
        ApplyCleanupTool tool = new ApplyCleanupTool(() -> service, cache);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", kind);
        args.put("filePath", file.toString());
        args.put("auto_apply", false); // stage: compare the planned change

        ToolResponse response = tool.execute(args);
        ParitySupport.assertParity("apply-cleanup", id, response, projectPath,
            helper.getTempDirectory(), HEADER);
    }
}
