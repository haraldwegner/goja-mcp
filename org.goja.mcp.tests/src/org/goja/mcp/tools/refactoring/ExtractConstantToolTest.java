package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.ExtractConstantTool;
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
 * Integration tests for ExtractConstantTool under the Sprint 14b auto-apply
 * contract (temp fixture copy; on-disk verification; undo round-trip).
 */
class ExtractConstantToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ExtractConstantTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path refactoringTargetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new ExtractConstantTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        refactoringTargetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/RefactoringTarget.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode extractionArgs(String constantName) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetFile.toString());
        args.put("startLine", 35);  // "PREFIX_" string literal
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        if (constantName != null) {
            args.put("constantName", constantName);
        }
        return args;
    }

    // ========== Auto-apply contract ==========

    @Test
    @DisplayName("extracts expression to static final constant on disk; undo restores")
    void extractsConstant_appliesAndUndoRestores() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response = tool.execute(extractionArgs("DEFAULT_PREFIX"));

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("DEFAULT_PREFIX", data.get("constantName"));
        assertNotNull(data.get("constantType"));
        assertNotNull(data.get("undoChangeId"));

        String onDisk = Files.readString(refactoringTargetFile);
        assertTrue(onDisk.contains("static final"),
            "constant declaration must be on disk");
        assertTrue(onDisk.contains("DEFAULT_PREFIX ="),
            "constant must be declared with the requested name");
        assertNotEquals(original, onDisk);

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(refactoringTargetFile));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires constantName parameter")
    void requiresConstantName() {
        ToolResponse response = tool.execute(extractionArgs(null));
        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("startLine", 35);
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        args.put("constantName", "DEFAULT_PREFIX");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid constant names — without touching disk")
    void rejectsInvalidConstantNames() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response = tool.execute(extractionArgs("123INVALID"));

        assertFalse(response.isSuccess());
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
        args.put("constantName", "DEFAULT_PREFIX");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
