package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.ApplyRefactoringTool;
import org.goja.mcp.tools.RenameSymbolTool;
import org.goja.mcp.tools.UndoRefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for RenameSymbolTool under the Sprint 14b auto-apply
 * contract: renames are performed against a TEMP COPY of the fixture,
 * verified on disk, and reverted via the undo handle.
 */
class RenameSymbolToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private RenameSymbolTool tool;
    private ApplyRefactoringTool applyTool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private Path refactoringTargetFile;
    private Path helloWorldFile;
    private Path calculatorFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new RenameSymbolTool(() -> service, cache);
        applyTool = new ApplyRefactoringTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        projectPath = helper.getTempDirectory().resolve("simple-maven");
        refactoringTargetFile = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java");
        helloWorldFile = projectPath.resolve("src/main/java/com/example/HelloWorld.java");
        calculatorFile = projectPath.resolve("src/main/java/com/example/Calculator.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode renameArgs(Path file, int line, int column, String newName) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", line);
        args.put("column", column);
        args.put("newName", newName);
        return args;
    }

    // ========== Auto-apply contract ==========

    @Test
    @DisplayName("Sprint 14 (bugs.md #13): renaming a class also rewrites its explicit constructors — on disk")
    void renameClass_alsoRewritesConstructors() throws Exception {
        // HelloWorld.java declares two explicit constructors. Pre-Sprint-14,
        // constructor SimpleNames resolved to IMethodBinding (the
        // constructor), so the targetKey-match missed them. With auto-apply
        // the proof is the file content itself.
        ToolResponse response = tool.execute(
            renameArgs(helloWorldFile, 5, 13, "Greeting"));

        assertTrue(response.isSuccess(),
            "rename HelloWorld -> Greeting must succeed; got: " + response.getError());
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("HelloWorld", data.get("oldName"));
        assertEquals("Greeting", data.get("newName"));
        assertEquals("Class", data.get("symbolKind"));
        assertNotNull(data.get("undoChangeId"));
        assertFalse(((List<?>) data.get("filesModified")).isEmpty());

        String onDisk = Files.readString(helloWorldFile);
        assertTrue(onDisk.contains("class Greeting"), "class declaration must be renamed");
        assertTrue(onDisk.contains("public Greeting()"),
            "no-arg constructor must be renamed (bugs.md #13)");
        assertTrue(onDisk.contains("public Greeting(String"),
            "String-arg constructor must be renamed (bugs.md #13)");
        assertFalse(onDisk.contains("HelloWorld("), "no constructor keeps the old name");

        String diff = (String) data.get("diff");
        assertTrue(diff.contains("+public class Greeting") || diff.contains("Greeting"),
            "diff must document the rename:\n" + diff);
    }

    @Test
    @DisplayName("rename local variable applies on disk and undo restores the original")
    void renameLocalVariable_appliesAndUndoRestores() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response = tool.execute(
            renameArgs(refactoringTargetFile, 88, 12, "renamedLocal"));

        assertTrue(response.isSuccess(),
            "rename local must succeed; got: " + response.getError());
        Map<String, Object> data = getData(response);
        assertEquals("LocalVariable", data.get("symbolKind"));
        assertTrue((int) data.get("totalEdits") > 0);
        assertEquals(1, data.get("filesAffected"),
            "local rename must stay in one file — constructor gate regression (bugs.md #13)");

        String renamed = Files.readString(refactoringTargetFile);
        assertTrue(renamed.contains("renamedLocal"), "new name must be on disk");
        assertNotEquals(original, renamed);

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(refactoringTargetFile),
            "undo must restore the original content byte-for-byte");
    }

    @Test
    @DisplayName("auto_apply: false stages without touching disk; apply_refactoring commits")
    void stagedMode_stagesThenApplies() throws Exception {
        String original = Files.readString(calculatorFile);

        ObjectNode args = renameArgs(calculatorFile, 14, 15, "sum");
        args.put("auto_apply", false);
        ToolResponse staged = tool.execute(args);

        assertTrue(staged.isSuccess(), () -> String.valueOf(staged.getError()));
        Map<String, Object> data = getData(staged);
        assertEquals(Boolean.FALSE, data.get("applied"));
        String changeId = (String) data.get("changeId");
        assertNotNull(changeId);
        assertNull(data.get("undoChangeId"), "staged response has no undo handle yet");
        assertTrue(((String) data.get("diff")).contains("sum"),
            "diff must preview the rename");
        assertEquals(original, Files.readString(calculatorFile),
            "staging must not touch the file");

        ToolResponse applied = applyTool.execute(
            objectMapper.createObjectNode().put("changeId", changeId));
        assertTrue(applied.isSuccess(), () -> String.valueOf(applied.getError()));
        assertTrue(Files.readString(calculatorFile).contains("sum("),
            "apply_refactoring must commit the staged rename");
    }

    @Test
    @DisplayName("rename field rewrites all usages on disk")
    void renameField_rewritesAllUsages() throws Exception {
        ToolResponse response = tool.execute(
            renameArgs(refactoringTargetFile, 15, 19, "userFullName"));

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("userName", data.get("oldName"));
        assertEquals("Field", data.get("symbolKind"));
        assertTrue((int) data.get("totalEdits") >= 3, "field is used in multiple places");

        String onDisk = Files.readString(refactoringTargetFile);
        assertTrue(onDisk.contains("userFullName"), "renamed field must be on disk");
    }

    @Test
    @DisplayName("rename method returns method kind and applies")
    void renameMethod_returnsMethodKind() throws Exception {
        ToolResponse response = tool.execute(renameArgs(calculatorFile, 14, 15, "sum"));

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("add", data.get("oldName"));
        assertEquals("sum", data.get("newName"));
        assertEquals("Method", data.get("symbolKind"));
        assertTrue(Files.readString(calculatorFile).contains("sum("));
    }

    // ========== Validation Tests ==========

    @Test
    @DisplayName("rejects invalid Java identifiers, reserved words, and same name — without touching disk")
    void rejectsInvalidNames() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response1 = tool.execute(
            renameArgs(refactoringTargetFile, 88, 12, "123invalid"));
        assertFalse(response1.isSuccess());
        assertTrue(response1.getError().getMessage().contains("identifier"));

        ToolResponse response2 = tool.execute(
            renameArgs(refactoringTargetFile, 88, 12, "class"));
        assertFalse(response2.isSuccess());

        ToolResponse response3 = tool.execute(
            renameArgs(refactoringTargetFile, 88, 12, "oldName"));
        assertFalse(response3.isSuccess());
        assertTrue(response3.getError().getMessage().contains("Same as current"));

        assertEquals(original, Files.readString(refactoringTargetFile),
            "rejected renames must not modify the file");
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 10);
        args.put("column", 5);
        args.put("newName", "test");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }

    @Test
    @DisplayName("requires newName parameter")
    void requiresNewName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetFile.toString());
        args.put("line", 10);
        args.put("column", 5);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles invalid line/column gracefully")
    void handlesInvalidLineColumn() {
        ToolResponse response = tool.execute(
            renameArgs(refactoringTargetFile, -1, -1, "test"));
        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("handles no symbol at position")
    void handlesNoSymbolAtPosition() {
        ToolResponse response = tool.execute(
            renameArgs(refactoringTargetFile, 1, 0, "test"));
        assertFalse(response.isSuccess());
    }
}
