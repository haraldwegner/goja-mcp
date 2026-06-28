package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IFile;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.codegen.GenerateToStringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateToStringToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private GenerateToStringTool tool;
    private org.goja.mcp.tools.UndoRefactoringTool undoTool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        var cache = new org.goja.mcp.refactoring.RefactoringChangeCache();
        tool = new GenerateToStringTool(() -> service, cache);
        undoTool = new org.goja.mcp.tools.UndoRefactoringTool(() -> service, cache);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: STRING_CONCATENATION style generates concat method")
    void happy_toStringConcatStyle_generatesMethod() throws Exception {
        IFile target = findFile("UnusedCode.java");
        assertNotNull(target);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 7);
        args.put("column", 4);
        args.put("style", "STRING_CONCATENATION");
        ArrayNode fields = args.putArray("fields");
        fields.add("unusedField");
        fields.add("unusedStringField");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String src = (String) data.get("generatedSource");
        assertNotNull(src);
        assertTrue(src.contains("@Override"), "must include @Override; got:\n" + src);
        assertTrue(src.contains("public String toString()"),
            "must include toString signature; got:\n" + src);
        // Concat style: the body uses + and includes the class header.
        assertTrue(src.contains("\"UnusedCode [\""),
            "concat body must start with class header literal; got:\n" + src);
        assertTrue(src.contains("\"unusedField=\""),
            "concat body must include unusedField= literal; got:\n" + src);
        // Should NOT use StringBuilder.
        assertTrue(!src.contains("new StringBuilder()"),
            "concat style must not introduce StringBuilder; got:\n" + src);
    }

    @Test
    @DisplayName("happy: STRING_BUILDER style generates StringBuilder method")
    void happy_toStringBuilderStyle_generatesMethod() throws Exception {
        IFile target = findFile("UnusedCode.java");
        assertNotNull(target);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 7);
        args.put("column", 4);
        args.put("style", "STRING_BUILDER");
        ArrayNode fields = args.putArray("fields");
        fields.add("unusedField");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String src = (String) data.get("generatedSource");
        assertNotNull(src);
        assertTrue(src.contains("StringBuilder sb = new StringBuilder()"),
            "must declare sb; got:\n" + src);
        assertTrue(src.contains("sb.append("),
            "must call sb.append; got:\n" + src);
        assertTrue(src.contains("return sb.toString()"),
            "must return sb.toString(); got:\n" + src);
    }

    @Test
    @DisplayName("Sprint 14b: codegen apply → undo restores the original file byte-for-byte")
    void generateToString_undoRestoresOriginal() throws Exception {
        IFile target = findFile("UnusedCode.java");
        assertNotNull(target);
        java.nio.file.Path targetPath = target.getLocation().toFile().toPath();
        String original = java.nio.file.Files.readString(targetPath);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", targetPath.toString());
        args.put("line", 7);
        args.put("column", 4);
        args.put("style", "STRING_CONCATENATION");
        args.putArray("fields").add("unusedField");

        ToolResponse applied = tool.execute(args);
        assertTrue(applied.isSuccess(), "got: " + applied.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) applied.getData();
        String undoChangeId = (String) data.get("undoChangeId");
        assertNotNull(undoChangeId, "codegen must return an undo handle");
        assertTrue(java.nio.file.Files.readString(targetPath).contains("toString()"),
            "generated method must be on disk");

        ToolResponse undone = undoTool.execute(
            objectMapper.createObjectNode().put("undoChangeId", undoChangeId));
        assertTrue(undone.isSuccess(), "undo must succeed; got: " + undone.getError());
        assertTrue(original.equals(java.nio.file.Files.readString(targetPath)),
            "undo must restore the original content byte-for-byte");
    }

    private IFile findFile(String simpleName) throws Exception {
        AtomicReference<IFile> found = new AtomicReference<>();
        service.getJavaProject().getProject().accept(resource -> {
            if (resource instanceof IFile f && simpleName.equals(f.getName())) {
                found.compareAndSet(null, f);
            }
            return true;
        });
        return found.get();
    }
}
