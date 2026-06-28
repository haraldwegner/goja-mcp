package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.MoveClassTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11 Phase E — {@code move_class} happy/validation/conflict trio.
 *
 * <p>Uses {@code loadProjectCopy} so the simple-maven fixture isn't
 * mutated by the actual file moves the refactoring performs.</p>
 */
class MoveClassToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private MoveClassTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new MoveClassTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
        // The project lives at helper.getTempDirectory()/simple-maven after
        // copyFixture; loadProjectCopy returned the same temp-rooted service.
        projectPath = service.getProjectRoot();
    }

    @Test
    @DisplayName("happy: move RefactoringTarget from com.example to com.example.target")
    void happy_moveClassToNewSubpackage() throws Exception {
        Path source = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java");
        assertTrue(Files.exists(source), "fixture must exist before refactoring");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", source.toString());
        // Position on the public class declaration line (zero-based).
        args.put("line", 12);
        args.put("column", 13);
        args.put("targetPackage", "com.example.target");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "move_class should succeed for a clean target package");

        // The original file is gone; a new file under com/example/target/ exists.
        assertFalse(Files.exists(source),
            "original file should no longer exist after the move");
        assertTrue(
            Files.exists(projectPath.resolve("src/main/java/com/example/target/RefactoringTarget.java")),
            "moved file should appear in the target package directory");
    }

    @Test
    @DisplayName("validation: missing targetPackage returns INVALID_PARAMETER")
    void validation_missingTargetPackage() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/RefactoringTarget.java");
        args.put("line", 12);
        args.put("column", 13);
        // no targetPackage
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "missing targetPackage must be rejected");
    }

    @Test
    @DisplayName("safety: moving to the source package is a no-op rejection (no files modified)")
    void conflict_movingToSourcePackageRejected() throws Exception {
        Path source = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java");
        long sizeBefore = Files.size(source);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", source.toString());
        args.put("line", 12);
        args.put("column", 13);
        args.put("targetPackage", "com.example"); // SAME as source
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(),
            "move_class to the source package should be rejected, not silently succeed");
        assertTrue(Files.exists(source), "no file should have moved");
        assertTrue(Files.size(source) == sizeBefore, "file content unchanged");
    }
}
