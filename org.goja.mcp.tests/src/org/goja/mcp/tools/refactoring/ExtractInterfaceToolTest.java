package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.ExtractInterfaceTool;
import org.goja.mcp.tools.UndoRefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ExtractInterfaceTool under the Sprint 14b auto-apply
 * contract: creates the interface file AND adds the implements clause on
 * disk; undo removes the new file and reverts the class.
 */
class ExtractInterfaceToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ExtractInterfaceTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;
    private Path interfaceTargetFile;
    private Path newInterfaceFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new ExtractInterfaceTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
        interfaceTargetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/InterfaceExtractTarget.java");
        newInterfaceFile = interfaceTargetFile.resolveSibling("IExtractTarget.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getExtractedMethods(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("extractedMethods");
    }

    private ObjectNode extractionArgs() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetFile.toString());
        args.put("line", 8);  // Class declaration line (0-based)
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");
        return args;
    }

    // ========== Auto-apply contract ==========

    @Test
    @DisplayName("extracts interface: creates file + implements clause on disk; undo removes both")
    void extractsInterface_appliesAndUndoRestores() throws Exception {
        String originalClass = Files.readString(interfaceTargetFile);
        assertFalse(Files.exists(newInterfaceFile), "fixture must not ship the interface");

        ToolResponse response = tool.execute(extractionArgs());

        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("IExtractTarget", data.get("interfaceName"));
        assertNotNull(data.get("undoChangeId"));

        // Interface file created with the expected content
        assertTrue(Files.exists(newInterfaceFile), "interface file must be created on disk");
        String interfaceOnDisk = Files.readString(newInterfaceFile);
        assertTrue(interfaceOnDisk.contains("package com.example"));
        assertTrue(interfaceOnDisk.contains("public interface IExtractTarget"));

        // Class gained the implements clause. The fixture already implements
        // Comparable, so the new interface is appended to the existing list.
        String classOnDisk = Files.readString(interfaceTargetFile);
        assertTrue(classOnDisk.contains(", IExtractTarget"),
            "class must implement the new interface (appended to the existing list): "
                + classOnDisk.lines().filter(l -> l.contains("class InterfaceExtractTarget"))
                    .findFirst().orElse("?"));

        // Public methods extracted
        List<Map<String, Object>> methods = getExtractedMethods(data);
        assertFalse(methods.isEmpty());

        // Undo: interface file gone, class restored byte-for-byte
        ToolResponse undone = undoTool.execute(objectMapper.createObjectNode()
            .put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertFalse(Files.exists(newInterfaceFile), "undo must remove the created file");
        assertEquals(originalClass, Files.readString(interfaceTargetFile));
    }

    @Test
    @DisplayName("excludes private and static methods from interface")
    void excludesPrivateAndStaticMethods() {
        ToolResponse response = tool.execute(extractionArgs());

        assertTrue(response.isSuccess());
        List<Map<String, Object>> methods = getExtractedMethods(getData(response));

        assertFalse(methods.stream().anyMatch(m -> ((String) m.get("name")).contains("helper")),
            "private helper() must be excluded");
        assertFalse(methods.stream().anyMatch(m -> ((String) m.get("name")).contains("create")),
            "static create() must be excluded");
    }

    // ========== Optional Parameters Tests ==========

    @Test
    @DisplayName("allows selecting specific methods for interface")
    void allowsSelectingSpecificMethods() {
        ObjectNode args = extractionArgs();
        args.set("methodNames", objectMapper.createArrayNode().add("getName").add("setName"));

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        assertEquals(2, getExtractedMethods(getData(response)).size());
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires interfaceName parameter")
    void requiresInterfaceName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetFile.toString());
        args.put("line", 8);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid interface names — without touching disk")
    void rejectsInvalidInterfaceNames() throws Exception {
        String original = Files.readString(interfaceTargetFile);

        ObjectNode args = extractionArgs();
        args.put("interfaceName", "123Invalid");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
        assertEquals(original, Files.readString(interfaceTargetFile));
    }

    @Test
    @DisplayName("handles invalid position gracefully")
    void handlesInvalidPosition() {
        ObjectNode args = extractionArgs();
        args.put("line", 999);  // Way beyond file length
        args.put("column", 999);

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
