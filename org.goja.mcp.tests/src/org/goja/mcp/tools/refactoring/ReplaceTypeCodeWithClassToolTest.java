package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 19 — replace_type_code_with_class via refactor_to_pattern (new-CU creation).
 * Fixture com.example.TypeCodeTargets: {@code Order} with STATUS_* codes at 0-based 11:6.
 */
class ReplaceTypeCodeWithClassToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RefactorToPatternTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper mapper;
    private Path pkgDir;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new RefactorToPatternTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        mapper = new ObjectMapper();
        pkgDir = helper.getTempDirectory().resolve("simple-maven/src/main/java/com/example");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode args(String newTypeName) {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "replace_type_code_with_class");
        n.put("filePath", pkgDir.resolve("TypeCodeTargets.java").toString());
        n.put("line", 11);
        n.put("column", 6);
        n.put("newTypeName", newTypeName);
        return n;
    }

    @Test
    @DisplayName("generates the enum in a new file; undo deletes it")
    void generatesEnum_andUndoDeletes() throws Exception {
        Path newFile = pkgDir.resolve("OrderStatus.java");
        assertFalse(Files.exists(newFile), "precondition: new file absent");

        ToolResponse response = tool.execute(args("OrderStatus"));
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("OrderStatus", data.get("newType"));
        assertNotNull(data.get("undoChangeId"));

        assertTrue(Files.exists(newFile), "the enum file should be created");
        String enumSrc = Files.readString(newFile);
        assertTrue(enumSrc.contains("package com.example;"), "same package:\n" + enumSrc);
        assertTrue(enumSrc.contains("public enum OrderStatus {"), "enum declared:\n" + enumSrc);
        // STATUS_ prefix stripped to clean constant names
        assertTrue(enumSrc.contains("NEW"), "NEW constant:\n" + enumSrc);
        assertTrue(enumSrc.contains("PAID"), "PAID constant:\n" + enumSrc);
        assertTrue(enumSrc.contains("SHIPPED"), "SHIPPED constant:\n" + enumSrc);

        ToolResponse undone = undoTool.execute(
            mapper.createObjectNode().put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertFalse(Files.exists(newFile), "undo must delete the created file");
    }

    @Test
    @DisplayName("missing newTypeName is rejected")
    void rejectsMissingNewTypeName() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "replace_type_code_with_class");
        args.put("filePath", pkgDir.resolve("TypeCodeTargets.java").toString());
        args.put("line", 11);
        args.put("column", 6);
        assertFalse(tool.execute(args).isSuccess());
    }

    @Test
    @DisplayName("a class with no type-code group is rejected")
    void rejectsNoTypeCodeGroup() throws Exception {
        // NoCodes is at 0-based line 23 (class NoCodes { ... })
        ObjectNode args = args("Whatever");
        args.put("line", 23);
        ToolResponse response = tool.execute(args);
        assertFalse(response.isSuccess());
    }
}
