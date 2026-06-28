package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.ExtractMethodTool;
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
 * Integration tests for ExtractMethodTool under the Sprint 14b auto-apply
 * contract (temp fixture copy; on-disk verification; undo round-trip).
 */
class ExtractMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ExtractMethodTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path refactoringTargetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new ExtractMethodTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        refactoringTargetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/RefactoringTarget.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode extractionArgs(String methodName) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetFile.toString());
        args.put("startLine", 44);  // Start of sum calculation loop
        args.put("startColumn", 8);
        args.put("endLine", 47);    // End of loop
        args.put("endColumn", 9);
        if (methodName != null) {
            args.put("methodName", methodName);
        }
        return args;
    }

    // ========== Auto-apply contract ==========

    @Test
    @DisplayName("extracts code block to method on disk; undo restores the original")
    void extractsCodeBlock_appliesAndUndoRestores() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response = tool.execute(extractionArgs("calculateSum"));

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("calculateSum", data.get("methodName"));
        assertNotNull(data.get("parameters"));
        assertNotNull(data.get("returnType"));
        assertNotNull(data.get("methodCall"));
        assertNotNull(data.get("undoChangeId"));

        String onDisk = Files.readString(refactoringTargetFile);
        assertTrue(onDisk.contains("calculateSum("),
            "new method and its call must be on disk");
        assertNotEquals(original, onDisk);

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(refactoringTargetFile));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires methodName parameter")
    void requiresMethodName() {
        ToolResponse response = tool.execute(extractionArgs(null));
        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("startLine", 44);
        args.put("startColumn", 8);
        args.put("endLine", 47);
        args.put("endColumn", 9);
        args.put("methodName", "calculateSum");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid method names and reserved words — without touching disk")
    void rejectsInvalidMethodNames() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        assertFalse(tool.execute(extractionArgs("123invalid")).isSuccess());
        assertFalse(tool.execute(extractionArgs("while")).isSuccess());

        assertEquals(original, Files.readString(refactoringTargetFile));
    }

    @Test
    @DisplayName("handles invalid range gracefully")
    void handlesInvalidRange() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetFile.toString());
        args.put("startLine", -1);
        args.put("startColumn", -1);
        args.put("endLine", -1);
        args.put("endColumn", -1);
        args.put("methodName", "calculateSum");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
