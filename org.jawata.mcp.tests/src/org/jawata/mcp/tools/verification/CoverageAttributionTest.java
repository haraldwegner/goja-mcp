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

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 23 (D4, C9) — who-tests-what on per-test segments: exact
 * tests_covering both directions, focused-first ranking with kinds labeled,
 * impacted-test selection, cross-module attribution, and the honest
 * "unavailable" answer.
 */
class CoverageAttributionTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ObjectMapper om;
    private String covDirBefore;

    @BeforeEach
    void setUp() throws Exception {
        covDirBefore = System.getProperty("jawata.coverage.dir");
        System.setProperty("jawata.coverage.dir",
            Files.createTempDirectory("jawata-cov-store-").toString());
        om = new ObjectMapper();
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
    @DisplayName("C9: exact both directions, unit ranked before integration, impacted minimal, honest unavailable")
    void attribution_exactRankedMinimalHonest() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("coverage-target");
        RunTestsTool tool = new RunTestsTool(() -> service);

        // Honest "unavailable" BEFORE any attribution run exists.
        Map<String, Object> unavailable = action(tool, "coverage_tests_covering",
            Map.of("target", "com.example.cov.Covered#alwaysCalled"));
        assertEquals(Boolean.FALSE, unavailable.get("attributionAvailable"),
            "must never guess; got: " + unavailable);

        // Attribution run 1 (unit): CoveredTest.
        String unitArtifact = runAttribution(tool, "com.example.cov.CoveredTest", "unit");
        // Attribution run 2 (integration): IntegrationishTest also covers alwaysCalled.
        runAttribution(tool, "com.example.cov.IntegrationishTest", "integration");

        // tests_covering(alwaysCalled): BOTH tests, unit first, kinds labeled.
        Map<String, Object> covering = action(tool, "coverage_tests_covering",
            Map.of("target", "com.example.cov.Covered#alwaysCalled"));
        List<Map<String, Object>> coveredBy = cast(covering.get("coveredBy"));
        assertEquals(2, coveredBy.size(), "got: " + covering);
        assertEquals("unit", coveredBy.get(0).get("evidenceKind"),
            "unit evidence must rank FIRST; got: " + coveredBy);
        assertTrue(String.valueOf(coveredBy.get(0).get("test")).contains("CoveredTest"),
            "got: " + coveredBy);
        assertEquals("integration", coveredBy.get(1).get("evidenceKind"), "got: " + coveredBy);

        // Exactness the other way: neverCalled is covered by NOBODY.
        Map<String, Object> nobody = action(tool, "coverage_tests_covering",
            Map.of("target", "com.example.cov.Covered#neverCalled"));
        assertEquals(0, cast(nobody.get("coveredBy")).size(), "got: " + nobody);

        // coverage_of_test: the unit test's segment names what it exercised
        // (artifact-scoped by design — the honest not-found note lists the
        // artifact's known tests when misaddressed).
        Map<String, Object> ofTest = action(tool, "coverage_of_test", Map.of(
            "artifactId", unitArtifact,
            "test", "com.example.cov.CoveredTest#exercisesAlwaysCalledAndOneBranchArm"));
        String covers = String.valueOf(ofTest.get("covers"));
        assertTrue(covers.contains("com.example.cov.Covered#alwaysCalled"), "got: " + ofTest);
        assertFalse(covers.contains("com.example.cov.Covered#neverCalled"), "got: " + ofTest);

        // impacted_tests for an explicit changed symbol: minimal, ranked.
        Map<String, Object> impacted = action(tool, "coverage_impacted_tests",
            Map.of("symbols", List.of("com.example.cov.Covered#branchy")));
        List<Map<String, Object>> tests = cast(impacted.get("impactedTests"));
        assertEquals(2, tests.size(), "both tests exercise branchy; got: " + impacted);
        assertEquals("unit", tests.get(0).get("evidenceKind"), "got: " + tests);
        // ... and a symbol nobody covers yields an empty set, not a guess.
        Map<String, Object> none = action(tool, "coverage_impacted_tests",
            Map.of("symbols", List.of("com.example.cov.NeverLoaded#ghost")));
        assertEquals(0, cast(none.get("impactedTests")).size(), "got: " + none);
    }

    @Test
    @DisplayName("C9 cross-module: module-b tests attributed to module-a symbols")
    void attribution_crossModule() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("reactor-cross");
        RunTestsTool tool = new RunTestsTool(() -> service);
        runAttribution(tool, "com.example.cross.CrossModuleTest", "unit");

        Map<String, Object> covering = action(tool, "coverage_tests_covering",
            Map.of("target", "com.example.alpha.AlphaLib#magic"));
        List<Map<String, Object>> coveredBy = cast(covering.get("coveredBy"));
        assertEquals(1, coveredBy.size(), "got: " + covering);
        assertTrue(String.valueOf(coveredBy.get(0).get("test"))
                .contains("CrossModuleTest#magicComesFromModuleA"),
            "B-tests-cover-A must attribute exactly; got: " + coveredBy);
    }

    private String runAttribution(RunTestsTool tool, String testClass, String kind) {
        ObjectNode args = om.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", testClass);
        args.put("framework", "junit5");
        args.put("timeoutSeconds", 120);
        args.put("attribution", true);
        args.put("evidenceKind", kind);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "attribution run failed: " + r.getError());
        Map<String, Object> data = cast2(r.getData());
        assertEquals(Boolean.TRUE, cast2(data.get("summary")).get("evidenceFinalized"),
            "got: " + data.get("summary") + " stderr: " + data.get("stderrTail"));
        return (String) data.get("coverageArtifactId");
    }

    private Map<String, Object> action(RunTestsTool tool, String action,
            Map<String, Object> extra) {
        ObjectNode args = om.createObjectNode();
        args.put("action", action);
        extra.forEach((k, v) -> {
            if (v instanceof String s) {
                args.put(k, s);
            } else if (v instanceof List<?> l) {
                var arr = args.putArray(k);
                l.forEach(o -> arr.add(String.valueOf(o)));
            }
        });
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), action + " failed: " + r.getError());
        return cast2(r.getData());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cast(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast2(Object o) {
        return (Map<String, Object>) o;
    }
}
