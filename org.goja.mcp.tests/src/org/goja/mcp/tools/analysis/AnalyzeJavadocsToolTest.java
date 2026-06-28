package org.goja.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AnalyzeJavadocsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 15a — analyze_javadocs ingest. Drives the simple-maven JavadocTargets
 * fixture: structured tags → HIGH-confidence api_contract; free-text → LOW;
 * @deprecated → its own deprecated_behavior fact; undocumented → no fact.
 */
class AnalyzeJavadocsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private AnalyzeJavadocsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeJavadocsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> ingest() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "ingest");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("ingest", data.get("kind"));
        return (List<Map<String, Object>>) data.get("facts");
    }

    private Map<String, Object> factFor(List<Map<String, Object>> facts, String symbolEndsWith, String type) {
        return facts.stream()
            .filter(f -> type.equals(f.get("type")))
            .filter(f -> String.valueOf(f.get("symbol")).endsWith(symbolEndsWith))
            .findFirst().orElse(null);
    }

    @Test
    @DisplayName("structured method doc → HIGH-confidence api_contract with @param/@return/@throws evidence")
    void structuredContract() {
        List<Map<String, Object>> facts = ingest();
        Map<String, Object> f = factFor(facts, "JavadocTargets#discountedTotal", "api_contract");
        assertNotNull(f, "discountedTotal should yield an api_contract fact: " + facts);
        assertEquals("high", f.get("confidence"));
        @SuppressWarnings("unchecked")
        List<Object> evidence = (List<Object>) f.get("evidence");
        assertNotNull(evidence);
        String joined = evidence.toString();
        assertTrue(joined.contains("@param subtotal"), joined);
        assertTrue(joined.contains("@return"), joined);
        assertTrue(joined.contains("@throws"), joined);
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) f.get("source");
        assertEquals("javadoc", source.get("kind"));
    }

    @Test
    @DisplayName("@deprecated → a separate HIGH-confidence deprecated_behavior fact")
    void deprecatedFact() {
        List<Map<String, Object>> facts = ingest();
        Map<String, Object> f = factFor(facts, "JavadocTargets#oldRound", "deprecated_behavior");
        assertNotNull(f, "oldRound should yield a deprecated_behavior fact: " + facts);
        assertEquals("high", f.get("confidence"));
        assertTrue(String.valueOf(f.get("summary")).startsWith("Deprecated:"));
    }

    @Test
    @DisplayName("free-text-only doc → LOW-confidence fact; undocumented method → no fact")
    void freeTextAndUndocumented() {
        List<Map<String, Object>> facts = ingest();
        Map<String, Object> note = factFor(facts, "JavadocTargets#invariantNote", "domain_fact");
        assertNotNull(note, "invariantNote free-text should yield a domain_fact: " + facts);
        assertEquals("low", note.get("confidence"));

        boolean undocumentedPresent = facts.stream()
            .anyMatch(f -> String.valueOf(f.get("symbol")).endsWith("JavadocTargets#undocumented"));
        assertFalse(undocumentedPresent, "undocumented method must not produce a fact");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> validate() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "validate");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("validate", data.get("kind"));
        return (List<Map<String, Object>>) data.get("findings");
    }

    @Test
    @DisplayName("validate reports missing tags + broken refs on documented members, no getter spam")
    void validateReportsAndDoesNotSpam() {
        List<Map<String, Object>> findings = validate();
        assertFalse(findings.isEmpty(), "expected Javadoc findings");
        assertTrue(findings.stream().allMatch(f -> "JAVADOC".equals(f.get("category"))),
            "all findings are JAVADOC: " + findings);
        String msgs = findings.toString();
        assertTrue(msgs.contains("Missing tag for parameter"),
            "missing-@param should be reported: " + msgs);
        assertTrue(msgs.toLowerCase().contains("cannot be resolved") || msgs.contains("Nonexistent"),
            "broken @see reference should be reported: " + msgs);
        // The design choice: missing-comment detection is OFF → no undocumented-getter spam.
        assertFalse(msgs.contains("Missing comment"),
            "trivial undocumented members must NOT be flagged: " + msgs);
    }

    @Test
    @DisplayName("validate has no global side effect (a later ingest is unaffected)")
    void validateNoSideEffect() {
        validate();
        // Options are parser-scoped, never set on the project — ingest still works.
        Map<String, Object> f = factFor(ingest(), "JavadocTargets#discountedTotal", "api_contract");
        assertNotNull(f, "ingest still works after validate (no global option mutation)");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> generate(String symbol) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "generate");
        if (symbol != null) {
            args.put("symbol", symbol);
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("generate: doclint skeleton + evidence for a method, no model-written prose")
    void generateMethodSkeleton() {
        Map<String, Object> d = generate("com.example.JavadocTargets#discountedTotal");
        assertEquals(Boolean.FALSE, d.get("skip"));
        assertEquals("method", d.get("targetKind"));
        String skeleton = (String) d.get("skeleton");
        assertTrue(skeleton.contains("@param subtotal TODO"), skeleton);
        assertTrue(skeleton.contains("@param rate TODO"), skeleton);
        assertTrue(skeleton.contains("@return TODO"), skeleton);
        assertTrue(skeleton.contains("@throws IllegalArgumentException TODO"), skeleton);
        // model-free: every described slot is a TODO placeholder, never prose.
        assertFalse(skeleton.toLowerCase().contains("discount rate"), "no fabricated prose: " + skeleton);

        @SuppressWarnings("unchecked")
        List<String> placeholders = (List<String>) d.get("prosePlaceholders");
        assertTrue(placeholders.containsAll(List.of("summary", "@param subtotal", "@param rate",
            "@return", "@throws IllegalArgumentException")), placeholders.toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) d.get("evidence");
        assertEquals("public", evidence.get("visibility"));
        assertEquals("double", evidence.get("returns"));
        assertEquals(List.of("IllegalArgumentException"), evidence.get("throws"));
    }

    @Test
    @DisplayName("generate: trivial getter → skip:true")
    void generateSkipsTrivialAccessor() {
        Map<String, Object> d = generate("com.example.JavadocTargets#getName");
        assertEquals(Boolean.TRUE, d.get("skip"));
        assertFalse(d.containsKey("skeleton"), "skipped target emits no skeleton");
    }

    @Test
    @DisplayName("generate: type target → type skeleton")
    void generateTypeSkeleton() {
        Map<String, Object> d = generate("com.example.JavadocTargets");
        assertEquals("type", d.get("targetKind"));
        assertEquals(Boolean.FALSE, d.get("skip"));
        assertTrue(((String) d.get("skeleton")).contains("TODO: summarize JavadocTargets"));
    }

    @Test
    @DisplayName("generate: missing symbol rejected; unknown symbol not found")
    void generateBadInputs() {
        ToolResponse missing = tool.execute(objectMapper.createObjectNode().put("kind", "generate"));
        assertFalse(missing.isSuccess());
        assertEquals("INVALID_PARAMETER", missing.getError().getCode());

        ToolResponse unknown = tool.execute(objectMapper.createObjectNode()
            .put("kind", "generate").put("symbol", "com.example.NoSuchType#nope"));
        assertFalse(unknown.isSuccess());
    }

    @Test
    @DisplayName("unknown kind is rejected")
    void unknownKind() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "no_such_kind");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());
    }
}
