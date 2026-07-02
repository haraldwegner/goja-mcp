package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import org.goja.mcp.tools.ExtractTool;
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
 * Sprint 18 — extract(kind=superclass). Fixtures com.example.GreetFormal +
 * GreetCasual share an identical self-contained punctuation(); the caret is on
 * GreetFormal at 0-based 3:13. Cross-file (parent create + 2 subclass edits) +
 * real compile check + undo.
 */
class ExtractSuperclassToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ExtractTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper mapper;
    private Path pkgDir;
    private Path formalFile;
    private Path casualFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new ExtractTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        mapper = new ObjectMapper();
        pkgDir = helper.getTempDirectory().resolve("simple-maven/src/main/java/com/example");
        formalFile = pkgDir.resolve("GreetFormal.java");
        casualFile = pkgDir.resolve("GreetCasual.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode args(String superclassName, String... siblings) {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "superclass");
        n.put("filePath", formalFile.toString());
        n.put("line", 3);
        n.put("column", 13);
        n.put("superclassName", superclassName);
        ArrayNode sibs = n.putArray("siblings");
        for (String s : siblings) {
            sibs.add(s);
        }
        return n;
    }

    private long compileErrors(Path file) throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(file);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        return Arrays.stream(ast.getProblems()).filter(IProblem::isError).count();
    }

    @Test
    @DisplayName("creates the parent, pulls up the shared method, reparents both; compiles; undo restores")
    void extractsSuperclass_compiles_andUndoRestores() throws Exception {
        String formalOrig = Files.readString(formalFile);
        String casualOrig = Files.readString(casualFile);
        Path parentFile = pkgDir.resolve("Greeter.java");
        assertFalse(Files.exists(parentFile), "precondition: parent absent");

        ToolResponse response = tool.execute(args("Greeter", "GreetCasual"));
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("Greeter", data.get("newSuperclass"));
        assertNotNull(data.get("undoChangeId"));

        assertTrue(Files.exists(parentFile), "the parent file should be created");
        String parent = Files.readString(parentFile);
        assertTrue(parent.contains("public abstract class Greeter {"), "abstract parent:\n" + parent);
        assertTrue(parent.contains("public String punctuation() {"), "pulled-up method:\n" + parent);
        assertTrue(parent.contains("return \".\";"), "method body carried verbatim:\n" + parent);

        String formal = Files.readString(formalFile);
        assertTrue(formal.contains("class GreetFormal extends Greeter"), "reparented:\n" + formal);
        assertFalse(formal.contains("public String punctuation() {"), "pulled method removed from subclass:\n" + formal);
        assertTrue(formal.contains("greet(String name)"), "non-shared method stays:\n" + formal);

        String casual = Files.readString(casualFile);
        assertTrue(casual.contains("class GreetCasual extends Greeter"), "sibling reparented:\n" + casual);
        assertFalse(casual.contains("public String punctuation() {"), "pulled method removed from sibling:\n" + casual);

        // Both subclasses compile: they extend the new Greeter and call the inherited punctuation().
        assertEquals(0, compileErrors(formalFile), "GreetFormal must compile:\n" + formal);
        assertEquals(0, compileErrors(casualFile), "GreetCasual must compile:\n" + casual);

        ToolResponse undone = undoTool.execute(
            mapper.createObjectNode().put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertFalse(Files.exists(parentFile), "undo must delete the parent");
        assertEquals(formalOrig, Files.readString(formalFile), "undo must restore GreetFormal");
        assertEquals(casualOrig, Files.readString(casualFile), "undo must restore GreetCasual");
    }

    @Test
    @DisplayName("missing superclassName is rejected")
    void rejectsMissingName() {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "superclass");
        n.put("filePath", formalFile.toString());
        n.put("line", 3);
        n.put("column", 13);
        n.putArray("siblings").add("GreetCasual");
        assertFalse(tool.execute(n).isSuccess());
    }

    @Test
    @DisplayName("no siblings is rejected (need a common set)")
    void rejectsNoSiblings() throws Exception {
        String formalOrig = Files.readString(formalFile);
        ToolResponse r = tool.execute(args("Greeter"));
        assertFalse(r.isSuccess());
        assertEquals(formalOrig, Files.readString(formalFile), "rejection must not touch disk");
        assertFalse(Files.exists(pkgDir.resolve("Greeter.java")));
    }

    @Test
    @DisplayName("an unknown sibling is rejected")
    void rejectsUnknownSibling() {
        assertFalse(tool.execute(args("Greeter", "Nonexistent")).isSuccess());
    }
}
