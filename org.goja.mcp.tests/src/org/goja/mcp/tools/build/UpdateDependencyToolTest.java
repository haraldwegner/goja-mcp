package org.goja.mcp.tools.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ErrorInfo;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.build.AddDependencyTool;
import org.goja.mcp.tools.build.UpdateDependencyTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 13 (v1.7.0) — {@code update_dependency} contract tests.
 */
class UpdateDependencyToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private AddDependencyTool addTool;
    private UpdateDependencyTool updateTool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        addTool = new AddDependencyTool(() -> service);
        updateTool = new UpdateDependencyTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: update existing dependency version reflects in pom")
    void happy_updateMavenDependencyVersion_pomReflects() throws Exception {
        // Seed an existing dep first.
        ObjectNode addArgs = objectMapper.createObjectNode();
        addArgs.put("groupId", "org.apache.commons");
        addArgs.put("artifactId", "commons-lang3");
        addArgs.put("version", "3.14.0");
        ToolResponse addResult = addTool.execute(addArgs);
        assertTrue(addResult.isSuccess(), "seed add must succeed; got: " + addResult.getError());

        // Now bump the version.
        ObjectNode updateArgs = objectMapper.createObjectNode();
        updateArgs.put("groupId", "org.apache.commons");
        updateArgs.put("artifactId", "commons-lang3");
        updateArgs.put("newVersion", "3.15.0");

        ToolResponse r = updateTool.execute(updateArgs);
        assertTrue(r.isSuccess(), "update must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        Map<String, String> updated = (Map<String, String>) data.get("updated");
        assertEquals("3.14.0", updated.get("oldVersion"));
        assertEquals("3.15.0", updated.get("newVersion"));

        Path pom = service.allProjects().iterator().next().projectRoot().resolve("pom.xml");
        String content = Files.readString(pom, StandardCharsets.UTF_8);
        assertTrue(content.contains("<version>3.15.0</version>"),
            "pom must reflect the new version; got:\n" + content);
        assertFalse(content.contains("<version>3.14.0</version>"),
            "pom must NOT still contain the old version; got:\n" + content);
    }

    @Test
    @DisplayName("validation: unknown dependency returns INVALID_PARAMETER")
    void validation_unknownDependency_returnsInvalidParameter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("groupId", "com.example.nonexistent");
        args.put("artifactId", "ghost-lib");
        args.put("newVersion", "1.0.0");

        ToolResponse r = updateTool.execute(args);
        assertFalse(r.isSuccess(), "unknown dep must be rejected");
        ErrorInfo err = r.getError();
        assertNotNull(err);
        assertEquals(ErrorInfo.INVALID_PARAMETER, err.getCode(),
            "expected INVALID_PARAMETER; got: " + err);
    }
}
