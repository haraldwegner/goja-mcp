package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractTool;
import org.jawata.mcp.tools.UndoRefactoringTool;
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
 *
 * <p>Sprint 25 (spec D1a item 7): the original tests gate the CONSERVATIVE
 * mode ({@code mode=identical}, the preserved Sprint-18 contract); the JDT
 * default ({@code ExtractSupertypeProcessor}) gets its own tests below,
 * including the field + non-shared-member case the conservative mode
 * refuses.</p>
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

    /** Args for the CONSERVATIVE mode (the original Sprint-18 contract). */
    private ObjectNode args(String superclassName, String... siblings) {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "superclass");
        n.put("filePath", formalFile.toString());
        n.put("line", 3);
        n.put("column", 13);
        n.put("superclassName", superclassName);
        n.put("mode", "identical");
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

    // ========== JDT default mode (Sprint 25, spec D1a item 7) ==========

    @Test
    @DisplayName("jdt default: extracts the parent, dedups the sibling's duplicate; compiles; undo restores")
    void jdtDefault_extractsAndDedupsSiblings_undoRestores() throws Exception {
        String formalOrig = Files.readString(formalFile);
        String casualOrig = Files.readString(casualFile);
        Path parentFile = pkgDir.resolve("Greeter.java");
        assertFalse(Files.exists(parentFile), "precondition: parent absent");

        ObjectNode n = args("Greeter", "GreetCasual");
        n.remove("mode"); // default = jdt
        ToolResponse response = tool.execute(n);
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("jdt", data.get("mode"));
        assertNotNull(data.get("undoChangeId"));

        assertTrue(Files.exists(parentFile), "the parent file should be created");
        String parent = Files.readString(parentFile);
        assertTrue(parent.contains("class Greeter"), "parent type:\n" + parent);
        assertTrue(parent.contains("punctuation()"), "pulled-up method:\n" + parent);

        String formal = Files.readString(formalFile);
        assertTrue(formal.contains("class GreetFormal extends Greeter"), "reparented:\n" + formal);
        assertFalse(formal.contains("public String punctuation() {"), "moved out of the caret class:\n" + formal);

        String casual = Files.readString(casualFile);
        assertTrue(casual.contains("class GreetCasual extends Greeter"), "sibling reparented:\n" + casual);
        assertFalse(casual.contains("public String punctuation() {"),
            "the sibling's matching duplicate must be deleted (inherited now):\n" + casual);

        assertEquals(0, compileErrors(formalFile), "GreetFormal must compile:\n" + formal);
        assertEquals(0, compileErrors(casualFile), "GreetCasual must compile:\n" + casual);

        ToolResponse undone = undoTool.execute(
            mapper.createObjectNode().put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertFalse(Files.exists(parentFile), "undo must delete the parent");
        assertEquals(formalOrig, Files.readString(formalFile), "undo must restore GreetFormal");
        assertEquals(casualOrig, Files.readString(casualFile), "undo must restore GreetCasual");
    }

    private ObjectNode shapeArgs() {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "superclass");
        n.put("filePath", pkgDir.resolve("ShapeCircle.java").toString());
        n.put("line", 3);
        n.put("column", 13);
        n.put("superclassName", "SuperShape");
        n.putArray("siblings").add("ShapeSquare");
        n.putArray("members").add("label").add("describe");
        return n;
    }

    @Test
    @DisplayName("jdt default: pulls up a FIELD and a member the sibling lacks (the case mode=identical cannot do)")
    void jdtDefault_pullsUpFieldAndNonSharedMember() throws Exception {
        ToolResponse response = tool.execute(shapeArgs());
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));

        String parent = Files.readString(pkgDir.resolve("SuperShape.java"));
        assertTrue(parent.contains("label"), "field pulled up:\n" + parent);
        assertTrue(parent.contains("describe()"), "method pulled up:\n" + parent);

        String circle = Files.readString(pkgDir.resolve("ShapeCircle.java"));
        assertTrue(circle.contains("class ShapeCircle extends SuperShape"), "reparented:\n" + circle);
        assertFalse(circle.contains("String label"), "field moved out:\n" + circle);
        assertFalse(circle.contains("describe()"), "method moved out:\n" + circle);

        String square = Files.readString(pkgDir.resolve("ShapeSquare.java"));
        assertTrue(square.contains("class ShapeSquare extends SuperShape"),
            "sibling reparented (inherits the new members):\n" + square);

        assertEquals(0, compileErrors(pkgDir.resolve("ShapeCircle.java")));
        assertEquals(0, compileErrors(pkgDir.resolve("ShapeSquare.java")));
    }

    @Test
    @DisplayName("mode=identical refuses the field + non-shared member — without touching disk")
    void identicalMode_refusesFieldAndNonSharedMember() throws Exception {
        String circleOrig = Files.readString(pkgDir.resolve("ShapeCircle.java"));

        ObjectNode n = shapeArgs();
        n.put("mode", "identical");
        ToolResponse response = tool.execute(n);

        assertFalse(response.isSuccess(),
            "the conservative contract must refuse a field / non-identical member");
        assertEquals(circleOrig, Files.readString(pkgDir.resolve("ShapeCircle.java")),
            "refusal must not touch disk");
        assertFalse(Files.exists(pkgDir.resolve("SuperShape.java")));
    }
}
