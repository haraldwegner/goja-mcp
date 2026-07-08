package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.RefactoringTool;
import org.goja.mcp.tools.UndoRefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 18 — refactoring(action=apply_plan): the gated multi-step loop. Fixture
 * com.example.ComposeMethodTargets ({@code ReportBuilder.build()} — header section
 * at 0-based 13, footer at 0-based 18, startColumn 8 / endColumn 44).
 */
class RefactorPlanApplyToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RefactoringTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper mapper;
    private Path targetFile;

    @BeforeEach
    void setUp() throws Exception {
        // compile-clean fixture: apply_plan parity-gates on absolute 0/0, and post
        // Sprint 22a P0-a simple-maven no longer compiles clean (the now-firing builder
        // surfaces its deliberately-unresolvable imports). compose-clean holds only the
        // compilable ComposeMethodTargets, verbatim — so the coordinates carry over.
        JdtServiceImpl service = helper.loadProjectCopy("compose-clean");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new RefactoringTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        mapper = new ObjectMapper();
        targetFile = helper.getTempDirectory()
            .resolve("compose-clean/src/main/java/com/example/ComposeMethodTargets.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode section(int startLine, int endLine, String name) {
        ObjectNode s = mapper.createObjectNode();
        s.put("startLine", startLine);
        s.put("startColumn", 8);
        s.put("endLine", endLine);
        s.put("endColumn", 44);
        s.put("methodName", name);
        return s;
    }

    private String planCompose(ObjectNode... sections) {
        ObjectNode a = mapper.createObjectNode();
        a.put("action", "plan");
        a.put("kind", "compose_method");
        a.put("filePath", targetFile.toString());
        ArrayNode arr = a.putArray("sections");
        for (ObjectNode s : sections) {
            arr.add(s);
        }
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> "plan failed: " + r.getError());
        return (String) getData(r).get("planId");
    }

    private ToolResponse applyPlan(String planId) {
        ObjectNode a = mapper.createObjectNode();
        a.put("action", "apply_plan");
        a.put("planId", planId);
        return tool.execute(a);
    }

    @Test
    @DisplayName("compose_method applies both steps parity-gated, surfaces purity findings, and undo restores")
    void appliesGated_surfacesPurity_andUndoRestores() throws Exception {
        String original = Files.readString(targetFile);
        String planId = planCompose(section(13, 13, "writeHeader"), section(18, 18, "writeFooter"));

        ToolResponse r = applyPlan(planId);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        Map<String, Object> d = getData(r);
        assertEquals(Boolean.TRUE, d.get("applied"));
        assertEquals(2, d.get("stepsRun"));
        assertNotNull(d.get("undoChangeId"));

        // The purity check ran on build() each step; extracting a section repoints its calls,
        // so CHANGED_OUTBOUND_CALLS is surfaced (advisory) — but nothing halts the pure refactor.
        List<?> findings = (List<?>) d.get("purityFindings");
        assertFalse(findings.isEmpty(), "purity findings should be surfaced for the composed refactor");

        String onDisk = Files.readString(targetFile);
        assertTrue(onDisk.contains("writeHeader()") && onDisk.contains("writeFooter()"),
            "build() should call both extracted methods:\n" + onDisk);
        assertTrue(onDisk.contains("void writeHeader()") && onDisk.contains("void writeFooter()"),
            "both methods created:\n" + onDisk);

        ToolResponse undone = undoTool.execute(
            mapper.createObjectNode().put("undoChangeId", (String) d.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(targetFile), "the composed undo must restore the original byte-for-byte");
    }

    @Test
    @DisplayName("a step that fails mid-plan rolls the whole plan back atomically (byte-identical)")
    void forcedFailure_rollsBackAtomically() throws Exception {
        String original = Files.readString(targetFile);
        // Bottom-up order runs footer@18 first (succeeds), then the bogus @8 (the class
        // declaration line — not extractable) which fails and must unwind the footer extraction.
        String planId = planCompose(section(18, 18, "writeFooter"), section(8, 8, "bogus"));

        ToolResponse r = applyPlan(planId);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError())); // safe-fail is a successful outcome
        Map<String, Object> d = getData(r);
        assertEquals(Boolean.FALSE, d.get("applied"));
        assertEquals(Boolean.TRUE, d.get("rolledBack"));

        assertEquals(original, Files.readString(targetFile),
            "a failed plan must leave the workspace byte-identical to pre-plan");
    }
}
