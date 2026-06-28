package org.goja.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ErrorInfo;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.CompileWorkspaceTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 12 (v1.6.0) — {@code compile_workspace} contract tests.
 *
 * <p>What this tool owns and what we test here:</p>
 * <ul>
 *   <li>Walking {@code service.allProjects()} (or one project when
 *       {@code projectKey} is set).</li>
 *   <li>Reading {@link IMarker#PROBLEM} markers via
 *       {@link org.eclipse.core.resources.IResource#findMarkers}.</li>
 *   <li>Mapping severity, formatting {@code filePath} / {@code line} /
 *       {@code message} into the documented JSON shape.</li>
 *   <li>Aggregating {@code errorCount} / {@code warningCount} and validating
 *       inputs ({@code minSeverity}, {@code projectKey}).</li>
 * </ul>
 *
 * <p>What we delegate to Eclipse and DON'T re-test:
 * {@code project.refreshLocal} + {@code project.build} actually invoking
 * the JDT compiler and emitting markers from broken Java source. That's a
 * 20-year-old Eclipse contract powering the IDE Problems view; if it
 * regressed, every Eclipse install on the planet would.</p>
 *
 * <p>The {@code compileError_*} test below uses
 * {@link IFile#createMarker(String)} directly — the same API JDT's compiler
 * uses internally — to attach a known PROBLEM marker to a known fixture
 * file. {@code compile_workspace} then reads + structures it. This works
 * in any Eclipse runtime (Tycho-test or production) because it doesn't
 * depend on the build job firing.</p>
 */
class CompileWorkspaceToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private CompileWorkspaceTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new CompileWorkspaceTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: clean simple-maven project compiles with zero errors")
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
            "clean fixture must yield zero errors; diagnostics=" + data.get("diagnostics"));
        assertTrue(((Number) data.get("projectsCompiled")).intValue() >= 1,
            "at least one project must have been compiled");
    }

    @Test
    @DisplayName("contract: PROBLEM marker on a fixture file is surfaced as ERROR diagnostic")
    void compileError_surfacesProblemMarker() throws Exception {
        // Find HelloWorld.java's IFile in the loaded Eclipse project. The
        // exact linked-folder name (e.g. src-main-java vs src-1-...) varies
        // by ProjectImporter behaviour, so walk the resource tree by file
        // name rather than hardcoding a path.
        IProject project = service.getJavaProject().getProject();
        AtomicReference<IFile> found = new AtomicReference<>();
        project.accept(resource -> {
            if (resource instanceof IFile f && "HelloWorld.java".equals(f.getName())) {
                found.compareAndSet(null, f);
            }
            return true;
        });
        IFile target = found.get();
        assertNotNull(target, "HelloWorld.java IFile must be present in the loaded project");

        // Attach a real PROBLEM marker — the same API JDT's compiler uses
        // when it detects a compile error.
        IMarker marker = target.createMarker(IMarker.PROBLEM);
        marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
        marker.setAttribute(IMarker.MESSAGE, "synthetic compile_workspace test marker");
        marker.setAttribute(IMarker.LINE_NUMBER, 5);   // 1-based as Eclipse stores
        marker.setAttribute(IMarker.CHAR_START, 100);
        marker.setAttribute(IMarker.CHAR_END, 110);

        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(),
            "compile_workspace must succeed when problem markers exist; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();

        assertEquals(1, ((Number) data.get("errorCount")).intValue(),
            "exactly one ERROR diagnostic expected; data=" + data);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) data.get("diagnostics");
        assertEquals(1, diagnostics.size(), "one diagnostic expected; got: " + diagnostics);

        Map<String, Object> diag = diagnostics.get(0);
        assertEquals("ERROR", diag.get("severity"));
        assertEquals("synthetic compile_workspace test marker", diag.get("message"));
        assertTrue(String.valueOf(diag.get("filePath")).endsWith("HelloWorld.java"),
            "filePath must point at HelloWorld.java; got: " + diag.get("filePath"));
        // Tool converts Eclipse's 1-based line number to 0-based; line=5 → 4.
        assertEquals(4, ((Number) diag.get("line")).intValue());
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
    @DisplayName("Sprint 14 (bugs.md #9): scope='main' excludes test-source markers")
    void scope_main_excludesTestSourceMarker() throws Exception {
        attachSyntheticErrorTo("SampleTest.java", "test-source synthetic marker");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "main");
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(0, ((Number) data.get("errorCount")).intValue(),
            "scope='main' must hide the test-source marker; diagnostics=" + data.get("diagnostics"));
    }

    @Test
    @DisplayName("Sprint 14 (bugs.md #9): scope='test' surfaces test-source markers only")
    void scope_test_surfacesTestSourceMarker_excludesMainSourceMarker() throws Exception {
        attachSyntheticErrorTo("SampleTest.java", "test-source synthetic marker");
        attachSyntheticErrorTo("HelloWorld.java", "main-source synthetic marker");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("scope", "test");
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, ((Number) data.get("errorCount")).intValue(),
            "scope='test' must surface exactly the test-source marker; diagnostics=" + data.get("diagnostics"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) data.get("diagnostics");
        assertEquals("test-source synthetic marker", diagnostics.get(0).get("message"));
    }

    @Test
    @DisplayName("Sprint 14 (bugs.md #9): scope='both' is the default and surfaces both main + test markers")
    void scope_both_isDefault_andSurfacesAllMarkers() throws Exception {
        attachSyntheticErrorTo("SampleTest.java", "test-source synthetic marker");
        attachSyntheticErrorTo("HelloWorld.java", "main-source synthetic marker");

        ObjectNode args = objectMapper.createObjectNode();
        // No scope arg → default "both"
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(2, ((Number) data.get("errorCount")).intValue(),
            "default scope must surface both main and test markers; diagnostics=" + data.get("diagnostics"));
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

    /**
     * Attach a synthetic PROBLEM error marker to a fixture file by name.
     * Same pattern as {@link #compileError_surfacesProblemMarker()} — bypasses
     * the JDT build job and exercises only the marker-read + structuring code
     * path. Tycho-test-runtime-friendly.
     */
    private void attachSyntheticErrorTo(String fileName, String message) throws Exception {
        IProject project = service.getJavaProject().getProject();
        AtomicReference<IFile> found = new AtomicReference<>();
        project.accept(resource -> {
            if (resource instanceof IFile f && fileName.equals(f.getName())) {
                found.compareAndSet(null, f);
            }
            return true;
        });
        IFile target = found.get();
        assertNotNull(target, fileName + " IFile must be present in the loaded project");
        IMarker marker = target.createMarker(IMarker.PROBLEM);
        marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
        marker.setAttribute(IMarker.MESSAGE, message);
        marker.setAttribute(IMarker.LINE_NUMBER, 1);
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
}
