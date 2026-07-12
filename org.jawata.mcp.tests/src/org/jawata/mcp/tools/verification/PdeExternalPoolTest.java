package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.core.JdtServiceImpl;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.execution.RunnerClasspath;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sprint 23 (D7) — the EXTERNAL bundle pool: the {@code pde-external}
 * fixture's Require-Bundle (jackson-databind) and Import-Package (org.slf4j,
 * jackson-core/annotations) resolve from pool DIRECTORIES (here: the running
 * dist's own {@code bundles/} + {@code test-bundles/}), the sibling test
 * bundle resolves through the Sprint-11 WORKSPACE pool, both compile with 0
 * errors, and the PDE test suite runs green through the forked runner.
 */
class PdeExternalPoolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ObjectMapper objectMapper;
    private String poolsBefore;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        String distRoot = System.getProperty("jawata.dist.root");
        assumeTrue(distRoot != null, "jawata.dist.root must be set (the boot sets it)");
        poolsBefore = System.getProperty("jawata.bundle.pools");
        System.setProperty("jawata.bundle.pools",
            Path.of(distRoot, "bundles") + File.pathSeparator + Path.of(distRoot, "test-bundles"));
    }

    @AfterEach
    void restorePools() {
        if (poolsBefore == null) {
            System.clearProperty("jawata.bundle.pools");
        } else {
            System.setProperty("jawata.bundle.pools", poolsBefore);
        }
    }

    @Test
    @DisplayName("D7: external Require-Bundle/Import-Package resolve, fixtures compile 0 errors, PDE suite runs green")
    void externalPool_compilesAndRunsPdeTests() throws Exception {
        // Load the LIBRARY bundle first so the workspace pool can serve the
        // test bundle's Require-Bundle on it.
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-external", "pde-external-tests");

        LoadedProject testsProject = null;
        for (LoadedProject p : service.allProjects()) {
            if (p.projectKey().contains("ext-tests")
                    || p.projectRoot().getFileName().toString().equals("pde-external-tests")) {
                testsProject = p;
            }
        }
        assertNotNull(testsProject, "pde-external-tests must be loaded; got: "
            + service.allProjects().stream().map(LoadedProject::projectKey).toList());

        // Compile gate: BOTH fixtures build with 0 errors on the pool-resolved
        // classpath (buildAndCheck returns null exactly then).
        for (LoadedProject p : service.allProjects()) {
            String problem = RunnerClasspath.buildAndCheck(p.javaProject(), new NullProgressMonitor());
            if (problem != null) {
                StringBuilder cp = new StringBuilder();
                for (org.eclipse.jdt.core.IClasspathEntry e : p.javaProject().getRawClasspath()) {
                    cp.append("\n  ").append(e.getEntryKind()).append(' ').append(e.getPath());
                }
                assertNull(problem, "PDE fixture '" + p.projectKey()
                    + "' must compile 0 errors on the pool classpath; raw classpath:" + cp);
            }
        }

        // Run the PDE suite through the forked runner on the resolved classpath.
        RunTestsTool tool = new RunTestsTool(() -> service);
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", "com.example.exttests.ExtLibTest");
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        args.put("projectKey", testsProject.projectKey());

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        assertEquals(2, summary.get("total"), "summary: " + summary
            + " failures: " + data.get("failures") + " stderr: " + data.get("stderrTail"));
        assertEquals(2, summary.get("passed"), "summary: " + summary
            + " failures: " + data.get("failures"));
        assertEquals(Boolean.TRUE, summary.get("evidenceFinalized"), "summary: " + summary);
    }
}
