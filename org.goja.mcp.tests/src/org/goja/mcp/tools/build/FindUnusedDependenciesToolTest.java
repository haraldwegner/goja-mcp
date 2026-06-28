package org.goja.mcp.tools.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.build.AddDependencyTool;
import org.goja.mcp.tools.build.FindUnusedDependenciesTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 13 (v1.7.0) — {@code find_unused_dependencies} contract tests.
 *
 * <p>Heuristic-based: declared deps whose groupId / parent-groupId /
 * artifactId-as-dotted-suffix is referenced by any source import are
 * "used"; others are "unused". Tests exercise both branches.</p>
 */
class FindUnusedDependenciesToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private FindUnusedDependenciesTool findTool;
    private AddDependencyTool addTool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        findTool = new FindUnusedDependenciesTool(() -> service);
        addTool = new AddDependencyTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: unused dep reported, used dep not reported")
    void happy_unusedDepReported_passingDepNotReported() throws Exception {
        Path projectRoot = service.allProjects().iterator().next().projectRoot();

        // Drop a tiny source file that imports something matching commons-lang3,
        // so commons-lang3 will be flagged as USED.
        Path javaFile = projectRoot.resolve("src/main/java/com/example/UsesCommons.java");
        Files.writeString(javaFile, """
            package com.example;
            import org.apache.commons.lang3.StringUtils;
            public class UsesCommons {
                public boolean blank() { return StringUtils.isBlank(""); }
            }
            """, StandardCharsets.UTF_8);
        // Refresh JDT so the new file is in the model before find_unused walks
        // package fragments.
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());

        // Add the matching dep — should be detected as used.
        ObjectNode addUsed = objectMapper.createObjectNode();
        addUsed.put("groupId", "org.apache.commons");
        addUsed.put("artifactId", "commons-lang3");
        addUsed.put("version", "3.14.0");
        ToolResponse a1 = addTool.execute(addUsed);
        assertTrue(a1.isSuccess(), "add commons-lang3 must succeed; got: " + a1.getError());

        // Add an obviously-unused dep — no source references it.
        ObjectNode addUnused = objectMapper.createObjectNode();
        addUnused.put("groupId", "com.example.nonexistent");
        addUnused.put("artifactId", "ghost-lib");
        addUnused.put("version", "1.0.0");
        ToolResponse a2 = addTool.execute(addUnused);
        assertTrue(a2.isSuccess(), "add ghost-lib must succeed; got: " + a2.getError());

        // Run find_unused.
        ToolResponse r = findTool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), "find_unused must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unused = (List<Map<String, Object>>) data.get("unusedDependencies");
        assertNotNull(unused);

        // Diagnostic: confirm both deps were read out of the pom.
        Path pomFile = projectRoot.resolve("pom.xml");
        String pomContent = Files.readString(pomFile, StandardCharsets.UTF_8);
        assertEquals(2, ((Number) data.get("totalDeclared")).intValue(),
            "expected 2 declared deps; got: " + data.get("totalDeclared")
                + "\npom:\n" + pomContent);

        assertTrue(unused.stream().anyMatch(d ->
                "com.example.nonexistent".equals(d.get("groupId"))
                && "ghost-lib".equals(d.get("artifactId"))),
            "ghost-lib must be reported as unused; got: " + unused
                + "\npom:\n" + pomContent);
        assertTrue(unused.stream().noneMatch(d -> "commons-lang3".equals(d.get("artifactId"))),
            "commons-lang3 must NOT be reported (used by UsesCommons.java); got: " + unused);
    }

    @Test
    @DisplayName("happy: project with no dependencies returns empty unused list")
    void happy_noDependencies_returnsEmpty() {
        ToolResponse r = findTool.execute(objectMapper.createObjectNode());
        assertTrue(r.isSuccess(), "find_unused must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("maven", data.get("projectKind"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unused = (List<Map<String, Object>>) data.get("unusedDependencies");
        assertEquals(0, unused.size(),
            "fixture pom has no <dependency> entries; expected empty list; got: " + unused);
    }
}
