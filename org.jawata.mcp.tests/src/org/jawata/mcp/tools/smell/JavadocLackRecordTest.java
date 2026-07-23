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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jawata-mcp#8 regression: a fully documented {@code record} must report ZERO
 * javadoc_lack findings. JDT's comment mapper does not attach doc comments to a
 * record's own type node or (as observed) to every second field, so
 * {@code getJavadoc()} lies; the detector's source-grounded fallback must see
 * the {@code /**} that is right there on disk.
 *
 * <p>Two shapes, both from the field report: {@code RecordParamDoc} mirrors
 * {@code EmbedderIdentity} (type Javadoc documents the components with
 * {@code @param} + inline tags — the false-positive case), {@code RecordPlainDoc}
 * mirrors {@code StoredEntry} (no {@code @param} — the control). Both are fully
 * documented, so both must be clean.</p>
 */
class JavadocLackRecordTest {

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
    private List<Map<String, Object>> findingsFor(String fixture) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "javadoc_lack");
        args.put("filePath", "src/main/java/com/example/" + fixture);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "refused: " + (r.getError() != null ? r.getError().getMessage() : "?"));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return (List<Map<String, Object>>) data.get("findings");
    }

    @Test
    @DisplayName("a fully documented record with @param type Javadoc is clean (EmbedderIdentity shape)")
    void paramDocRecordIsClean() {
        List<Map<String, Object>> findings = findingsFor("RecordParamDoc.java");
        assertEquals(0, findings.size(),
            "the record type and every constant carry Javadoc: " + findings);
    }

    @Test
    @DisplayName("a fully documented record without @param is clean (StoredEntry shape control)")
    void plainDocRecordIsClean() {
        List<Map<String, Object>> findings = findingsFor("RecordPlainDoc.java");
        assertEquals(0, findings.size(),
            "the record type and every constant carry Javadoc: " + findings);
    }
}
