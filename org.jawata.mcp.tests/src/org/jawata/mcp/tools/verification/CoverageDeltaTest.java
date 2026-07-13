package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 23 (D3, C8) — delta vs git diffs (worktree/staged/range,
 * rename-clean), baseline regression blind to a rising global percentage,
 * versioned thresholds with visible waivers, stability marking that
 * excludes unstable lines from gate answers, and CI import gated by the
 * class-id check.
 */
class CoverageDeltaTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RunTestsTool tool;
    private ObjectMapper om;
    private String covDirBefore;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        covDirBefore = System.getProperty("jawata.coverage.dir");
        System.setProperty("jawata.coverage.dir",
            Files.createTempDirectory("jawata-cov-store-").toString());
        service = helper.loadProjectCopy("coverage-target");
        tool = new RunTestsTool(() -> service);
        om = new ObjectMapper();
        root = service.getProjectRoot();
        git("init", "-q");
        git("config", "user.email", "fixture@test");
        git("config", "user.name", "fixture");
        git("add", "-A");
        git("commit", "-q", "-m", "fixture baseline");
    }

    @AfterEach
    void restore() {
        if (covDirBefore == null) {
            System.clearProperty("jawata.coverage.dir");
        } else {
            System.setProperty("jawata.coverage.dir", covDirBefore);
        }
    }

    @Test
    @DisplayName("C8 delta: worktree/staged/range exact; pure rename yields no false gap")
    void delta_exactAcrossDiffKinds_renameClean() throws Exception {
        // Change 1: a NEW never-called method with exactly 2 executable lines.
        Path covered = root.resolve("src/main/java/com/example/cov/Covered.java");
        String src = Files.readString(covered);
        src = src.replace("public int alwaysCalled(int x) {",
            """
            public int freshUncovered(int x) {
                    int a = x + 3;
                    return a * 2;
                }

                public int alwaysCalled(int x) {""");
        // Change 2: an inserted COMMENT block above (shifts everything below —
        // shifted covered lines must not surface as changes).
        src = src.replace("public class Covered {",
            "// shift block line 1\n// shift block line 2\n// shift block line 3\npublic class Covered {");
        Files.writeString(covered, src);
        // Change 3: a PURE rename of an unchanged, covered file.
        git("mv", "src/main/java/com/example/cov/Helpers.java",
            "src/main/java/com/example/cov/Helpers2.java");

        rebuild();
        String artifactId = runWithCoverage("com.example.cov.CoveredTest");

        for (String[] mode : new String[][] {
                {"worktree", null}, {"staged", null}}) {
            if ("staged".equals(mode[0])) git("add", "-A");
            Map<String, Object> delta = coverageAction("coverage_delta", artifactId,
                Map.of("diff", mode[0]));
            assertDeltaShape(delta, mode[0]);
        }
        git("add", "-A");
        git("commit", "-q", "-m", "changes");
        Map<String, Object> delta = coverageAction("coverage_delta", artifactId,
            Map.of("diff", "range", "range", "HEAD~1..HEAD"));
        assertDeltaShape(delta, "range");
    }

    private void assertDeltaShape(Map<String, Object> delta, String mode) {
        Map<String, Object> totals = cast2(delta.get("totals"));
        assertEquals(2, totals.get("uncoveredChangedLines"),
            mode + ": exactly the 2 freshUncovered body lines; got: " + delta);
        // The renamed-but-unchanged CovHelper must not appear as a gap.
        String all = String.valueOf(delta.get("files"));
        assertFalse(all.contains("Helpers2.java") && all.contains("uncoveredChangedLines=[")
                && all.substring(all.indexOf("Helpers2.java")).contains("uncoveredChangedLines"),
            mode + ": pure rename must yield no uncovered-changed lines; got: " + all);
    }

    @Test
    @DisplayName("C8 baseline: regression named while the global percentage RISES")
    void baseline_regressionDespiteRisingGlobal() throws Exception {
        String baselineArtifact = runWithCoverage("com.example.cov.CoveredTest");
        coverageAction("coverage_baseline_set", baselineArtifact, Map.of("name", "main"));

        // Rewrite the test: DROP branchy(true) (its then-arm becomes newly
        // missed) but cover much MORE overall (neverCalled + run the lambda +
        // branchy(false)) — the global percentage rises.
        Path test = root.resolve("src/test/java/com/example/cov/CoveredTest.java");
        Files.writeString(test, """
            package com.example.cov;

            import org.junit.jupiter.api.Test;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            public class CoveredTest {
                @Test
                void covering() {
                    Covered c = new Covered();
                    assertEquals(5, c.alwaysCalled(4));
                    assertEquals(8, c.neverCalled(4));
                    assertEquals("no", c.branchy(false));
                    c.lambdaHolder().run();
                    assertEquals("alpha", CovEnum.ALPHA.tag());
                    assertEquals("helped", CovHelper.help());
                    assertEquals(2, new Flip().flip(false));
                    assertEquals(1, new Flip().flip(true));
                    assertEquals("boo", new NeverLoaded().ghost());
                }
            }
            """);
        rebuild();
        String after = runWithCoverage("com.example.cov.CoveredTest");

        Map<String, Object> compare = coverageAction("coverage_baseline_compare", after,
            Map.of("name", "main"));
        assertEquals(Boolean.TRUE, compare.get("regression"), "got: " + compare);
        assertTrue(String.valueOf(compare.get("newlyMissed")).contains("Covered#branchy"),
            "branchy's then-arm must be named; got: " + compare);
        double before = parsePercent(compare.get("globalLinePercentBefore"));
        double afterPct = parsePercent(compare.get("globalLinePercentAfter"));
        assertTrue(afterPct > before,
            "the scenario must have a RISING global percentage (" + before + " -> "
                + afterPct + "); got: " + compare);
    }

    @Test
    @DisplayName("C8 thresholds: value + policy VERSION + waivers visible; version bumps")
    void thresholds_versionedWaiversVisible() throws Exception {
        coverageAction("coverage_threshold_set", null, Map.of(
            "lineThresholdPercent", 80,
            "waivers", List.of(Map.of("pattern", "com.example.cov.NeverLoaded",
                "reason", "known ghost — waived"))));
        String artifactId = runWithCoverage("com.example.cov.CoveredTest");

        Map<String, Object> report = coverageAction("coverage_report", artifactId, Map.of());
        Map<String, Object> threshold = cast2(report.get("threshold"));
        assertNotNull(threshold, "an active policy must surface: " + report);
        assertEquals(80.0, ((Number) threshold.get("lineThresholdPercent")).doubleValue());
        assertEquals(1, threshold.get("policyVersion"));
        assertTrue(String.valueOf(threshold.get("waivers")).contains("known ghost"),
            "waivers must be visible; got: " + threshold);
        assertNotNull(threshold.get("verdict"));

        coverageAction("coverage_threshold_set", null, Map.of("lineThresholdPercent", 85));
        Map<String, Object> report2 = coverageAction("coverage_report", artifactId, Map.of());
        assertEquals(2, cast2(report2.get("threshold")).get("policyVersion"),
            "a policy change must visibly bump the version");
    }

    @Test
    @DisplayName("C8 stability: flip-flopping lines marked UNSTABLE and excluded from gate answers")
    void stability_unstableNeverCountsCovered() throws Exception {
        String runA = runWithCoverage("com.example.cov.FlipTest");
        String runB = runWithCoverage("com.example.cov.FlipTest", "-Dcov.flip=true");

        Map<String, Object> stability = coverageAction("coverage_stability", runA,
            Map.of("otherArtifactId", runB));
        Map<String, Object> unstable = cast2(stability.get("unstable"));
        assertTrue(unstable.containsKey("com.example.cov.Flip"),
            "the flip-dependent line must be unstable; got: " + stability);

        // Gate exclusion: touch the unstable line, cover it in a fresh run —
        // the delta must STILL refuse to count it as covered.
        Path flip = root.resolve("src/main/java/com/example/cov/Flip.java");
        Files.writeString(flip, Files.readString(flip).replace(
            "return 1;", "return 1 + 0;"));
        rebuild();
        String runC = runWithCoverage("com.example.cov.FlipTest", "-Dcov.flip=true");
        Map<String, Object> delta = coverageAction("coverage_delta", runC,
            Map.of("diff", "worktree"));
        Map<String, Object> totals = cast2(delta.get("totals"));
        assertTrue(((Number) totals.get("unstableChangedLines")).intValue() >= 1,
            "the changed unstable line must be reported unstable, not covered; got: " + delta);
    }

    @Test
    @DisplayName("C8 import: matching bytes analyze; mismatched bytes are refused")
    void import_classIdGate() throws Exception {
        String local = runWithCoverage("com.example.cov.CoveredTest");
        Path exported = Files.createTempFile("ci-export-", ".exec");
        Files.copy(new org.jawata.mcp.coverage.CoverageStore().execFile(local), exported,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        ObjectNode args = om.createObjectNode();
        args.put("action", "coverage_import");
        args.put("execFile", exported.toString());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "import failed: " + r.getError());
        String imported = (String) cast2(r.getData()).get("artifactId");

        // Matching bytes: analyzes with real totals, no stale refusals.
        Map<String, Object> report = coverageAction("coverage_report", imported, Map.of());
        assertNotNull(report.get("totals"), "got: " + report);
        assertFalse(report.containsKey("staleClasses"), "got: " + report);

        // Mismatched bytes: modify + rebuild → the SAME import is refused.
        Path covered = root.resolve("src/main/java/com/example/cov/Covered.java");
        Files.writeString(covered, Files.readString(covered).replace(
            "return x + 1;", "int y2 = x; return y2 + 1;"));
        rebuild();
        Map<String, Object> after = coverageAction("coverage_report", imported, Map.of());
        assertTrue(String.valueOf(after.get("staleClasses")).contains("com.example.cov.Covered"),
            "mismatched bytes must be REFUSED; got: " + after);
    }

    // ------------------------------------------------------------- helpers

    private void git(String... args) throws Exception {
        java.util.List<String> cmd = new java.util.ArrayList<>(
            List.of("git", "-C", root.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "git timed out: " + String.join(" ", args));
        assertEquals(0, p.exitValue(), "git " + String.join(" ", args) + " failed: " + out);
    }

    private void rebuild() throws Exception {
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());
        String problem = org.jawata.mcp.execution.RunnerClasspath.buildAndCheck(
            service.getJavaProject(), new org.eclipse.core.runtime.NullProgressMonitor());
        assertEquals(null, problem, "fixture must rebuild cleanly");
    }

    private String runWithCoverage(String testClass, String... vmArgs) {
        ObjectNode args = om.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", testClass);
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        args.put("coverage", true);
        if (vmArgs.length > 0) {
            var arr = args.putArray("vmArgs");
            for (String v : vmArgs) arr.add(v);
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "run failed: " + r.getError());
        Map<String, Object> data = cast2(r.getData());
        assertEquals(Boolean.TRUE, cast2(data.get("summary")).get("evidenceFinalized"),
            "run must finalize; got: " + data.get("summary") + " stderr: "
                + data.get("stderrTail"));
        return (String) data.get("coverageArtifactId");
    }

    private Map<String, Object> coverageAction(String action, String artifactId,
            Map<String, Object> extra) {
        ObjectNode args = om.createObjectNode();
        args.put("action", action);
        if (artifactId != null) args.put("artifactId", artifactId);
        extra.forEach((k, v) -> {
            switch (v) {
                case String s -> args.put(k, s);
                case Integer i -> args.put(k, i);
                case Number n -> args.put(k, n.doubleValue());
                case List<?> l -> {
                    var arr = args.putArray(k);
                    for (Object o : l) {
                        if (o instanceof Map<?, ?> m) {
                            var node = arr.addObject();
                            m.forEach((mk, mv) -> node.put(String.valueOf(mk), String.valueOf(mv)));
                        } else {
                            arr.add(String.valueOf(o));
                        }
                    }
                }
                default -> args.put(k, String.valueOf(v));
            }
        });
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), action + " failed: " + r.getError());
        return cast2(r.getData());
    }

    private static double parsePercent(Object percent) {
        return Double.parseDouble(String.valueOf(percent).replace("%", ""));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast2(Object o) {
        return (Map<String, Object>) o;
    }
}
