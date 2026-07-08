package org.goja.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.core.workspace.StrictDiskSync;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.GetDiagnosticsTool;
import org.goja.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21e (item C) — get_diagnostics honesty: {@code cu.reconcile()} returns a null
 * AST for non-working-copies, so the tool NEVER reported problems for closed files.
 * Now it reads the file's post-build PROBLEM markers (per-call fresh since 21d's
 * build-on-change guard) — an externally written broken file, never opened, finally
 * shows its syntax error; the clean fixture still reports zero.
 *
 * <p>Sprint 22a (P0-a): these tests run on the {@code compile-clean} fixture and
 * assert on the markers the REAL JDT Java builder emits. Before the buildSpec fix
 * the synthesized project had no Java builder, so this test injected a synthetic
 * {@code IMarker.PROBLEM} to stand in for the compiler; now the builder fires
 * (via the 21d dispatch-seam build-on-change) and emits the marker itself.</p>
 */
class GetDiagnosticsMarkersTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("THE gap case: a closed file's REAL compiler error appears in get_diagnostics")
    void closed_file_problem_marker_is_reported() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("compile-clean");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new GetDiagnosticsTool(() -> service));
        registry.setDiskSync(new StrictDiskSync(() -> service));

        // Prime the guard (first call reconciles the new root once).
        assertTrue(registry.callTool("get_diagnostics", mapper.createObjectNode()).isSuccess());

        // External write of a broken file — never opened, no working copy anywhere.
        Path broken = service.getProjectRoot()
            .resolve("src/main/java/com/example/Broken.java");
        Files.writeString(broken,
            "package com.example;\n\npublic class Broken {\n    void m() { int x = ; }\n}\n");
        // One dispatch pass so the 21d guard folds the new file into the workspace
        // and the P0-a Java builder compiles it, emitting the real syntax-error marker.
        assertTrue(registry.callTool("get_diagnostics", mapper.createObjectNode()).isSuccess());

        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", broken.toString());
        ToolResponse r = registry.callTool("get_diagnostics", args);
        assertTrue(r.isSuccess(), "diagnostics call succeeds: " + r.getError());

        Map<String, Object> d = data(r);
        assertTrue(((Number) d.get("errorCount")).intValue() >= 1,
            "closed-file real compiler error reported: " + d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diags = (List<Map<String, Object>>) d.get("diagnostics");
        Map<String, Object> broke = diags.stream()
            .filter(x -> String.valueOf(x.get("filePath")).contains("Broken.java"))
            .findFirst().orElse(null);
        assertNotNull(broke, "a diagnostic must point at Broken.java: " + diags);
        assertEquals("error", broke.get("severity"));
        assertEquals(3, broke.get("line"), "syntax error on line 4 (1-based) → tool 0-based 3");
        assertTrue(String.valueOf(broke.get("message")).contains("Syntax error"),
            "message should be the real compiler syntax error: " + broke.get("message"));
    }

    @Test
    @DisplayName("clean fixture still reports zero errors through the marker path")
    void clean_fixture_reports_zero_errors() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("compile-clean");
        ToolRegistry registry = new ToolRegistry();
        registry.register(new GetDiagnosticsTool(() -> service));
        registry.setDiskSync(new StrictDiskSync(() -> service));

        ObjectNode args = mapper.createObjectNode();
        args.put("severity", "error");
        ToolResponse r = registry.callTool("get_diagnostics", args);
        assertTrue(r.isSuccess());
        Map<String, Object> d = data(r);
        assertEquals(0, ((Number) d.get("errorCount")).intValue(), "clean fixture stays zero: " + d);
        assertTrue((int) d.get("filesChecked") > 0, "whole-project walk unchanged");
    }
}
