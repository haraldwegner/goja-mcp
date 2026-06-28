package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.ExtractVariableTool;
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
 * Integration tests for ExtractVariableTool under the Sprint 14b auto-apply
 * contract (temp fixture copy; on-disk verification; undo round-trip).
 */
class ExtractVariableToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ExtractVariableTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path refactoringTargetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new ExtractVariableTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        refactoringTargetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/RefactoringTarget.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode extractionArgs() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetFile.toString());
        args.put("startLine", 31);  // input.length() * 2 + 10
        args.put("startColumn", 21);
        args.put("endLine", 31);
        args.put("endColumn", 44);
        return args;
    }

    // ========== Auto-apply contract ==========

    @Test
    @DisplayName("extracts expression to variable on disk; undo restores the original")
    void extractsExpression_appliesAndUndoRestores() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ObjectNode args = extractionArgs();
        args.put("variableName", "calculated");
        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("calculated", data.get("variableName"));
        assertNotNull(data.get("variableType"));
        assertNotNull(data.get("undoChangeId"));

        String onDisk = Files.readString(refactoringTargetFile);
        assertTrue(onDisk.contains("calculated ="),
            "declaration must be on disk:\n" + data.get("declaration"));
        assertNotEquals(original, onDisk);

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(refactoringTargetFile));
    }

    // ========== Optional Parameter Tests ==========

    @Test
    @DisplayName("auto-suggests variable name when not provided")
    void autoSuggestsVariableName() {
        ToolResponse response = tool.execute(extractionArgs());

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("variableName"));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("startLine", 31);
        args.put("startColumn", 21);
        args.put("endLine", 31);
        args.put("endColumn", 44);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires start and end position parameters")
    void requiresPositionParameters() {
        // Missing start position
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", refactoringTargetFile.toString());
        args1.put("endLine", 31);
        args1.put("endColumn", 44);

        ToolResponse response1 = tool.execute(args1);
        assertFalse(response1.isSuccess());

        // Missing end position
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", refactoringTargetFile.toString());
        args2.put("startLine", 31);
        args2.put("startColumn", 21);

        ToolResponse response2 = tool.execute(args2);
        assertFalse(response2.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid variable names and reserved words — without touching disk")
    void rejectsInvalidVariableNames() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ObjectNode args1 = extractionArgs();
        args1.put("variableName", "123invalid");
        assertFalse(tool.execute(args1).isSuccess());

        ObjectNode args2 = extractionArgs();
        args2.put("variableName", "for");
        assertFalse(tool.execute(args2).isSuccess());

        assertEquals(original, Files.readString(refactoringTargetFile),
            "rejected extractions must not modify the file");
    }
}
