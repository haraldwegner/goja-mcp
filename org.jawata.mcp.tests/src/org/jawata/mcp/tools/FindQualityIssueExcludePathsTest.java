package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.8.1 dogfood fix (found live 2026-07-11): a fowler family sweep on
 * jawata-mcp itself was flooded by the vendored 12k-line Eclipse
 * ProblemReporter copy in org.jawata.jdtpatch — correct findings, useless
 * signal. Contract: {@code excludePaths} (array of path substrings) drops
 * findings AND conflicts whose filePath contains any entry, BEFORE counts,
 * summary, baseline and pagination are computed.
 */
class FindQualityIssueExcludePathsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper mapper;
    private FindQualityIssueTool tool;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        mapper = new ObjectMapper();
        tool = new FindQualityIssueTool(() -> service);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> runFamily(String... excludes) {
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "fowler");
        args.put("limit", 1000);
        if (excludes.length > 0) {
            var arr = args.putArray("excludePaths");
            for (String e : excludes) arr.add(e);
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "sweep must succeed; got: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("family sweep: excludePaths drops matching findings and shrinks the count")
    void excludePathsFiltersFamilySweep() {
        Map<String, Object> full = runFamily();
        int fullCount = ((Number) full.get("count")).intValue();
        List<Map<String, Object>> fullFindings = (List<Map<String, Object>>) full.get("findings");
        long fromTargetFile = fullFindings.stream()
            .filter(f -> String.valueOf(f.get("filePath")).contains("PrimitiveObsession")).count();
        assertTrue(fromTargetFile > 0,
            "fixture must produce findings in PrimitiveObsessionTargets; got " + fullCount + " total");

        Map<String, Object> filtered = runFamily("PrimitiveObsession");
        int filteredCount = ((Number) filtered.get("count")).intValue();
        assertEquals(fullCount - fromTargetFile, filteredCount,
            "count must reflect the filtered set");
        List<Map<String, Object>> findings = (List<Map<String, Object>>) filtered.get("findings");
        assertTrue(findings.stream()
                .noneMatch(f -> String.valueOf(f.get("filePath")).contains("PrimitiveObsession")),
            "no finding from the excluded path may survive");
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) filtered.get("conflicts");
        assertTrue(conflicts.stream()
                .noneMatch(c -> String.valueOf(c.get("filePath")).contains("PrimitiveObsession")),
            "no conflict at the excluded path may survive");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("single kind: excludePaths applies to a plain kind run too")
    void excludePathsFiltersSingleKind() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "primitive_obsession");
        var arr = args.putArray("excludePaths");
        arr.add("PrimitiveObsession");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "kind run must succeed; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        assertTrue(findings.stream()
                .noneMatch(f -> String.valueOf(f.get("filePath")).contains("PrimitiveObsession")),
            "excluded path must not appear in single-kind findings; got: " + findings);
    }
}
