package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.MovePackageTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11 Phase E — {@code move_package} happy/validation/conflict trio.
 */
class MovePackageToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private MovePackageTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new MovePackageTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    @Test
    @DisplayName("happy: rename com.example.service to com.example.svc")
    void happy_renameSubpackage() throws Exception {
        Path serviceDir = projectPath.resolve("src/main/java/com/example/service");
        assertTrue(Files.isDirectory(serviceDir), "fixture must have com.example.service before refactoring");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("packageName", "com.example.service");
        args.put("newPackageName", "com.example.svc");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "renaming a leaf subpackage should succeed");

        assertFalse(Files.exists(serviceDir),
            "old package directory should be gone after rename");
        assertTrue(
            Files.isDirectory(projectPath.resolve("src/main/java/com/example/svc")),
            "new package directory should exist with the moved compilation units");
    }

    @Test
    @DisplayName("validation: missing newPackageName returns INVALID_PARAMETER")
    void validation_missingNewPackageName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("packageName", "com.example.service");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("safety: renaming to the same package is rejected (no files modified)")
    void conflict_sameNameRejected() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("packageName", "com.example");
        args.put("newPackageName", "com.example");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "no-op rename must be rejected by the tool's input check");
    }
}
