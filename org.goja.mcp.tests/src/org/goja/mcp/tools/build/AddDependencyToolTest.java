package org.goja.mcp.tools.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ErrorInfo;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.build.AddDependencyTool;
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
 * Sprint 13 (v1.7.0) — {@code add_dependency} contract tests.
 */
class AddDependencyToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private AddDependencyTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new AddDependencyTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: add compile dependency appears in pom.xml")
    void happy_addCompileDependency_appearsInPom() throws Exception {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("groupId", "org.apache.commons");
        args.put("artifactId", "commons-lang3");
        args.put("version", "3.14.0");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("add_dependency", data.get("operation"));
        String pomPath = (String) data.get("pomPath");
        assertNotNull(pomPath, "pomPath must be populated");

        // Read directly from the fixture's on-disk pom (loaded.projectRoot()
        // gives the original temp path; IProject.getLocation() is Eclipse's
        // workspace metadata folder — the wrong path).
        Path pom = service.allProjects().iterator().next().projectRoot().resolve("pom.xml");
        String content = Files.readString(pom, StandardCharsets.UTF_8);
        assertTrue(content.contains("<groupId>org.apache.commons</groupId>"),
            "groupId must be present in pom; got:\n" + content);
        assertTrue(content.contains("<artifactId>commons-lang3</artifactId>"),
            "artifactId must be present; got:\n" + content);
        assertTrue(content.contains("<version>3.14.0</version>"),
            "version must be present; got:\n" + content);
    }

    @Test
    @DisplayName("validation: duplicate dependency returns INVALID_PARAMETER")
    void validation_duplicateDependency_returnsInvalidParameter() throws Exception {
        // First add — should succeed.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("groupId", "org.apache.commons");
        args.put("artifactId", "commons-lang3");
        args.put("version", "3.14.0");
        ToolResponse first = tool.execute(args);
        assertTrue(first.isSuccess(), "first add must succeed; got: " + first.getError());

        // Second add of the same coords — must be rejected.
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "duplicate add must be rejected");
        ErrorInfo err = r.getError();
        assertNotNull(err);
        assertEquals(ErrorInfo.INVALID_PARAMETER, err.getCode(),
            "expected INVALID_PARAMETER; got: " + err);
        assertTrue(err.getMessage().toLowerCase().contains("already")
                || err.getMessage().toLowerCase().contains("update_dependency"),
            "error must hint at update_dependency; got: " + err.getMessage());
    }
}
