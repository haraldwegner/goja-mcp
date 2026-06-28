package org.goja.mcp.tools.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.workflow.FormatTool;
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
 * Sprint 13 (v1.7.0) — {@code format} contract tests.
 *
 * <p>Drops a deliberately mis-indented file into the fixture's temp copy
 * and verifies the JDT formatter normalises it. Dry-run path verifies the
 * tool returns a diff without writing.</p>
 */
class FormatToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private FormatTool tool;
    private ObjectMapper objectMapper;
    private Path messyFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new FormatTool(() -> service);
        objectMapper = new ObjectMapper();

        // Drop a deliberately bad-formatted file into the temp project
        // copy so we have a known target the formatter will rewrite.
        Path projectRoot = service.allProjects().iterator().next().projectRoot();
        messyFile = projectRoot.resolve("src/main/java/com/example/Messy.java");
        Files.writeString(messyFile,
            "package com.example;\n"
                + "public  class   Messy {\n"
                + "public   int  add (   int   a ,    int  b ) {\n"
                + "return  a +  b ;\n"
                + "}\n"
                + "}\n",
            StandardCharsets.UTF_8);
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());
    }

    @Test
    @DisplayName("happy: format file normalises whitespace")
    void happy_formatFile_normalizesWhitespace() throws Exception {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "file");
        scope.put("filePath", messyFile.toString());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, ((Number) data.get("filesFormatted")).intValue(),
            "exactly one file expected to format; data=" + data);

        // Reload the file and verify the messy whitespace has been normalised.
        String updated = Files.readString(messyFile, StandardCharsets.UTF_8);
        assertTrue(!updated.contains("public  class   Messy"),
            "double/triple spaces in class decl must be gone; got:\n" + updated);
        assertTrue(updated.contains("public class Messy"),
            "single-spaced class decl expected; got:\n" + updated);
    }

    @Test
    @DisplayName("dryRun: returns diff samples without writing files")
    void dryRun_returnsDiffWithoutWriting() throws Exception {
        String before = Files.readString(messyFile, StandardCharsets.UTF_8);

        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "file");
        scope.put("filePath", messyFile.toString());
        args.put("dryRun", true);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(true, data.get("dryRun"));
        assertEquals(1, ((Number) data.get("filesFormatted")).intValue());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> samples = (List<Map<String, Object>>) data.get("samples");
        assertNotNull(samples);
        assertEquals(1, samples.size(), "expected one sample entry; got: " + samples);

        // File on disk must be unchanged.
        String after = Files.readString(messyFile, StandardCharsets.UTF_8);
        assertEquals(before, after,
            "dryRun must not modify the file on disk; before/after diverged");
    }
}
