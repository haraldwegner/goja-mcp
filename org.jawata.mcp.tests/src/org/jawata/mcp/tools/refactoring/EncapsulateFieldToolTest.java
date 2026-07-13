package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.EncapsulateFieldTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11 Phase E — {@code encapsulate_field} happy/validation/conflict trio.
 */
class EncapsulateFieldToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private EncapsulateFieldTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new EncapsulateFieldTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    @Test
    @DisplayName("happy: encapsulate RefactoringDerived.fieldToEncapsulate generates accessors")
    void happy_encapsulatePublicField() throws Exception {
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringHierarchy.java");
        assertTrue(Files.exists(file));

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        // 'public int fieldToEncapsulate;' is on line index 24.
        args.put("line", 24);
        args.put("column", 16);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "encapsulating a public field that has no existing accessors must succeed; got: "
                + r.getError());

        String after = Files.readString(file);
        assertTrue(after.contains("getFieldToEncapsulate"),
            "getter must be generated; file content was:\n" + after);
        assertTrue(after.contains("setFieldToEncapsulate"),
            "setter must be generated; file content was:\n" + after);
        assertTrue(after.contains("private int fieldToEncapsulate"),
            "field visibility should default to private after encapsulation");
    }

    @Test
    @DisplayName("validation: position not on a field returns INVALID_PARAMETER")
    void validation_positionNotOnField() {
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringHierarchy.java");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        // Position on the package declaration — definitely not a field.
        args.put("line", 0);
        args.put("column", 0);
        assertFalse(tool.execute(args).isSuccess());
    }

    @Test
    @DisplayName("safety: invalid newFieldVisibility returns INVALID_PARAMETER")
    void conflict_invalidVisibility() {
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringHierarchy.java");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", 22);
        args.put("column", 16);
        args.put("newFieldVisibility", "BOGUS");
        assertFalse(tool.execute(args).isSuccess());
    }
}
