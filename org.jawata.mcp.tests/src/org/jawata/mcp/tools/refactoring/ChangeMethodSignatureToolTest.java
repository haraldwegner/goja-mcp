package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.UndoRefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ChangeMethodSignatureTool under the Sprint 14b
 * auto-apply contract (temp fixture copy; on-disk verification; undo
 * round-trip).
 */
class ChangeMethodSignatureToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ChangeMethodSignatureTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path refactoringTargetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new ChangeMethodSignatureTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        refactoringTargetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/RefactoringTarget.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    private ObjectNode baseArgs() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", refactoringTargetFile.toString());
        args.put("line", 71);  // formatMessage method
        args.put("column", 18);
        return args;
    }

    private ObjectNode param(String name, String type) {
        ObjectNode param = objectMapper.createObjectNode();
        param.put("name", name);
        param.put("type", type);
        return param;
    }

    // ========== Auto-apply contract ==========

    @Test
    @DisplayName("renames method and updates call sites on disk; undo restores")
    void renamesMethod_appliesAndUndoRestores() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ObjectNode args = baseArgs();
        args.put("newName", "formatOutput");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("formatOutput", data.get("newName"));
        assertTrue((int) data.get("totalEdits") > 1, "declaration + call sites");
        assertNotNull(data.get("undoChangeId"));

        String onDisk = Files.readString(refactoringTargetFile);
        assertTrue(onDisk.contains("formatOutput("),
            "renamed signature and call sites must be on disk");
        assertNotEquals(original, onDisk);

        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(refactoringTargetFile));
    }

    @Test
    @DisplayName("adds new parameter with default value")
    void addsParameterWithDefaultValue() throws Exception {
        ObjectNode args = baseArgs();
        ArrayNode params = objectMapper.createArrayNode();
        params.add(param("message", "String"));
        params.add(param("count", "int"));
        ObjectNode withDefault = param("prefix", "String");
        withDefault.put("defaultValue", "\"\"");
        params.add(withDefault);
        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(3, data.get("newParameterCount"));
        assertTrue(Files.readString(refactoringTargetFile).contains("String prefix"),
            "new parameter must be in the signature on disk");
    }

    @Test
    @DisplayName("removes parameter (coupled: body uses it → REPORT fallback + coupledChange worklist)")
    void removesParameter() {
        ObjectNode args = baseArgs();
        ArrayNode params = objectMapper.createArrayNode();
        params.add(param("message", "String"));
        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(1, data.get("newParameterCount"));
        // Sprint 25: JDT conservatively refuses removing a parameter the body names
        // (`count`), so it routes through the REPORT fallback and is flagged
        // coupledChange. Here the applied result happens to compile — the body's
        // `count` falls back to the field of the same name — so there is no worklist;
        // the genuine worklist case is asserted in changesReturnType.
        assertEquals(Boolean.TRUE, data.get("coupledChange"),
            "removing a named parameter must be flagged coupledChange");
    }

    @Test
    @DisplayName("reorders parameters in method signature")
    void reordersParameters() {
        ObjectNode args = baseArgs();
        ArrayNode params = objectMapper.createArrayNode();
        params.add(param("count", "int"));
        params.add(param("message", "String"));
        args.set("newParameters", params);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
    }

    @Test
    @DisplayName("changes method return type")
    void changesReturnType() throws Exception {
        ObjectNode args = baseArgs();
        args.put("newReturnType", "void");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals("void", data.get("newReturnType"));
        // Sprint 25: String->void with a value-returning body is COUPLED — JDT refuses;
        // the REPORT fallback applies it and returns the body/caller sites as the
        // worklist (the change-the-signature-then-fix-by-the-compile-failures workflow).
        assertEquals(Boolean.TRUE, data.get("coupledChange"),
            "return String->void with a value-returning body must be flagged coupledChange");
        assertNotNull(data.get("introducedErrors"),
            "the coupled return change must return its compile-failure worklist");
        assertTrue(Files.readString(refactoringTargetFile).contains("void formatMessage")
                || Files.readString(refactoringTargetFile).contains("void formatMessage("),
            "new return type must be in the signature on disk");
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires at least one change to be specified — without touching disk")
    void requiresAtLeastOneChange() throws Exception {
        String original = Files.readString(refactoringTargetFile);

        ToolResponse response = tool.execute(baseArgs());

        assertFalse(response.isSuccess());
        assertEquals(original, Files.readString(refactoringTargetFile));
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 71);
        args.put("column", 18);
        args.put("newName", "renamed");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("handles invalid position gracefully")
    void handlesInvalidPosition() {
        ObjectNode args = baseArgs();
        args.put("line", -1);
        args.put("column", -1);
        args.put("newName", "renamed");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("handles non-method position gracefully")
    void handlesNonMethodPosition() {
        ObjectNode args = baseArgs();
        args.put("line", 15);  // Field declaration
        args.put("column", 19);
        args.put("newName", "renamed");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== bugs.md #15: constructor handling ==========

    private Path ctorFile() {
        // InterfaceExtractTarget has `public InterfaceExtractTarget(String name)`
        // (0-based line 16) + a `new InterfaceExtractTarget(name)` call site.
        return helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/InterfaceExtractTarget.java");
    }

    private ObjectNode ctorArgs() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", ctorFile().toString());
        args.put("line", 16);    // public InterfaceExtractTarget(String name)
        args.put("column", 20);  // inside the constructor name
        return args;
    }

    @Test
    @DisplayName("#15: constructor stays a constructor (no `void`) + new-expression call site updated; undo restores")
    void constructorParamTypeChange_staysConstructor_updatesCallSite_undoRestores() throws Exception {
        Path file = ctorFile();
        String original = Files.readString(file);

        ObjectNode args = ctorArgs();
        ArrayNode params = args.putArray("newParameters");
        params.add(param("name", "CharSequence")); // String -> CharSequence, same name

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));

        String onDisk = Files.readString(file);
        assertTrue(onDisk.contains("InterfaceExtractTarget(CharSequence name)"),
            "constructor gains the new param type:\n" + onDisk);
        assertFalse(onDisk.contains("void InterfaceExtractTarget"),
            "MUST NOT prepend a return type to a constructor (the bug)");
        assertTrue(onDisk.contains("new InterfaceExtractTarget(name)"),
            "the `new` call site stays valid (ClassInstanceCreation handled, not skipped):\n" + onDisk);

        String undoChangeId = (String) getData(response).get("undoChangeId");
        ToolResponse undone = undoTool.execute(
            objectMapper.createObjectNode().put("undoChangeId", undoChangeId));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(file), "undo restores byte-for-byte");
    }

    @Test
    @DisplayName("#15: adding a constructor param rewrites the `new` call site (old code skipped ClassInstanceCreation)")
    void constructorAddParam_rewritesNewCallSite() throws Exception {
        Path file = ctorFile();
        String original = Files.readString(file);

        ObjectNode args = ctorArgs();
        ArrayNode params = args.putArray("newParameters");
        params.add(param("name", "String"));
        params.add(param("count", "int"));

        ToolResponse response = tool.execute(args);
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));

        String onDisk = Files.readString(file);
        assertTrue(onDisk.contains("InterfaceExtractTarget(String name, int count)"),
            "constructor gains the second param, still a constructor:\n" + onDisk);
        assertFalse(onDisk.contains("void InterfaceExtractTarget"), "no return type on a constructor");
        // v2.12.1 (C13-c): the placeholder must COMPILE — the old bare-comment
        // form (`/* TODO: count */` as the whole argument) was a syntax error
        // this very test used to assert verbatim.
        assertTrue(onDisk.contains("new InterfaceExtractTarget(name, /* TODO count */ 0)"),
            "the `new` call site is rewritten with a compiling added-arg placeholder:\n" + onDisk);

        String undoChangeId = (String) getData(response).get("undoChangeId");
        undoTool.execute(objectMapper.createObjectNode().put("undoChangeId", undoChangeId));
        assertEquals(original, Files.readString(file), "undo restores byte-for-byte");
    }
}
