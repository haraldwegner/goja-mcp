package org.goja.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.JavaRuntime;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ErrorInfo;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 12 (v1.6.0) — {@code run_tests} validation + (deferred) happy-path
 * coverage.
 *
 * <p>The three happy-path tests are {@code @Disabled} because Tycho-surefire's
 * headless test runtime doesn't compile our sample-project fixtures (the
 * forked test JVM needs the fixture's compiled classes on disk, and Tycho's
 * test stage doesn't run javac on
 * {@code test-resources/sample-projects/.../src/test/java}). Production usage
 * works (manager → real workspace → real test classpath); validation tests
 * below cover the input layer. Full happy-path coverage waits on a
 * fixture-build pipeline. See {@code docs/upgrade-checklist.md}.</p>
 *
 * <p>Sprint 14 (v1.8.0) — bugs.md #1 full fix: the v1.7.1 short-circuit that
 * pre-empted plain Maven / Gradle projects with an {@code INVALID_PARAMETER}
 * + {@code mvn test} workaround is gone. Plain Maven / Gradle now flow
 * through the same launch path as PDE, with an explicit pre-computed
 * classpath that avoids the JDT JUnit launcher's
 * {@code Bundle.getHeaders()} NPE. The
 * {@link #mavenProject_runtimeClasspathMementos_resolveWithoutNpe()} test
 * pins the indirect smoke (the classpath-computation step is exactly what
 * the fix delegates to); end-to-end JVM-spawning verification still waits
 * on the fixture-build pipeline.</p>
 */
class RunTestsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RunTestsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new RunTestsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("validation: missing scope returns INVALID_PARAMETER")
    void validation_missingScope() {
        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        ErrorInfo err = r.getError();
        assertNotNull(err);
        assertEquals(ErrorInfo.INVALID_PARAMETER, err.getCode(),
            "expected INVALID_PARAMETER; got: " + err);
    }

    @Test
    @DisplayName("validation: scope.kind unknown returns INVALID_PARAMETER")
    void validation_unknownScopeKind() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "totally-bogus");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(ErrorInfo.INVALID_PARAMETER, r.getError().getCode(),
            "expected INVALID_PARAMETER; got: " + r.getError());
    }

    @Test
    @DisplayName("validation: kind=package without packageName is INVALID_PARAMETER")
    void validation_packageMissingPackageName() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "package");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(ErrorInfo.INVALID_PARAMETER, r.getError().getCode(),
            "expected INVALID_PARAMETER; got: " + r.getError());
    }

    // ========== Schema documentation contract tests (v1.7.1 / bug #3) ==========
    // Pin the documented input-combination rules so accidental edits to the
    // description block surface as test failures.

    @Test
    @DisplayName("schema doc spells out {typeName, methodName} combination for kind=method")
    void schema_methodScopeDescription_mentionsTypeNameMethodName() {
        String desc = tool.getDescription();
        assertTrue(desc.contains("{typeName, methodName}"),
            "Description must explicitly document the {typeName, methodName} combination; got:\n" + desc);
    }

    @Test
    @DisplayName("schema doc spells out {filePath, line, column} combination for kind=method")
    void schema_methodScopeDescription_mentionsFilePathLineColumn() {
        String desc = tool.getDescription();
        assertTrue(desc.contains("{filePath, line, column}"),
            "Description must explicitly document the {filePath, line, column} combination; got:\n" + desc);
    }

    @Test
    @DisplayName("schema doc explicitly rejects {filePath, methodName} combination (the bugs.md #3 misleading hint)")
    void schema_methodScopeDescription_doesNotImplyFilePathMethodName() {
        String desc = tool.getDescription();
        // The literal phrase "{filePath, methodName}" may appear once if we
        // explicitly mention "NOT valid". Make sure it ONLY appears in a
        // negative context.
        if (desc.contains("{filePath, methodName}")) {
            assertTrue(desc.contains("NOT a valid") || desc.contains("not a valid")
                || desc.contains("NOT valid")  || desc.contains("not valid"),
                "If {filePath, methodName} appears, it MUST be in a negative context "
                  + "(\"NOT a valid combination\"); got:\n" + desc);
        }
    }

    @Test
    @DisplayName("methodName field description documents the typeName pairing rule")
    void schema_methodNameField_documentsTypeNamePairing() {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> root = (java.util.Map<String, Object>) tool.getInputSchema();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> props = (java.util.Map<String, Object>) root.get("properties");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> scope = (java.util.Map<String, Object>) props.get("scope");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> scopeProps = (java.util.Map<String, Object>) scope.get("properties");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> methodNameProp = (java.util.Map<String, Object>) scopeProps.get("methodName");

        String methodNameDesc = (String) methodNameProp.get("description");
        assertTrue(methodNameDesc.contains("typeName"),
            "methodName description must mention typeName as the pairing field; got: " + methodNameDesc);
    }

    // ========== Bug #1 full-fix smoke (Sprint 14 / v1.8.0) ==========
    // The v1.7.1 workaround dispatch is gone. Plain Maven / Gradle projects
    // now flow through the same JDT JUnit launcher path as PDE, with an
    // explicit pre-computed runtime classpath that avoids the
    // Bundle.getHeaders() NPE. The smoke below verifies the classpath
    // computation step (the load-bearing change) succeeds for a plain Maven
    // project; end-to-end JVM-spawning happy-path coverage still waits on the
    // fixture-build pipeline (see @Disabled tests below).

    @Test
    @DisplayName("Bug #1: JavaRuntime.computeUnresolvedRuntimeClasspath succeeds on plain Maven without NPE")
    void mavenProject_runtimeClasspathMementos_resolveWithoutNpe() throws Exception {
        // simple-maven is loaded in @BeforeEach. The fix in JUnitLaunchHelper
        // calls JavaRuntime.computeUnresolvedRuntimeClasspath on this exact
        // shape of project to populate ATTR_CLASSPATH on the launch config,
        // sidestepping the JDT launcher's PluginClasspathProvider path that
        // NPEs on a missing OSGi Bundle. If this returns non-empty without
        // throwing, the load-bearing side of the fix works for Maven.
        IJavaProject jp = helper.getService().allProjects().iterator().next().javaProject();
        IRuntimeClasspathEntry[] entries = JavaRuntime.computeUnresolvedRuntimeClasspath(jp);
        assertNotNull(entries, "computeUnresolvedRuntimeClasspath must not return null");
        assertTrue(entries.length > 0,
            "computeUnresolvedRuntimeClasspath must return at least one entry for a Maven project; got 0");
        for (IRuntimeClasspathEntry entry : entries) {
            String memento = entry.getMemento();
            assertNotNull(memento, "Each entry must produce a non-null memento (used by ATTR_CLASSPATH)");
            assertFalse(memento.isBlank(), "Each entry's memento must be non-blank; got: '" + memento + "'");
        }
    }

    @Test
    @DisplayName("Bug #1 end-to-end: triggering run_tests on plain Maven does not surface Bundle.getHeaders / OSGi NPE")
    void runTests_plainMaven_doesNotNpeEndToEnd() {
        // End-to-end NPE-avoidance smoke. The simple-maven fixture has test
        // sources but no compiled .class files (the fixture-build pipeline is
        // separate work — see the @Disabled happy-path tests below). So the
        // actual launch is expected to FAIL — what we're verifying is the
        // SHAPE of the failure:
        //   - PRE-FIX behaviour (v1.7.1): the OSGi NPE on Bundle.getHeaders()
        //     surfaced as INTERNAL_ERROR.
        //   - WITH FIX (v1.8.0): the launch proceeds far enough to fail on
        //     something else (test class not found, empty results, timeout, …)
        //     but NEVER on Bundle.getHeaders().
        // Short timeout caps the wait — the launch fails fast or times out
        // bounded.
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "method");
        scope.put("typeName", "com.example.SampleTest");
        scope.put("methodName", "testAddition");
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 8);

        ToolResponse r = tool.execute(args);

        String allText;
        if (r.isSuccess()) {
            allText = String.valueOf(r.getData());
        } else {
            allText = (r.getError().getCode() + " : " + r.getError().getMessage()
                + " | " + r.getError().getHint());
        }
        assertFalse(allText.contains("Bundle.getHeaders"),
            "Response must not mention Bundle.getHeaders (PRE-FIX OSGi NPE substring); got: " + allText);
        assertFalse(allText.contains("\"bundle\" is null"),
            "Response must not mention the canonical Bundle-was-null NPE phrase; got: " + allText);
    }

    @Test
    @Disabled("Pending fixture-build pipeline: JUnit launching from the "
        + "Tycho-surefire test runtime needs sample-project test classes "
        + "compiled on disk for the forked JVM's classpath. The Sprint 14 / "
        + "v1.8.0 bug #1 fix removed the OSGi NPE block (see "
        + "mavenProject_runtimeClasspathMementos_resolveWithoutNpe above); "
        + "end-to-end coverage waits on the fixture-build pipeline. "
        + "Production usage via the manager works. See "
        + "docs/upgrade-checklist.md.")
    @DisplayName("happy: methodScope on testAddition returns one passed result")
    void happy_methodScope_returnsPassed() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "method");
        scope.put("typeName", "com.example.SampleTest");
        scope.put("methodName", "testAddition");
        args.put("framework", "junit5");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
    }

    @Test
    @Disabled("Pending fixture-build pipeline — see "
        + "happy_methodScope_returnsPassed and docs/upgrade-checklist.md.")
    @DisplayName("happy: classScope on SampleTest returns mixed pass/fail results")
    void happy_classScope_returnsMixedResults() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", "com.example.SampleTest");
        args.put("framework", "junit5");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
    }

    @Test
    @Disabled("Pending fixture-build pipeline — see "
        + "happy_methodScope_returnsPassed and docs/upgrade-checklist.md.")
    @DisplayName("happy: packageScope on com.example collects all tests")
    void happy_packageScope_collectsAllTests() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "package");
        scope.put("packageName", "com.example");
        args.put("framework", "junit5");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
    }
}
