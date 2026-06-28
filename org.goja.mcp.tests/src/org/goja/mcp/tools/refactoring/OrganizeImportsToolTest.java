package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.OrganizeImportsTool;
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
 * Integration tests for OrganizeImportsTool under the Sprint 14b auto-apply
 * contract (temp fixture copy; on-disk verification; undo round-trip).
 * A file whose imports are already organized short-circuits with
 * {@code hasChanges: false} and never builds a change.
 */
class OrganizeImportsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private OrganizeImportsTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path refactoringTargetFile;
    private Path calculatorFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new OrganizeImportsTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        refactoringTargetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/RefactoringTarget.java");
        calculatorFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/Calculator.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode args(Path file) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        return args;
    }

    // ========== Auto-apply contract ==========

    @Test
    @DisplayName("organizes imports: applies on disk when changes exist, no-ops otherwise")
    void organizeImports_appliesOrShortCircuits() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response = tool.execute(args(refactoringTargetFile));

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("filePath"));
        assertNotNull(data.get("totalImports"));
        assertNotNull(data.get("hasChanges"));

        if ((boolean) data.get("hasChanges")) {
            // Applied: organized block on disk, undo restores.
            assertEquals(Boolean.TRUE, data.get("applied"));
            assertNotNull(data.get("undoChangeId"));
            String organizedBlock = (String) data.get("organizedImportBlock");
            assertNotNull(organizedBlock);
            if (organizedBlock.contains("java.") && organizedBlock.contains("com.")) {
                assertTrue(organizedBlock.indexOf("java.") < organizedBlock.indexOf("com."));
            }
            assertNotEquals(original, Files.readString(refactoringTargetFile));

            ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
                .put("undoChangeId", (String) data.get("undoChangeId")));
            assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
            assertEquals(original, Files.readString(refactoringTargetFile));
        } else {
            // No-op: nothing applied, file untouched.
            assertEquals(Boolean.FALSE, data.get("applied"));
            assertEquals(original, Files.readString(refactoringTargetFile));
        }
    }

    // ========== File with No Imports Test ==========

    @Test
    @DisplayName("handles file with no imports correctly — short-circuits without change")
    void handlesFileWithNoImports() throws Exception {
        String original = Files.readString(calculatorFile);

        ToolResponse response = tool.execute(args(calculatorFile));

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals(0, data.get("totalImports"));
        assertEquals(Boolean.FALSE, data.get("applied"));
        assertEquals(original, Files.readString(calculatorFile));
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects empty filePath and non-existent file")
    void rejectsInvalidFilePaths() {
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("filePath", "");

        assertFalse(tool.execute(args1).isSuccess());

        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", "non/existent/File.java");

        assertFalse(tool.execute(args2).isSuccess());
    }
}
