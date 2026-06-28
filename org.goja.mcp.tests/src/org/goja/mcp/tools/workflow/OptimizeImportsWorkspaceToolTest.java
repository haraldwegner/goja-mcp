package org.goja.mcp.tools.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.workflow.OptimizeImportsWorkspaceTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 13 (v1.7.0) — {@code optimize_imports_workspace} contract tests.
 *
 * <p>Drops a fixture file with intentionally unused imports and asserts
 * the workspace fan-out removes them. Idempotence path: a second run on
 * the cleaned workspace removes nothing.</p>
 */
class OptimizeImportsWorkspaceToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private OptimizeImportsWorkspaceTool tool;
    private ObjectMapper objectMapper;
    private Path bloatedFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new OptimizeImportsWorkspaceTool(() -> service);
        objectMapper = new ObjectMapper();

        // Mutate an existing fixture file to add bogus imports. Editing in
        // place avoids the linked-folder new-file refresh quirk Sprint 12
        // hit with compile_workspace.
        Path projectRoot = service.allProjects().iterator().next().projectRoot();
        bloatedFile = projectRoot.resolve("src/main/java/com/example/HelloWorld.java");
        String original = Files.readString(bloatedFile, StandardCharsets.UTF_8);
        // Inject 3 unused imports right after the package declaration.
        String mutated = original.replace("package com.example;",
            "package com.example;\n\n"
                + "import java.util.Set;\n"
                + "import java.io.IOException;\n"
                + "import java.util.HashMap;");
        Files.writeString(bloatedFile, mutated, StandardCharsets.UTF_8);
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());
    }

    @Test
    @DisplayName("happy: workspace scope removes unused imports across project")
    void happy_workspaceScope_removesUnusedImports() throws Exception {
        ToolResponse r = tool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("workspace", data.get("scope"));
        assertTrue(((Number) data.get("filesProcessed")).intValue() >= 1,
            "expected at least 1 file processed; got: " + data);

        // The 3 unused imports we injected must be removed from HelloWorld.
        String content = Files.readString(bloatedFile, StandardCharsets.UTF_8);
        String diag = "data=" + data + "\n--- file (" + content.length() + " chars) ---\n"
            + content + "\n--- end ---";
        assertTrue(!content.contains("import java.util.Set;"),
            "unused Set must be gone; " + diag);
        assertTrue(!content.contains("import java.util.HashMap;"),
            "unused HashMap must be gone; " + diag);
        assertTrue(!content.contains("import java.io.IOException;"),
            "unused IOException must be gone; " + diag);
    }

    @Test
    @DisplayName("idempotent: second run on cleaned workspace removes nothing")
    void idempotent_secondRun_removesNothing() {
        // First pass — mutates files.
        ToolResponse first = tool.execute(objectMapper.createObjectNode());
        assertTrue(first.isSuccess());

        // Second pass — should report zero removals.
        ToolResponse second = tool.execute(objectMapper.createObjectNode());
        assertTrue(second.isSuccess(), "second run must succeed; got: " + second.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) second.getData();
        assertNotNull(data);
        assertEquals(0, ((Number) data.get("importsRemoved")).intValue(),
            "second run must not remove any imports; data=" + data);
    }
}
