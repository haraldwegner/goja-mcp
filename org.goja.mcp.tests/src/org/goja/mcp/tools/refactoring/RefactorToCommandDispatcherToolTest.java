package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.RefactorToPatternTool;
import org.goja.mcp.tools.UndoRefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 19 — refactor_to_command_dispatcher via refactor_to_pattern. Fixture
 * CommandTargets: Calculator.apply(int op) switches at 0-based 23:8. Structure +
 * real compile check + undo.
 */
class RefactorToCommandDispatcherToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactorToPatternTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper mapper;
    private Path targetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new RefactorToPatternTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        mapper = new ObjectMapper();
        targetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/CommandTargets.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode args(int line, int column) {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "refactor_to_command_dispatcher");
        n.put("filePath", targetFile.toString());
        n.put("line", line);
        n.put("column", column);
        return n;
    }

    private long compileErrors() throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(targetFile);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        return Arrays.stream(ast.getProblems()).filter(IProblem::isError).count();
    }

    @Test
    @DisplayName("lifts case bodies to Command classes + switch-expression dispatch; compiles; undo restores")
    void refactorToCommand_appliesCompilesAndUndoRestores() throws Exception {
        String original = Files.readString(targetFile);

        ToolResponse response = tool.execute(args(23, 8)); // switch (op)
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("CalculatorCommand", data.get("commandInterface"));
        assertNotNull(data.get("undoChangeId"));

        String onDisk = Files.readString(targetFile);
        assertTrue(onDisk.contains("interface CalculatorCommand"), "command interface:\n" + onDisk);
        assertTrue(onDisk.contains("class AddCommand implements CalculatorCommand"), "AddCommand:\n" + onDisk);
        assertTrue(onDisk.contains("class DefaultCommand implements CalculatorCommand"), "DefaultCommand:\n" + onDisk);
        assertTrue(onDisk.contains("CalculatorCommand __command = switch (op)"), "switch-expression dispatch:\n" + onDisk);
        assertTrue(onDisk.contains("case ADD -> new AddCommand()"), "ADD arm:\n" + onDisk);
        assertTrue(onDisk.contains("default -> new DefaultCommand()"), "default arm:\n" + onDisk);
        assertTrue(onDisk.contains("__command.execute();"), "executes selected command:\n" + onDisk);

        assertEquals(0, compileErrors(), "refactored code must compile (0 ERROR problems):\n" + onDisk);

        ToolResponse undone = undoTool.execute(
            mapper.createObjectNode().put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(targetFile), "undo must restore the original byte-for-byte");
    }

    @Test
    @DisplayName("a non-switch position is rejected without touching disk")
    void rejectsNonSwitch() throws Exception {
        String original = Files.readString(targetFile);
        ToolResponse response = tool.execute(args(13, 8)); // a constant, not a switch
        assertFalse(response.isSuccess());
        assertEquals(original, Files.readString(targetFile));
    }
}
