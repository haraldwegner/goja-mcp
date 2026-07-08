package org.goja.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindStringLiteralsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a P2-a — find_string_literals locates the emitter of a string
 * literal (here {@code "Hello, "} in the compile-clean fixture's Clean.java)
 * that a symbol search cannot.
 */
class FindStringLiteralsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindStringLiteralsTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("compile-clean");
        tool = new FindStringLiteralsTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> matches(ToolResponse r) {
        assertTrue(r.isSuccess(), "got: " + r.getError());
        return (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("matches");
    }

    private ToolResponse run(String query, boolean regex) {
        ObjectNode args = mapper.createObjectNode();
        args.put("query", query);
        if (regex) {
            args.put("regex", true);
        }
        return tool.execute(args);
    }

    @Test
    @DisplayName("substring: finds the file:line that emits a literal")
    void substring_findsEmitter() {
        List<Map<String, Object>> ms = matches(run("Hello, ", false));
        assertFalse(ms.isEmpty(), "the 'Hello, ' literal must be found");
        boolean inClean = ms.stream().anyMatch(m ->
            String.valueOf(m.get("filePath")).endsWith("Clean.java")
                && String.valueOf(m.get("literal")).contains("Hello, "));
        assertTrue(inClean, "a match must point at Clean.java with the literal: " + ms);
    }

    @Test
    @DisplayName("regex: a Java regex matches literal values")
    void regex_matches() {
        assertFalse(matches(run("Hello.*", true)).isEmpty(), "regex must match the greeting literal");
    }

    @Test
    @DisplayName("no match: an absent literal returns zero matches")
    void noMatch_returnsEmpty() {
        assertEquals(0, matches(run("zzz-no-such-literal-anywhere", false)).size());
    }

    @Test
    @DisplayName("validation: missing query is rejected")
    void validation_missingQuery() {
        assertFalse(tool.execute(mapper.createObjectNode()).isSuccess());
    }
}
