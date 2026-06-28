package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.InlineVariableTool;
import org.goja.mcp.tools.UndoRefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for InlineVariableTool under the Sprint 14b auto-apply
 * contract (temp fixture copy; on-disk verification; undo round-trip).
 */
class InlineVariableToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private InlineVariableTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path refactoringTargetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new InlineVariableTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        refactoringTargetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/RefactoringTarget.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode args(int line, int column) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetFile.toString());
        args.put("line", line);
        args.put("column", column);
        return args;
    }

    // ========== Auto-apply contract ==========

    @Test
    @DisplayName("inlines variable on disk; undo restores the original")
    void inlinesVariable_appliesAndUndoRestores() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response = tool.execute(args(26, 15)); // String trimmed = input.trim();

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("trimmed", data.get("variableName"));
        assertNotNull(data.get("initializerText"));
        assertTrue((int) data.get("usageCount") > 0);
        assertNotNull(data.get("undoChangeId"));

        String onDisk = Files.readString(refactoringTargetFile);
        assertFalse(onDisk.contains("String trimmed ="),
            "declaration must be removed from disk");
        assertNotEquals(original, onDisk);

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(refactoringTargetFile));
    }

    // ========== Safety Check Tests ==========

    @Test
    @DisplayName("refuses to inline modified variable — without touching disk")
    void refusesToInlineModifiedVariable() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response = tool.execute(args(121, 12)); // value reassigned later

        assertFalse(response.isSuccess());
        assertEquals(original, Files.readString(refactoringTargetFile));
    }

    @Test
    @DisplayName("refuses variable without initializer")
    void refusesVariableWithoutInitializer() {
        ToolResponse response = tool.execute(args(130, 12)); // int value; (no initializer)
        assertFalse(response.isSuccess());
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 26);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires line and column parameters")
    void requiresLineAndColumn() {
        // Missing line
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetFile.toString());
        args1.put("column", 15);

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());

        // Missing column
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetFile.toString());
        args2.put("line", 26);

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles non-variable position gracefully")
    void handlesNotAVariable() {
        ToolResponse response = tool.execute(args(24, 16)); // Method declaration
        assertFalse(response.isSuccess());
    }
}
