package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 25 (spec D3a) — javadoc_lack: undocumented public API, exact fixture
 * counts (com.example.JavadocLackTargets encodes the whole contract: exported
 * chain, implicit interface visibility, @Override and trivial-accessor skips,
 * enum constants).
 */
class JavadocLackDetectorTest {

    private static final String FIXTURE = "JavadocLackTargets.java";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> run(ObjectNode args) {
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "refused: " + (r.getError() != null
            ? r.getError().getCode() + " / " + r.getError().getMessage() : "?"));
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findings(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("findings");
    }

    private ObjectNode kindArgs() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "javadoc_lack");
        return args;
    }

    @Test
    @DisplayName("exact fixture counts: the 8 undocumented public-API members, nothing else")
    void exactFixtureCounts() {
        ObjectNode args = kindArgs();
        args.put("filePath", "src/main/java/com/example/" + FIXTURE);
        Map<String, Object> data = run(args);

        Set<String> symbols = findings(data).stream()
            .map(f -> String.valueOf(f.get("symbol")))
            .collect(Collectors.toSet());

        Set<String> expected = Set.of(
            "JavadocLackTargets#JavadocLackTargets",
            "JavadocLackTargets#undocumentedField",
            "JavadocLackTargets#undocumentedMethod",
            "JavadocLackTargets#protectedUndoc",
            "JavadocLackTargets.DocumentedNested#nestedUndoc",
            "JavadocLackTargets.NestedApi",
            "JavadocLackTargets.NestedApi#implicitlyPublic",
            "JavadocLackTargets.Level#HIGH");
        assertEquals(expected, symbols, "the enumeration must be EXACT");
        assertEquals(8, findings(data).size());

        // The deliberate skips must hold (they are inside `expected` by absence,
        // but name the load-bearing ones explicitly):
        assertFalse(symbols.contains("JavadocLackTargets#getUndocumentedField"),
            "trivial getter must be skipped");
        assertFalse(symbols.contains("JavadocLackTargets#setUndocumentedField"),
            "trivial setter must be skipped");
        assertFalse(symbols.contains("JavadocLackTargets#toString"),
            "@Override method must be skipped (doc inherited)");
        assertFalse(symbols.stream().anyMatch(s -> s.contains("PackageNested")),
            "a non-exported enclosing chain is not public API");
    }

    @Test
    @DisplayName("the quality family sweep carries javadoc_lack")
    void familySweepCarriesIt() {
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "quality");
        args.put("filePath", "src/main/java/com/example/" + FIXTURE);
        Map<String, Object> data = run(args);

        boolean present = findings(data).stream()
            .anyMatch(f -> "javadoc_lack".equals(String.valueOf(f.get("kind"))));
        assertTrue(present, "family=quality must include javadoc_lack findings");
    }

    @Test
    @DisplayName("excludePaths drops the fixture's findings before counting")
    void excludePathsRespected() {
        ObjectNode args = kindArgs();
        args.put("filePath", "src/main/java/com/example/" + FIXTURE);
        args.putArray("excludePaths").add(FIXTURE);
        Map<String, Object> data = run(args);

        assertEquals(0, findings(data).size(),
            "excludePaths must drop the fixture's findings: " + findings(data));
    }
}
