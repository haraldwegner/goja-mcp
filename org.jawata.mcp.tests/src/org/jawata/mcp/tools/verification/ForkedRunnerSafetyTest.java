package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 23 (D1) — the §13 safe-execution proofs on the forked-runner
 * spine, against the {@code runner-pathological} fixture:
 *
 * <ul>
 *   <li>a hanging test is REAPED at the timeout (process tree included) and
 *       the result honestly says the evidence was not finalized;</li>
 *   <li>a memory hog dies at the runner's -Xmx bound as a FAILED result in
 *       the CHILD — the server JVM is untouched;</li>
 *   <li>the runner environment is CLEARED: PATH — present in every
 *       server-side environment, the canary that would leak on inheritance —
 *       must not reach the runner (asserted INSIDE the fixture test, which
 *       only passes under a cleared env).</li>
 * </ul>
 */
class ForkedRunnerSafetyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RunTestsTool tool;
    private ObjectMapper objectMapper;
    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("runner-pathological");
        tool = new RunTestsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("§13 timeout: a hanging test is reaped, no orphan runner JVM, evidence honest")
    void timeout_reapsRunnerProcessTree() {
        ObjectNode args = classScope("com.example.pathological.HangingTest");
        args.put("timeoutSeconds", 8);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> summary = summaryOf(r);
        assertEquals(Boolean.TRUE, summary.get("timedOut"), "summary: " + summary);
        assertEquals(Boolean.FALSE, summary.get("evidenceFinalized"), "summary: " + summary);
        assertNotNull(summary.get("evidenceNote"), "an unfinalized run must explain itself");

        // No orphan of OURS. A machine-wide scan for the runner main class is wrong
        // in a parallel suite: it catches sibling tests' LEGITIMATE runners (it caught
        // a coverage test's, mid-run) and calls them orphans. Scope it to this test's
        // own fixture copy, and poll — reaping (destroyForcibly + descendants) is
        // asynchronous. Same fix as AsyncRunTestsTest carried in Sprint 23; this test
        // had the identical defect and was simply never hit until now.
        String ourFixture = service.getProjectRoot().toString();
        List<String> orphans = List.of();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            orphans = ProcessHandle.allProcesses()
                .filter(ProcessHandle::isAlive)
                .map(p -> p.info().commandLine().orElse(""))
                .filter(cmd -> cmd.contains("org.jawata.testrunner.Main")
                    && cmd.contains(ourFixture))
                .toList();
            if (orphans.isEmpty()) {
                break;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(orphans.isEmpty(), "orphan runner JVM(s) survived the reap: " + orphans);
    }

    @Test
    @DisplayName("§13 memory bound: the hog dies at the CHILD's -Xmx with honest OOM evidence")
    void memoryBound_hogFailsInChildJvm() {
        ObjectNode args = classScope("com.example.pathological.MemoryHogTest");
        args.put("timeoutSeconds", 120);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        // OutOfMemoryError is UNRECOVERABLE for the JUnit platform: depending
        // on where the allocation trips, the engine either records a FAILED
        // result or the whole runner aborts (honest evidence-not-finalized).
        // Either way the OOM evidence must be visible and the bound must have
        // fired in the CHILD — this server JVM keeps running (we're asserting).
        Map<String, Object> summary = summaryOf(r);
        String failureText = String.valueOf(dataOf(r).get("failures"));
        String stderrText = String.valueOf(dataOf(r).get("stderrTail"));
        boolean asFailedResult = Integer.valueOf(1).equals(summary.get("failed"))
            && failureText.contains("OutOfMemoryError");
        boolean asAbortedRun = Boolean.FALSE.equals(summary.get("evidenceFinalized"))
            && stderrText.contains("OutOfMemoryError");
        assertTrue(asFailedResult || asAbortedRun,
            "the -Xmx bound must surface as OOM evidence (FAILED result or honest "
                + "unfinalized run); summary: " + summary
                + " failures: " + failureText + " stderr: " + stderrText);
    }

    @Test
    @DisplayName("§13 env allowlist: the runner environment is cleared (PATH canary)")
    void envAllowlist_pathCanaryDoesNotLeak() {
        // Sanity: the SERVER environment does carry the canary this probe
        // relies on — otherwise the fixture assertion would prove nothing.
        assertNotNull(System.getenv("PATH"), "test precondition: server env has PATH");

        ObjectNode args = classScope("com.example.pathological.EnvProbeTest");
        args.put("timeoutSeconds", 120);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> summary = summaryOf(r);
        assertEquals(1, summary.get("passed"),
            "EnvProbeTest passes ONLY under a cleared environment; summary: " + summary
                + " failures: " + dataOf(r).get("failures")
                + " stderr: " + dataOf(r).get("stderrTail"));
    }

    private ObjectNode classScope(String typeName) {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", typeName);
        args.put("framework", "junit5");
        return args;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summaryOf(ToolResponse r) {
        return (Map<String, Object>) dataOf(r).get("summary");
    }
}
