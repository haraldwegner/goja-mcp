package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.InsertEdit;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.ApplyRefactoringTool;
import org.goja.mcp.tools.InspectRefactoringTool;
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
 * Sprint 14b Stage A1 — apply → undo → inspect round-trips over a real
 * {@link TextFileChange} against a temp copy of the simple-maven fixture.
 */
class ApplyUndoInspectToolsTest {

    private static final String MARKER = "// goja-apply-test-marker\n";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ApplyRefactoringTool applyTool;
    private UndoRefactoringTool undoTool;
    private InspectRefactoringTool inspectTool;
    private ObjectMapper objectMapper;
    private Path targetPath;
    private String originalContent;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        applyTool = new ApplyRefactoringTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        inspectTool = new InspectRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        targetPath = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/RefactoringTarget.java");
        originalContent = Files.readString(targetPath);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode args(String key, String value) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put(key, value);
        return node;
    }

    /** Stages a marker-insertion TextFileChange and returns its changeId. */
    private String stageMarkerInsertion() {
        ICompilationUnit cu = service.getCompilationUnit(targetPath);
        assertNotNull(cu, "fixture CU must resolve: " + targetPath);
        IFile file = (IFile) cu.getResource();

        TextFileChange change = new TextFileChange("insert marker", file);
        change.setEdit(new InsertEdit(0, MARKER));

        String diff = ChangeEngine.previewDiff(change, service);
        return cache.put(RefactoringChangeCache.Kind.STAGED, change,
            "insert test marker", diff, List.of(targetPath.toString()));
    }

    @Test
    @DisplayName("apply commits the staged change, undo restores the original file")
    void applyThenUndo_roundTrips() throws Exception {
        String changeId = stageMarkerInsertion();

        ToolResponse applied = applyTool.execute(args("changeId", changeId));
        assertTrue(applied.isSuccess(), () -> String.valueOf(applied.getError()));
        Map<String, Object> appliedData = getData(applied);

        String onDisk = Files.readString(targetPath);
        assertTrue(onDisk.startsWith(MARKER), "marker must be written to disk");

        String undoChangeId = (String) appliedData.get("undoChangeId");
        assertNotNull(undoChangeId, "apply must return an undo handle");
        assertFalse(((List<?>) appliedData.get("filesModified")).isEmpty(),
            "apply must report the modified file");

        ToolResponse undone = undoTool.execute(args("undoChangeId", undoChangeId));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(originalContent, Files.readString(targetPath),
            "undo must restore the original content byte-for-byte");
    }

    @Test
    @DisplayName("inspect returns the diff without touching the file or consuming the entry")
    void inspect_isNonMutatingAndNonConsuming() throws Exception {
        String changeId = stageMarkerInsertion();

        ToolResponse inspected = inspectTool.execute(args("changeId", changeId));
        assertTrue(inspected.isSuccess(), () -> String.valueOf(inspected.getError()));
        Map<String, Object> data = getData(inspected);

        assertEquals("STAGED", data.get("kind"));
        String diff = (String) data.get("diff");
        assertTrue(diff.contains("+" + MARKER.stripTrailing()),
            "diff must show the pending insertion:\n" + diff);
        assertTrue(diff.contains("@@"), diff);

        assertEquals(originalContent, Files.readString(targetPath),
            "inspect must not modify the file");

        // Still applicable afterwards — inspect did not consume the entry.
        ToolResponse applied = applyTool.execute(args("changeId", changeId));
        assertTrue(applied.isSuccess(), () -> String.valueOf(applied.getError()));
        assertTrue(Files.readString(targetPath).startsWith(MARKER));
    }

    @Test
    @DisplayName("unknown ids return INVALID_PARAMETER, applied ids are consumed")
    void unknownAndConsumedIds_errorShape() {
        ToolResponse unknown = applyTool.execute(args("changeId", "no-such-id"));
        assertFalse(unknown.isSuccess());
        assertEquals("INVALID_PARAMETER", unknown.getError().getCode());

        ToolResponse unknownUndo = undoTool.execute(args("undoChangeId", "no-such-id"));
        assertFalse(unknownUndo.isSuccess());
        assertEquals("INVALID_PARAMETER", unknownUndo.getError().getCode());

        String changeId = stageMarkerInsertion();
        assertTrue(applyTool.execute(args("changeId", changeId)).isSuccess());

        ToolResponse second = applyTool.execute(args("changeId", changeId));
        assertFalse(second.isSuccess(), "apply is one-shot — second apply must fail");
        assertEquals("INVALID_PARAMETER", second.getError().getCode());
    }

    @Test
    @DisplayName("missing required parameter returns INVALID_PARAMETER")
    void missingParameter_errorShape() {
        ToolResponse response = applyTool.execute(objectMapper.createObjectNode());
        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }
}
