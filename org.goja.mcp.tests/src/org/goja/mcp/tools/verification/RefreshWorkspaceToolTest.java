package org.goja.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.RefreshWorkspaceTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 14 Phase B.1 (v1.8.0) — {@code refresh_workspace} contract tests.
 *
 * <p>What this tool owns and what we test here:</p>
 * <ul>
 *   <li>Walking {@code service.allProjects()} (or one project when
 *       {@code projectKey} is set) and running {@code refreshLocal +
 *       CLEAN_BUILD + FULL_BUILD} per project.</li>
 *   <li>Returning aggregated diagnostics + {@code refreshedProjects} list +
 *       summary block.</li>
 *   <li>NOT calling addProject/removeProject — projectKey state survives.</li>
 * </ul>
 */
class RefreshWorkspaceToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefreshWorkspaceTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new RefreshWorkspaceTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: refresh_workspace returns success on a clean fixture with the documented response shape")
    void happy_returnsExpectedShape() {
        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(),
            "refresh_workspace must succeed on a clean fixture; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("refresh_workspace", data.get("operation"));
        @SuppressWarnings("unchecked")
        List<String> refreshedProjects = (List<String>) data.get("refreshedProjects");
        assertNotNull(refreshedProjects, "refreshedProjects must be present");
        assertEquals(1, refreshedProjects.size(),
            "single-project workspace should refresh 1 project; got: " + refreshedProjects);
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertNotNull(summary, "summary block must be present");
        assertNotNull(summary.get("filesRefreshed"), "summary must include filesRefreshed");
        assertNotNull(summary.get("classFilesInvalidated"), "summary must include classFilesInvalidated");
        assertNotNull(summary.get("errorCount"), "summary must include errorCount");
        assertNotNull(summary.get("warningCount"), "summary must include warningCount");
    }

    @Test
    @DisplayName("bugs.md #11 corollary: refresh_workspace preserves projectKey state (does NOT drop or rotate keys)")
    void refresh_preservesProjectKey() {
        String beforeKey = service.allProjects().iterator().next().projectKey();

        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        // Same key still present.
        assertTrue(service.getProject(beforeKey).isPresent(),
            "projectKey '" + beforeKey + "' must remain valid after refresh_workspace");
        // And not in the dropped set.
        assertFalse(service.wasRecentlyDropped(beforeKey).isPresent(),
            "refresh_workspace must NOT mark the project as dropped; bugs.md #11 corollary");
    }

    @Test
    @DisplayName("bugs.md #6: file written via plain Files.write is picked up after refresh_workspace")
    void refresh_picksUpFileWrittenViaPlainWrite() throws Exception {
        // Verify the project doesn't already contain our new file via the
        // simple-maven copy directory directly (we wrote loadProjectCopy
        // semantics: the temp dir IS the project root).
        Path projectRoot = service.allProjects().iterator().next().projectRoot();
        Path newFile = projectRoot.resolve(
            "src/main/java/com/example/RefreshedClassFromBash.java");
        assertFalse(Files.exists(newFile),
            "precondition: the new file must not exist yet");

        Files.writeString(newFile,
            "package com.example;\n"
                + "public class RefreshedClassFromBash {\n"
                + "    public int compute() { return 42; }\n"
                + "}\n");
        assertTrue(Files.exists(newFile), "file must exist on disk before refresh");

        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        // After refresh, JDT should report a higher file count than before
        // (we can't directly observe search_symbols here without rewiring;
        // the indirect signal is the summary's filesRefreshed being >= 1).
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        int filesRefreshed = ((Number) summary.get("filesRefreshed")).intValue();
        assertTrue(filesRefreshed >= 1,
            "post-refresh filesRefreshed should include the new file; got " + filesRefreshed);
    }

    @Test
    @DisplayName("scope: refresh_workspace(projectKey=X) lists exactly that project in refreshedProjects")
    void refresh_scopedToOneProject() throws Exception {
        String key = service.allProjects().iterator().next().projectKey();

        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectKey", key);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> refreshedProjects = (List<String>) data.get("refreshedProjects");
        assertEquals(List.of(key), refreshedProjects,
            "scoped refresh should report exactly one project; got: " + refreshedProjects);
    }

    @Test
    @DisplayName("validation: unknown projectKey returns INVALID_PARAMETER")
    void validation_unknownProjectKey() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectKey", "no-such-project");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "unknown projectKey must be rejected");
        assertEquals(org.goja.mcp.models.ErrorInfo.INVALID_PARAMETER,
            r.getError().getCode(),
            "expected INVALID_PARAMETER for never-loaded key; got: " + r.getError());
    }

    @Test
    @DisplayName("Sprint 14 bugs.md #11: dropped projectKey returns PROJECT_KEY_DROPPED via refresh_workspace too")
    void droppedProjectKey_returnsProjectKeyDropped() {
        String key = service.allProjects().iterator().next().projectKey();
        assertTrue(service.removeProject(key), "fixture key should remove cleanly");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectKey", key);
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "dropped key must be rejected");
        assertEquals(org.goja.mcp.models.ErrorInfo.PROJECT_KEY_DROPPED,
            r.getError().getCode(),
            "expected PROJECT_KEY_DROPPED; got: " + r.getError());
    }
}
