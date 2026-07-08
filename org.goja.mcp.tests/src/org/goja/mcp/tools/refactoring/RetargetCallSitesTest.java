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
 * Sprint 22a P1-a.3 — the {@code retargetCallsTo} mode on
 * {@code change_method_signature}: rewrite a method's call sites to invoke a
 * different (existing) method, leaving the declaration untouched.
 */
class RetargetCallSitesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ChangeMethodSignatureTool tool;
    private ObjectMapper mapper;
    private Path service_;
    private Path client;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("retarget");
        tool = new ChangeMethodSignatureTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        mapper = new ObjectMapper();
        service_ = service.getProjectRoot().resolve("src/main/java/com/example/Service.java");
        client = service.getProjectRoot().resolve("src/main/java/com/example/Client.java");
    }

    /** Zero-based [line, column] of {@code needle}, searched after {@code anchor}. */
    private static int[] posAfter(String text, String anchor, String needle) {
        int from = text.indexOf(anchor);
        int idx = text.indexOf(needle, from);
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

    private int compileErrors() {
        ToolResponse cr = new CompileWorkspaceTool(() -> service).execute(mapper.createObjectNode());
        assertTrue(cr.isSuccess(), "compile failed: " + cr.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> d = (Map<String, Object>) cr.getData();
        return ((Number) d.get("errorCount")).intValue();
    }

    @Test
    @DisplayName("retarget: both v1() call sites redirect to v2(), the declaration stays, project compiles")
    void retargetsCallSites_leavesDeclaration_compiles() throws Exception {
        int[] pos = posAfter(Files.readString(service_), "class Service", "v1(");

        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", service_.toString());
        args.put("line", pos[0]);
        args.put("column", pos[1]);
        args.put("retargetCallsTo", "v2");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "retarget must succeed; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"), "an undo handle must be returned");
        assertEquals("v2", data.get("retargetedTo"));

        String clientAfter = Files.readString(client);
        // Check the invocation form (.v1() ); the javadoc's {@link #v1(String)} is not a call.
        assertFalse(clientAfter.contains(".v1("), "no v1 call site may remain:\n" + clientAfter);
        assertEquals(2, clientAfter.split("\\bv2\\(").length - 1,
            "both call sites must now target v2:\n" + clientAfter);

        String serviceAfter = Files.readString(service_);
        assertTrue(serviceAfter.contains("String v1("),
            "the v1 declaration must be left untouched:\n" + serviceAfter);

        assertEquals(0, compileErrors(), "retarget to an existing method must stay compiling");
    }

    @Test
    @DisplayName("validation: retargetCallsTo cannot be combined with a signature change")
    void validation_retargetExclusiveWithSignatureChange() throws Exception {
        int[] pos = posAfter(Files.readString(service_), "class Service", "v1(");
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", service_.toString());
        args.put("line", pos[0]);
        args.put("column", pos[1]);
        args.put("retargetCallsTo", "v2");
        args.put("newName", "v9");
        assertFalse(tool.execute(args).isSuccess(),
            "combining retargetCallsTo with newName must be rejected");
    }
}
