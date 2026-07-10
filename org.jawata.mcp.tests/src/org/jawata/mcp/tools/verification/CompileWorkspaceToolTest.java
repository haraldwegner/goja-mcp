package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ErrorInfo;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.CompileWorkspaceTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code compile_workspace} contract tests.
 *
 * <p>What this tool owns and what we test here:</p>
 * <ul>
 *   <li>Walking {@code service.allProjects()} (or one project when
 *       {@code projectKey} is set), invoking {@code refreshLocal} +
 *       {@code project.build(FULL_BUILD)}.</li>
 *   <li>Reading {@link org.eclipse.core.resources.IMarker#PROBLEM} markers,
 *       mapping severity, formatting {@code filePath} / {@code line} /
 *       {@code message}, aggregating {@code errorCount} / {@code warningCount},
 *       validating inputs ({@code minSeverity}, {@code scope}, {@code projectKey}).</li>
 * </ul>
 *
 * <p><b>Non-vacuous by construction (Sprint 22a P0-a).</b> These tests run
 * against the {@code compile-clean} fixture — a project that genuinely
 * compiles against the JRE alone — and exercise the REAL JDT Java builder:
 * {@code compile_workspace}'s {@code FULL_BUILD} compiles the source and the
 * assertions read the markers the compiler actually emits. A clean project
 * yields zero errors; a genuinely-broken file dropped into a source root
 * yields a real compiler error.</p>
 *
 * <p>Before the P0-a buildSpec fix the synthesized {@code .project} carried an
 * EMPTY buildSpec, so {@code project.build(...)} ran no builders — "clean → 0"
 * passed vacuously and error cases had to inject synthetic
 * {@code IMarker.PROBLEM} markers (which the now-firing builder wipes off the
 * compilation units it processes). That strategy is gone; every compile
 * assertion below is backed by real compilation.</p>
 */
class CompileWorkspaceToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private CompileWorkspaceTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("compile-clean");
        tool = new CompileWorkspaceTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: clean compile-clean project compiles with zero errors (real builder — non-vacuous)")
    void happy_cleanProject_returnsZeroErrors() {
        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(),
            "compile_workspace must succeed on a clean fixture; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data, "result data must not be null");
        assertEquals("compile_workspace", data.get("operation"));
        assertEquals(0, ((Number) data.get("errorCount")).intValue(),
            "clean fixture must yield zero REAL compiler errors; diagnostics=" + data.get("diagnostics"));
        assertTrue(((Number) data.get("projectsCompiled")).intValue() >= 1,
            "at least one project must have been compiled");
    }

    @Test
    @DisplayName("P0-a (Sprint 22a): genuinely broken source yields a REAL compiler error (errorCount >= 1)")
    void brokenSource_realCompile_surfacesRealError() throws Exception {
        // Drop a genuinely-broken .java into the main source root and let
        // compile_workspace's FULL_BUILD invoke the JDT Java builder for real.
        // "int x = ;" is a pure syntax error the parser flags regardless of
        // binding/classpath resolution. Before P0-a this could never fail
        // (empty buildSpec → no builder ran).
        writeBrokenSiblingOf("Clean.java", "BrokenMain.java");

        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(),
            "compile_workspace must succeed on broken source; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        int errorCount = ((Number) data.get("errorCount")).intValue();
        assertTrue(errorCount >= 1,
            "the JDT Java builder must surface >= 1 REAL compile error for broken source; "
                + "errorCount=" + errorCount + ", diagnostics=" + data.get("diagnostics"));
        assertTrue(hasErrorIn(data, "BrokenMain.java"),
            "an ERROR diagnostic must point at BrokenMain.java (proves it is OUR real "
                + "compile error, not an incidental marker); diagnostics=" + data.get("diagnostics"));
    }

    // ========== Sprint 14 / bugs.md #8 + #9 — clean + scope params ==========

    @Test
    @DisplayName("Sprint 14 (bugs.md #8): clean=true triggers CLEAN_BUILD without errors")
    void clean_param_accepted_andStillReturnsZeroErrorsOnCleanFixture() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("clean", true);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(),
            "compile_workspace(clean=true) must succeed on a clean fixture; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(0, ((Number) data.get("errorCount")).intValue(),
            "clean fixture must yield zero errors even after CLEAN_BUILD; diagnostics=" + data.get("diagnostics"));
    }

    @Test
    @DisplayName("Sprint 14 (bugs.md #9): scope='main' surfaces the main-source error, hides the test-source one")
    void scope_main_surfacesMainError_excludesTestError() throws Exception {
        writeBrokenSiblingOf("Clean.java", "BrokenMain.java");
        writeBrokenSiblingOf("CleanSupport.java", "BrokenInTest.java");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "main");
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(hasErrorIn(data, "BrokenMain.java"),
            "scope='main' must surface the main-source error; diagnostics=" + data.get("diagnostics"));
        assertFalse(hasErrorIn(data, "BrokenInTest.java"),
            "scope='main' must hide the test-source error; diagnostics=" + data.get("diagnostics"));
    }

    @Test
    @DisplayName("Sprint 14 (bugs.md #9): scope='test' surfaces the test-source error, hides the main-source one")
    void scope_test_surfacesTestError_excludesMainError() throws Exception {
        writeBrokenSiblingOf("Clean.java", "BrokenMain.java");
        writeBrokenSiblingOf("CleanSupport.java", "BrokenInTest.java");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "test");
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(hasErrorIn(data, "BrokenInTest.java"),
            "scope='test' must surface the test-source error; diagnostics=" + data.get("diagnostics"));
        assertFalse(hasErrorIn(data, "BrokenMain.java"),
            "scope='test' must hide the main-source error; diagnostics=" + data.get("diagnostics"));
    }

    @Test
    @DisplayName("Sprint 14 (bugs.md #9): scope='both' is the default and surfaces both main + test errors")
    void scope_both_isDefault_andSurfacesAllErrors() throws Exception {
        writeBrokenSiblingOf("Clean.java", "BrokenMain.java");
        writeBrokenSiblingOf("CleanSupport.java", "BrokenInTest.java");

        ObjectNode args = objectMapper.createObjectNode();
        // No scope arg → default "both"
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(hasErrorIn(data, "BrokenMain.java"),
            "default scope must surface the main-source error; diagnostics=" + data.get("diagnostics"));
        assertTrue(hasErrorIn(data, "BrokenInTest.java"),
            "default scope must surface the test-source error; diagnostics=" + data.get("diagnostics"));
    }

    @Test
    @DisplayName("Sprint 14 validation: unknown scope returns INVALID_PARAMETER")
    void validation_unknownScope_returnsInvalidParameter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "frobnicate");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "unknown scope must be rejected");
        assertEquals(ErrorInfo.INVALID_PARAMETER, r.getError().getCode(),
            "expected INVALID_PARAMETER; got: " + r.getError());
    }

    @Test
    @DisplayName("validation: unknown projectKey returns INVALID_PARAMETER")
    void validation_unknownProjectKey_returnsInvalidParameter() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectKey", "no-such-project");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "unknown projectKey must be rejected");
        ErrorInfo err = r.getError();
        assertNotNull(err);
        assertEquals(ErrorInfo.INVALID_PARAMETER, err.getCode(),
            "expected INVALID_PARAMETER; got: " + err);
    }

    @Test
    @DisplayName("Sprint 14 (bugs.md #11): projectKey that was loaded and then removed returns PROJECT_KEY_DROPPED, not INVALID_PARAMETER")
    void droppedProjectKey_returnsProjectKeyDropped() {
        String key = service.allProjects().iterator().next().projectKey();
        assertTrue(service.removeProject(key), "fixture key should remove cleanly");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("projectKey", key);
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "dropped projectKey must be rejected");
        ErrorInfo err = r.getError();
        assertNotNull(err);
        assertEquals(ErrorInfo.PROJECT_KEY_DROPPED, err.getCode(),
            "expected PROJECT_KEY_DROPPED for a previously-loaded key; got: " + err);
        assertTrue(err.getMessage().contains(key),
            "error message should name the dropped projectKey; got: " + err.getMessage());
    }

    // ========== v2.7.1 — output cap (dogfood find: 17,383 diagnostics in one 4.25 MB response) ==========

    @Test
    @DisplayName("v2.7.1: diagnostics are paged — limit caps the array, count/truncated/hint expose the rest")
    void diagnostics_pagedByLimit() throws Exception {
        writeBrokenSiblingOf("Clean.java", "Broken1.java");
        writeBrokenSiblingOf("Clean.java", "Broken2.java");
        writeBrokenSiblingOf("Clean.java", "Broken3.java");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("limit", 2);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) data.get("diagnostics");
        assertEquals(2, diagnostics.size(), "limit=2 must cap the returned diagnostics");
        assertTrue(((Number) data.get("count")).intValue() >= 3,
            "count must carry the FULL total; got: " + data.get("count"));
        assertEquals(Boolean.TRUE, data.get("truncated"), "truncation must be flagged");
        assertNotNull(data.get("hint"), "a truncated page must carry the paging hint");
        assertTrue(((Number) data.get("errorCount")).intValue() >= 3,
            "errorCount must reflect the full set, not the page");
    }

    @Test
    @DisplayName("v2.7.1: offset pages past the first window")
    void diagnostics_offsetPages() throws Exception {
        writeBrokenSiblingOf("Clean.java", "Broken1.java");
        writeBrokenSiblingOf("Clean.java", "Broken2.java");
        writeBrokenSiblingOf("Clean.java", "Broken3.java");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("limit", 2);
        args.put("offset", 2);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) data.get("diagnostics");
        int total = ((Number) data.get("count")).intValue();
        assertEquals(Math.min(2, Math.max(0, total - 2)), diagnostics.size(),
            "offset=2/limit=2 must return the second window");
        assertEquals(2, ((Number) data.get("offset")).intValue(), "offset must be echoed");
    }

    @Test
    @DisplayName("v2.7.1: summary=true returns counts only — no diagnostics array, byProject breakdown present")
    void summary_returnsCountsWithoutDiagnostics() throws Exception {
        writeBrokenSiblingOf("Clean.java", "Broken1.java");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("summary", true);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertFalse(data.containsKey("diagnostics"),
            "summary mode must NOT ship the diagnostics array");
        assertEquals(Boolean.TRUE, data.get("summary"));
        assertTrue(((Number) data.get("errorCount")).intValue() >= 1,
            "summary keeps the full error count");
        assertNotNull(data.get("byProject"), "summary carries the per-project breakdown");
    }

    // ================================ helpers ================================

    /**
     * Write a genuinely-broken {@code .java} file into the same source folder /
     * package as {@code anchorFileName} (e.g. {@code Clean.java} in the main
     * root, {@code CleanSupport.java} in the test root), so the JDT Java builder
     * compiles it and emits a real PROBLEM marker. Returns the created file.
     */
    private IFile writeBrokenSiblingOf(String anchorFileName, String newFileName) throws Exception {
        IProject project = service.getJavaProject().getProject();
        AtomicReference<IFile> anchor = new AtomicReference<>();
        project.accept(resource -> {
            if (resource instanceof IFile f && anchorFileName.equals(f.getName())) {
                anchor.compareAndSet(null, f);
            }
            return true;
        });
        IFile a = anchor.get();
        assertNotNull(a, anchorFileName + " must be present in the loaded project");

        IContainer pkgDir = a.getParent();
        IFile broken = pkgDir.getFile(new Path(newFileName));
        String typeName = newFileName.substring(0, newFileName.length() - ".java".length());
        String badSource =
            "package com.example;\n"
            + "public class " + typeName + " {\n"
            + "    void broken() {\n"
            + "        int x = ;\n"
            + "    }\n"
            + "}\n";
        broken.create(
            new ByteArrayInputStream(badSource.getBytes(StandardCharsets.UTF_8)),
            true, new NullProgressMonitor());
        return broken;
    }

    /**
     * @return {@code true} iff some ERROR diagnostic's {@code filePath} ends
     * with {@code fileName}. Robust to a single syntax error producing one or
     * more markers.
     */
    private static boolean hasErrorIn(Map<String, Object> data, String fileName) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) data.get("diagnostics");
        if (diagnostics == null) return false;
        return diagnostics.stream().anyMatch(d ->
            "ERROR".equals(d.get("severity"))
                && String.valueOf(d.get("filePath")).endsWith(fileName));
    }
}
