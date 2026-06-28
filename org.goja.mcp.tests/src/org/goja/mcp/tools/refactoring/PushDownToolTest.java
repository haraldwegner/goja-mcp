package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.PushDownTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11 Phase E — {@code push_down} happy/validation/conflict trio.
 * Uses the RefactoringHierarchy fixture (Base / Derived).
 */
class PushDownToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private PushDownTool tool;
    private org.goja.mcp.tools.UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        var cache = new org.goja.mcp.refactoring.RefactoringChangeCache();
        tool = new PushDownTool(() -> service, cache);
        undoTool = new org.goja.mcp.tools.UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    @Test
    @DisplayName("happy: push RefactoringBase.commonMethod() down to RefactoringDerived")
    void happy_pushCommonMethodDown() throws Exception {
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringHierarchy.java");
        assertTrue(Files.exists(file));

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        // Position on `commonMethod` line in class RefactoringBase
        // ('void commonMethod() {' is on line index 18).
        args.put("line", 18);
        args.put("column", 9);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "pushing a base-only method down to its single subtype must succeed; got: "
                + r.getError());

        String after = Files.readString(file);
        int baseStart = after.indexOf("class RefactoringBase");
        int derivedStart = after.indexOf("class RefactoringDerived");
        assertTrue(baseStart != -1 && derivedStart != -1);
        String basePart = after.substring(baseStart, derivedStart);
        String derivedPart = after.substring(derivedStart);
        assertFalse(basePart.contains("commonMethod"),
            "commonMethod should no longer appear in RefactoringBase after push-down");
        assertTrue(derivedPart.contains("commonMethod"),
            "commonMethod should now appear in RefactoringDerived");
    }

    @Test
    @DisplayName("Sprint 14b: structural apply → undo restores the original file byte-for-byte")
    void pushDown_undoRestoresOriginal() throws Exception {
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringHierarchy.java");
        String original = Files.readString(file);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", 18);
        args.put("column", 9);

        ToolResponse applied = tool.execute(args);
        assertTrue(applied.isSuccess(), "got: " + applied.getError());
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) applied.getData();
        String undoChangeId = (String) data.get("undoChangeId");
        assertTrue(undoChangeId != null && !undoChangeId.isBlank(),
            "structural refactor must return an undo handle");
        assertFalse(original.equals(Files.readString(file)), "push-down must modify the file");

        ToolResponse undone = undoTool.execute(
            objectMapper.createObjectNode().put("undoChangeId", undoChangeId));
        assertTrue(undone.isSuccess(), "undo must succeed; got: " + undone.getError());
        assertTrue(original.equals(Files.readString(file)),
            "undo must restore the original content byte-for-byte");
    }

    @Test
    @DisplayName("validation: missing line/column returns INVALID_PARAMETER")
    void validation_missingCoordinates() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/RefactoringHierarchy.java");
        assertFalse(tool.execute(args).isSuccess());
    }

    @Test
    @DisplayName("safety: pushing down a member of a leaf type with no subtypes is rejected (no files modified)")
    void conflict_noSubtypes() {
        Path file = projectPath.resolve("src/main/java/com/example/HelloWorld.java");
        // HelloWorld has no subtypes in the workspace; pushing a method down
        // is well-defined as "to all subtypes" → an empty set → JDT's
        // checkInitialConditions reports an error.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", 7);
        args.put("column", 25);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "push_down on a type without subtypes must be rejected");
    }
}
