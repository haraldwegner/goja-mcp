package org.goja.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Tests for bug #5 (v1.7.1) — GojaApplication.findWorkspaceJson must
 * locate workspace.json both in the OSGi data dir directly AND one level
 * up to handle the GojaLauncher session-isolation UUID subdir.
 */
class GojaApplicationAutoLoadTest {

    @Test
    @DisplayName("findWorkspaceJson returns immediate-dir match when workspace.json sits at the OSGi data dir (direct invocation, no session subdir)")
    void findWorkspaceJson_immediateMatch_returnsThatPath(@TempDir Path dataDir) throws Exception {
        Path workspaceJson = dataDir.resolve("workspace.json");
        Files.writeString(workspaceJson, "{\"name\":\"T\",\"projects\":[],\"version\":1}");

        Path found = GojaApplication.findWorkspaceJson(dataDir);

        assertNotNull(found, "should find workspace.json directly in the data dir");
        assertEquals(workspaceJson.toRealPath(), found.toRealPath());
    }

    @Test
    @DisplayName("findWorkspaceJson walks up one level when OSGi data dir is a GojaLauncher session subdir (bug #5 fix)")
    void findWorkspaceJson_walksUpToParent_findsWorkspaceJsonAtWorkspaceRoot(@TempDir Path workspaceRoot) throws Exception {
        // Simulate the GojaLauncher layout: workspace.json at the root,
        // OSGi instance area at workspace/<uuid>/.
        Path workspaceJson = workspaceRoot.resolve("workspace.json");
        Files.writeString(workspaceJson, "{\"name\":\"T\",\"projects\":[],\"version\":1}");
        Path sessionDir = Files.createDirectory(workspaceRoot.resolve("abc12345"));

        Path found = GojaApplication.findWorkspaceJson(sessionDir);

        assertNotNull(found, "should walk up one level and find workspace.json at workspace root");
        assertEquals(workspaceJson.toRealPath(), found.toRealPath());
    }

    @Test
    @DisplayName("findWorkspaceJson prefers immediate-dir match over parent-dir match when both exist (no ambiguity)")
    void findWorkspaceJson_bothLevelsExist_prefersImmediate(@TempDir Path workspaceRoot) throws Exception {
        Files.writeString(workspaceRoot.resolve("workspace.json"),
            "{\"name\":\"PARENT\",\"projects\":[],\"version\":1}");
        Path sessionDir = Files.createDirectory(workspaceRoot.resolve("abc12345"));
        Path immediateJson = sessionDir.resolve("workspace.json");
        Files.writeString(immediateJson, "{\"name\":\"IMMEDIATE\",\"projects\":[],\"version\":1}");

        Path found = GojaApplication.findWorkspaceJson(sessionDir);

        assertNotNull(found);
        assertEquals(immediateJson.toRealPath(), found.toRealPath(),
            "immediate-dir match wins over parent-dir match");
    }

    @Test
    @DisplayName("findWorkspaceJson returns null when neither immediate dir nor parent dir has workspace.json")
    void findWorkspaceJson_neitherLevelHasFile_returnsNull(@TempDir Path workspaceRoot) throws Exception {
        Path sessionDir = Files.createDirectory(workspaceRoot.resolve("abc12345"));

        Path found = GojaApplication.findWorkspaceJson(sessionDir);

        assertNull(found);
    }

    @Test
    @DisplayName("findWorkspaceJson returns null on null dataDir input")
    void findWorkspaceJson_nullInput_returnsNull() {
        assertNull(GojaApplication.findWorkspaceJson(null));
    }

    @Test
    @DisplayName("findWorkspaceJson returns null when only a directory (not file) named 'workspace.json' exists at either level")
    void findWorkspaceJson_directoryNotFile_returnsNull(@TempDir Path workspaceRoot) throws Exception {
        // A directory named workspace.json should not satisfy isRegularFile.
        Files.createDirectory(workspaceRoot.resolve("workspace.json"));
        Path sessionDir = Files.createDirectory(workspaceRoot.resolve("abc12345"));
        Files.createDirectory(sessionDir.resolve("workspace.json"));

        Path found = GojaApplication.findWorkspaceJson(sessionDir);

        assertNull(found, "directory should not match isRegularFile probe");
    }
}
