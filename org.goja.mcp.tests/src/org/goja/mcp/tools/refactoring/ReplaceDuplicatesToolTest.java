package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.ApplyRefactoringTool;
import org.goja.mcp.tools.FindDuplicateCodeTool;
import org.goja.mcp.tools.ReplaceDuplicatesTool;
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
 * Sprint 14b Stage A5 — the full find_duplicate_code → replace_duplicates
 * loop against the Animal fixture: Animal.speak and Animal.move share one
 * normalized shape (same-type replacement), and shape-identical one-liners
 * in other fixture types join the group as cross-type skips.
 */
class ReplaceDuplicatesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private FindDuplicateCodeTool findTool;
    private ReplaceDuplicatesTool replaceTool;
    private ApplyRefactoringTool applyTool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path animalFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        findTool = new FindDuplicateCodeTool(() -> service);
        replaceTool = new ReplaceDuplicatesTool(() -> service, cache);
        applyTool = new ApplyRefactoringTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        animalFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/Animal.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    /** Detects with low minTokens and returns the groupId of the speak/move group. */
    @SuppressWarnings("unchecked")
    private String detectSpeakMoveGroupId() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("minTokens", 5);
        ToolResponse found = findTool.execute(args);
        assertTrue(found.isSuccess(), () -> String.valueOf(found.getError()));

        List<Map<String, Object>> groups =
            (List<Map<String, Object>>) getData(found).get("groups");
        for (Map<String, Object> group : groups) {
            List<Map<String, Object>> instances =
                (List<Map<String, Object>>) group.get("instances");
            boolean hasSpeak = instances.stream().anyMatch(i -> "speak".equals(i.get("methodName")));
            boolean hasMove = instances.stream().anyMatch(i -> "move".equals(i.get("methodName")));
            if (hasSpeak && hasMove) {
                String groupId = (String) group.get("groupId");
                assertNotNull(groupId, "groups must carry a stable groupId");
                return groupId;
            }
        }
        return fail("speak/move clone group not found in: " + groups);
    }

    private ObjectNode replaceArgs(String groupId) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("cloneGroupId", groupId);
        args.put("canonicalMethodName", "speak");
        args.put("minTokens", 5);
        return args;
    }

    // ========== The full loop ==========

    @Test
    @DisplayName("detect → replace: same-type clone delegates, cross-type clone skipped; undo restores")
    void fullLoop_replacesSameTypeSkipsCrossType_undoRestores() throws Exception {
        String original = Files.readString(animalFile);
        String groupId = detectSpeakMoveGroupId();

        ToolResponse response = replaceTool.execute(replaceArgs(groupId));

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals(1, data.get("replacedCount"), "exactly Animal.move is same-type");
        assertNotNull(data.get("undoChangeId"));

        // The group also catches shape-identical one-liners in OTHER types
        // (e.g. UnusedCode.anotherPublicMethod) — those must be skipped, not
        // rewritten. (Dog.speak is NOT in the group: its @Override annotation
        // changes the normalized shape.)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) data.get("skipped");
        assertTrue(skipped.stream().anyMatch(
                s -> String.valueOf(s.get("reason")).contains("different type")),
            "cross-type clones must be skipped with a reason; got: " + skipped);

        String onDisk = Files.readString(animalFile);
        assertTrue(onDisk.contains("speak();"),
            "move's body must delegate to speak():\n" + onDisk);
        assertFalse(onDisk.contains("Animal moves"),
            "move's duplicated body must be gone:\n" + onDisk);
        assertTrue(onDisk.contains("Dog barks"), "Dog.speak must be untouched");

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(animalFile),
            "undo must restore the original content byte-for-byte");
    }

    @Test
    @DisplayName("auto_apply: false stages the composite; apply_refactoring commits it")
    void stagedMode_stagesThenApplies() throws Exception {
        String original = Files.readString(animalFile);
        String groupId = detectSpeakMoveGroupId();

        ObjectNode args = replaceArgs(groupId);
        args.put("auto_apply", false);
        ToolResponse staged = replaceTool.execute(args);

        assertTrue(staged.isSuccess(), () -> String.valueOf(staged.getError()));
        Map<String, Object> data = getData(staged);
        assertEquals(Boolean.FALSE, data.get("applied"));
        String changeId = (String) data.get("changeId");
        assertNotNull(changeId);
        assertTrue(((String) data.get("diff")).contains("speak();"),
            "diff must preview the delegation");
        assertEquals(original, Files.readString(animalFile), "staging must not touch disk");

        ToolResponse applied = applyTool.execute(
            objectMapper.createObjectNode().put("changeId", changeId));
        assertTrue(applied.isSuccess(), () -> String.valueOf(applied.getError()));
        assertTrue(Files.readString(animalFile).contains("speak();"));
    }

    // ========== Error handling ==========

    @Test
    @DisplayName("unknown groupId returns INVALID_PARAMETER with re-detection hint")
    void unknownGroupId_errorShape() {
        ToolResponse response = replaceTool.execute(replaceArgs("deadbeef0000"));

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
        assertTrue(response.getError().getMessage().contains("find_duplicate_code"),
            "error must point back to detection");
    }

    @Test
    @DisplayName("requires cloneGroupId parameter")
    void requiresCloneGroupId() {
        ToolResponse response = replaceTool.execute(objectMapper.createObjectNode());
        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }
}
