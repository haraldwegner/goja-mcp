package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.PullUpTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11 Phase E — {@code pull_up} happy/validation/conflict trio.
 * Uses the RefactoringHierarchy fixture (Base / Derived).
 */
class PullUpToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private PullUpTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new PullUpTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    @Test
    @DisplayName("happy: pull RefactoringDerived.uniqueMethod() up to RefactoringBase")
    void happy_pullUniqueMethodToBase() throws Exception {
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringHierarchy.java");
        assertTrue(Files.exists(file));

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        // Position on the `uniqueMethod` declaration line (zero-based).
        // 'void uniqueMethod() {' is on line index 26 (per fixture file layout).
        args.put("line", 26);
        args.put("column", 9);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "pulling a method that only exists on the subtype to its base must succeed; got: "
                + r.getError());

        String after = Files.readString(file);
        // The method body should now be on RefactoringBase, removed from the
        // subtype. We look for the method appearing inside class RefactoringBase.
        int baseStart = after.indexOf("class RefactoringBase");
        int derivedStart = after.indexOf("class RefactoringDerived");
        assertTrue(baseStart != -1 && derivedStart != -1, "both classes should still be present");
        String basePart = after.substring(baseStart, derivedStart);
        assertTrue(basePart.contains("uniqueMethod"),
            "uniqueMethod should now appear inside RefactoringBase after pull-up");
    }

    @Test
    @DisplayName("validation: missing filePath returns INVALID_PARAMETER")
    void validation_missingFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 0);
        args.put("column", 0);
        assertFalse(tool.execute(args).isSuccess());
    }

    @Test
    @DisplayName("safety: pulling a method whose declaring type extends only Object is rejected (no files modified)")
    void conflict_baseClassIsObject() {
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        // Position on RefactoringTarget.processData(...) — its declaring type
        // RefactoringTarget extends Object, so pull-up is rejected pre-flight.
        args.put("line", 24);
        args.put("column", 16);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "pulling up to java.lang.Object must be rejected, not silently noop");
    }
}
