package org.goja.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.CompileWorkspaceTool;
import org.goja.mcp.tools.SearchSymbolsTool;
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
 * Sprint 22a P0-c — editor/agent scratch copies that land under a source root
 * ({@code src/main/java/.claude/.edit-baks/**}) must be excluded from the
 * linked source entry so they never register as duplicate types.
 *
 * <p>The {@code edit-bak-hygiene} fixture carries a real
 * {@code com.example.Widget} plus a stale copy of it under
 * {@code .claude/.edit-baks/}. Without the exclusion JDT would index two
 * {@code Widget}s — a phantom duplicate in search + call-hierarchy, and a
 * "type already defined" compile error. With it, only the real type exists.</p>
 */
class SourceEntryExclusionTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("edit-bak-hygiene");
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("P0-c: search_symbols finds exactly ONE Widget — the .claude/.edit-baks copy is excluded")
    void search_excludesEditBakDuplicate() {
        SearchSymbolsTool search = new SearchSymbolsTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("query", "Widget");
        args.put("kind", "Class");

        ToolResponse r = search.execute(args);
        assertTrue(r.isSuccess(), "search must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) data(r).get("results");
        List<Map<String, Object>> widgets = results.stream()
            .filter(m -> "com.example.Widget".equals(m.get("qualifiedName")))
            .toList();

        assertEquals(1, widgets.size(),
            "exactly one com.example.Widget must be indexed (the scratch copy excluded); got: " + widgets);
        assertFalse(String.valueOf(widgets.get(0).get("filePath")).contains(".claude"),
            "the surviving Widget must be the real one, not the .claude/.edit-baks copy: " + widgets.get(0));
    }

    @Test
    @DisplayName("P0-c: the project compiles with zero duplicate-type errors (real builder)")
    void compile_hasNoDuplicateTypeError() {
        CompileWorkspaceTool compile = new CompileWorkspaceTool(() -> service);
        ToolResponse r = compile.execute(mapper.createObjectNode());
        assertTrue(r.isSuccess(), "compile must succeed; got: " + r.getError());
        Map<String, Object> d = data(r);
        assertEquals(0, ((Number) d.get("errorCount")).intValue(),
            "the excluded scratch copy must not cause a duplicate-type error; diagnostics=" + d.get("diagnostics"));
    }
}
