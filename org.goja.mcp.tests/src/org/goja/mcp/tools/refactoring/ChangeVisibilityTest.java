package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.ChangeMethodSignatureTool;
import org.goja.mcp.tools.CompileWorkspaceTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a P1-a.2 — the {@code visibility} mode on {@code change_method_signature}
 * against the {@code visibility} fixture: reduce (public→package), widen
 * (package→public), replace (public→private); each stays compiling and the
 * reduce reports the reference-impact list.
 */
class ChangeVisibilityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ChangeMethodSignatureTool tool;
    private ObjectMapper mapper;
    private Path widget;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("visibility");
        tool = new ChangeMethodSignatureTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        mapper = new ObjectMapper();
        widget = service.getProjectRoot().resolve("src/main/java/com/example/Widget.java");
    }

    /** Zero-based [line, column] of {@code methodName + "("} in the class body (skips javadoc). */
    private static int[] declPosOf(String text, String methodName) {
        int classAt = text.indexOf("class Widget");
        int idx = text.indexOf(methodName + "(", classAt);
        int line = 0, col = 0;
        for (int i = 0; i < idx; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 0;
            } else {
                col++;
            }
        }
        return new int[]{line, col};
    }

    private ToolResponse changeVisibility(String methodName, String visibility) throws Exception {
        int[] pos = declPosOf(Files.readString(widget), methodName);
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", widget.toString());
        args.put("line", pos[0]);
        args.put("column", pos[1]);
        args.put("visibility", visibility);
        return tool.execute(args);
    }

    private int compileErrors() {
        ToolResponse cr = new CompileWorkspaceTool(() -> service).execute(mapper.createObjectNode());
        assertTrue(cr.isSuccess(), "compile failed: " + cr.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) cr.getData();
        return ((Number) d.get("errorCount")).intValue();
    }

    @Test
    @DisplayName("reduce: public open() -> package removes the modifier, reports impact, stays compiling")
    void reduceToPackage() throws Exception {
        ToolResponse r = changeVisibility("open", "package");
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"), "an undo handle must be returned");

        @SuppressWarnings("unchecked")
        Map<String, Object> impact = (Map<String, Object>) data.get("referenceImpact");
        assertNotNull(impact, "the visibility mode must report a reference-impact list");
        assertTrue(((Number) impact.get("count")).intValue() >= 1,
            "at least the same-package call site must be reported; impact=" + impact);

        String after = Files.readString(widget);
        assertTrue(after.contains("String open("), "open must remain: " + after);
        assertFalse(after.contains("public String open("),
            "the public modifier must be removed:\n" + after);
        assertEquals(0, compileErrors(), "in-package reduce must still compile");
    }

    @Test
    @DisplayName("widen: package hidden() -> public inserts the modifier and stays compiling")
    void widenToPublic() throws Exception {
        ToolResponse r = changeVisibility("hidden", "public");
        assertTrue(r.isSuccess(), "got: " + r.getError());
        String after = Files.readString(widget);
        assertTrue(after.contains("public int hidden("),
            "hidden must become public:\n" + after);
        assertEquals(0, compileErrors());
    }

    @Test
    @DisplayName("replace: public solo() -> private swaps the modifier and stays compiling")
    void reduceToPrivate() throws Exception {
        ToolResponse r = changeVisibility("solo", "private");
        assertTrue(r.isSuccess(), "got: " + r.getError());
        String after = Files.readString(widget);
        assertTrue(after.contains("private int solo("),
            "solo must become private:\n" + after);
        assertEquals(0, compileErrors());
    }

    @Test
    @DisplayName("validation: an unknown visibility value is rejected")
    void validation_badVisibility() throws Exception {
        assertFalse(changeVisibility("open", "frobnicate").isSuccess());
    }
}
