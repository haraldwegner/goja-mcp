package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.ApplyCleanupTool;
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
 * Sprint 15 — apply_cleanup under the auto-apply contract (temp fixture copy;
 * on-disk verification; undo round-trip). Drives the simple-maven fixture's
 * CleanupTargets, which carries both kinds plus a reassigned-local negative.
 */
class ApplyCleanupToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ApplyCleanupTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path targetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new ApplyCleanupTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        targetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/CleanupTargets.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode args(String kind) {
        ObjectNode a = objectMapper.createObjectNode();
        a.put("kind", kind);
        a.put("filePath", targetFile.toString());
        return a;
    }

    @Test
    @DisplayName("add_final marks unwritten params/locals final, leaves reassigned locals alone, undo restores")
    void addFinal() throws Exception {
        String original = Files.readString(targetFile);

        ToolResponse r = tool.execute(args("add_final"));
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> data = getData(r);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertNotNull(data.get("undoChangeId"));

        String after = Files.readString(targetFile);
        assertTrue(after.contains("final int value"), "param value must be final:\n" + after);
        assertTrue(after.contains("final int doubled"), "local doubled must be final:\n" + after);
        // Negative: the reassigned accumulator must remain non-final.
        assertTrue(after.contains("int acc = 0;"), "acc must stay non-final:\n" + after);
        assertFalse(after.contains("final int acc"), "acc reassigned in loop — must NOT be final");

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(targetFile));
    }

    @Test
    @DisplayName("redundant_modifiers strips implicit interface-member modifiers, undo restores")
    void redundantModifiers() throws Exception {
        String original = Files.readString(targetFile);

        ToolResponse r = tool.execute(args("redundant_modifiers"));
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> data = getData(r);
        assertEquals(Boolean.TRUE, data.get("applied"));

        String after = Files.readString(targetFile);
        assertTrue(after.contains("int LIMIT = 10;"), "field keeps its declaration:\n" + after);
        assertFalse(after.contains("public static final int LIMIT"), "field modifiers must be stripped");
        assertTrue(after.contains("void run();"), "method keeps its declaration:\n" + after);
        assertFalse(after.contains("public abstract void run"), "method modifiers must be stripped");

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(targetFile));
    }

    @Test
    @DisplayName("unknown kind is rejected")
    void unknownKind() {
        ObjectNode a = objectMapper.createObjectNode();
        a.put("kind", "no_such_kind");
        ToolResponse r = tool.execute(a);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }

    @Test
    @DisplayName("missing kind is rejected")
    void missingKind() {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }
}
