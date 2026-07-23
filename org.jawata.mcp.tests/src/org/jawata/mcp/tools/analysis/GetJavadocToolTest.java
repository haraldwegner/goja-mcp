package org.jawata.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.GetJavadocTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetJavadocToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetJavadocTool tool;
    private ObjectMapper objectMapper;
    private String calculatorPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetJavadocTool(() -> service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("parses javadoc with complete response including tags")
    void parsesJavadocComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);  // add method with javadoc
        args.put("column", 15);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("symbol"));
        assertNotNull(data.get("kind"));
        assertNotNull(data.get("hasDocumentation"));

        if ((Boolean) data.get("hasDocumentation")) {
            assertNotNull(data.get("summary"));
            // May have @param, @return, @throws depending on the method
        }
    }

    @Test @DisplayName("returns hasDocumentation false for undocumented symbol")
    void handlesUndocumentedSymbol() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 10);  // Class declaration (may not have javadoc)
        args.put("column", 13);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        assertNotNull(data.get("hasDocumentation"));
    }

    @Test
    @DisplayName("jawata-mcp#8: inline tags render to plain text; nothing truncates at '{'")
    @SuppressWarnings("unchecked")
    void inlineTagsAreRenderedNotTruncated() throws Exception {
        Path projectPath = helper.getFixturePath("simple-maven");
        String recordPath = projectPath
            .resolve("src/main/java/com/example/RecordParamDoc.java").toString();

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", recordPath);
        args.put("line", 17);    // 0-based: `public record RecordParamDoc(...)`
        args.put("column", 16);  // on the type name

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "refused: " + (r.getError() != null ? r.getError().getMessage() : "?"));
        Map<String, Object> data = getData(r);
        assertEquals(Boolean.TRUE, data.get("hasDocumentation"));

        String summary = (String) data.get("summary");
        assertNotNull(summary);
        assertFalse(summary.contains("{"),
            "the summary must not be cut off at an inline tag: " + summary);
        // The {@link #current} at the end rendered to plain text and the sentence survived.
        assertTrue(summary.contains("current exists"),
            "the tail after the inline tag must survive: " + summary);

        List<Map<String, String>> params = (List<Map<String, String>>) data.get("params");
        assertNotNull(params, "the @param tags must parse");
        String modelDesc = params.stream()
            .filter(p -> "model".equals(p.get("name")))
            .map(p -> p.get("description")).findFirst().orElse("");
        assertFalse(modelDesc.contains("{"),
            "the @param description must not be cut off at {@code}: " + modelDesc);
        assertTrue(modelDesc.contains("sentence-transformers/all-MiniLM-L6-v2"),
            "the {@code} content must render into the description: " + modelDesc);
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 14);
        noFile.put("column", 15);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", calculatorPath);
        noLine.put("column", 15);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("handles non-member position gracefully")
    void handlesNonMemberPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 0);  // Package declaration
        args.put("column", 0);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }
}
