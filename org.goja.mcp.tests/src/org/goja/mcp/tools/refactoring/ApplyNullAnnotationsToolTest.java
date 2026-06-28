package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.ApplyNullAnnotationsTool;
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
 * Sprint 15b — apply_null_annotations add under the auto-apply contract (temp
 * copy; on-disk verification; undo round-trip; public-API guard).
 */
class ApplyNullAnnotationsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ApplyNullAnnotationsTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path targetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new ApplyNullAnnotationsTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        targetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/AddNullTarget.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test
    @DisplayName("add @Nullable to a package-private param: on disk (JSpecify default) + undo restores")
    void addAndUndo() throws Exception {
        String original = Files.readString(targetFile);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "add");
        args.put("symbol", "com.example.AddNullTarget#find");
        args.put("nullness", "nullable");
        args.put("parameter", "key");
        args.put("style", "JSPECIFY"); // pin: the fixture set now has mixed families
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> data = getData(r);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertNotNull(data.get("undoChangeId"));

        String after = Files.readString(targetFile);
        assertTrue(after.contains("@Nullable"), "annotation added:\n" + after);
        assertTrue(after.contains("import org.jspecify.annotations.Nullable;"),
            "JSpecify import added (project default):\n" + after);

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(targetFile), "undo restores byte-for-byte");
    }

    @Test
    @DisplayName("public target is refused without allowPublicApi, accepted with it")
    void publicApiGuard() throws Exception {
        ObjectNode refused = objectMapper.createObjectNode();
        refused.put("kind", "add");
        refused.put("symbol", "com.example.AddNullTarget#pub");
        refused.put("nullness", "nullable");
        refused.put("parameter", "x");
        ToolResponse r = tool.execute(refused);
        assertFalse(r.isSuccess(), "public target must be refused by default");
        assertEquals("REFACTORING_REFUSED", r.getError().getCode());

        ObjectNode allowed = refused.deepCopy();
        allowed.put("allowPublicApi", true);
        ToolResponse r2 = tool.execute(allowed);
        assertTrue(r2.isSuccess(), () -> String.valueOf(r2.getError()));
        assertTrue(Files.readString(targetFile).contains("@Nullable"));
    }

    @Test
    @DisplayName("migrate JetBrains → JSpecify: swaps usages + imports, undo restores")
    void migrateJetBrainsToJSpecify() throws Exception {
        Path migrateFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/MigrateTarget.java");
        String original = Files.readString(migrateFile);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "migrate");
        args.put("from", "JETBRAINS");
        args.put("to", "JSPECIFY");
        args.put("filePath", migrateFile.toString());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        assertEquals(Boolean.TRUE, getData(r).get("applied"));

        String after = Files.readString(migrateFile);
        assertTrue(after.contains("@Nullable"), after);
        assertTrue(after.contains("@NonNull"), "@NotNull → @NonNull:\n" + after);
        assertFalse(after.contains("@NotNull"), "old @NotNull gone:\n" + after);
        assertTrue(after.contains("import org.jspecify.annotations.NonNull;"), after);
        assertTrue(after.contains("import org.jspecify.annotations.Nullable;"), after);
        assertFalse(after.contains("org.jetbrains"), "jetbrains imports gone:\n" + after);

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) getData(r).get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(migrateFile), "undo restores byte-for-byte");
    }

    @Test
    @DisplayName("migrate refuses an ambiguous default-scoping annotation")
    void migrateRefusesAmbiguous() {
        Path ambiguous = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/AmbiguousNull.java");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "migrate");
        args.put("from", "ECLIPSE");
        args.put("to", "JSPECIFY");
        args.put("filePath", ambiguous.toString());
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "default-scoping annotation must be refused");
        assertEquals("MIGRATION_AMBIGUOUS", r.getError().getCode());
    }

    @Test
    @DisplayName("missing required params rejected")
    void missingParams() {
        assertFalse(tool.execute(objectMapper.createObjectNode().put("kind", "add")).isSuccess());
        assertFalse(tool.execute(objectMapper.createObjectNode()
            .put("kind", "add").put("symbol", "com.example.AddNullTarget#find")).isSuccess());
    }
}
