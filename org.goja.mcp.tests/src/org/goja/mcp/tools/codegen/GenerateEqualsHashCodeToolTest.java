package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IFile;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ErrorInfo;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.codegen.GenerateEqualsHashCodeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 13 (v1.7.0) — {@code generate_equals_hashcode} contract tests.
 */
class GenerateEqualsHashCodeToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private GenerateEqualsHashCodeTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new GenerateEqualsHashCodeTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: equals/hashCode for two fields generates pair")
    void happy_equalsHashCodeForFields_generatesPair() throws Exception {
        IFile target = findFile("UnusedCode.java");
        assertNotNull(target, "UnusedCode.java must be present");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 7);
        args.put("column", 4);
        ArrayNode fields = args.putArray("fields");
        fields.add("unusedField");        // int (primitive)
        fields.add("unusedStringField");  // String (reference)

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "generate_equals_hashcode must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> added = (List<String>) data.get("methodsAdded");
        assertEquals(2, added.size(), "expected equals + hashCode; got: " + added);
        assertTrue(added.contains("equals"));
        assertTrue(added.contains("hashCode"));

        String generated = (String) data.get("generatedSource");
        assertNotNull(generated);
        assertTrue(generated.contains("@Override"),
            "generated source must include @Override; got:\n" + generated);
        assertTrue(generated.contains("public boolean equals(Object obj)"),
            "missing equals signature; got:\n" + generated);
        assertTrue(generated.contains("public int hashCode()"),
            "missing hashCode signature; got:\n" + generated);
        // Primitive field uses == comparison.
        assertTrue(generated.contains("this.unusedField == other.unusedField"),
            "primitive field comparison missing; got:\n" + generated);
        // Reference field uses Objects.equals.
        assertTrue(generated.contains("Objects.equals(this.unusedStringField, other.unusedStringField)"),
            "Objects.equals on reference field missing; got:\n" + generated);
        // hashCode delegates to Objects.hash.
        assertTrue(generated.contains("Objects.hash(unusedField, unusedStringField)"),
            "Objects.hash missing; got:\n" + generated);
        // java.util.Objects import inserted.
        assertTrue(generated.contains("import java.util.Objects"),
            "java.util.Objects import missing; got:\n" + generated);
    }

    @Test
    @DisplayName("validation: empty fields returns INVALID_PARAMETER")
    void validation_emptyFields_returnsInvalidParameter() throws Exception {
        IFile target = findFile("UnusedCode.java");
        assertNotNull(target);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 7);
        args.put("column", 4);
        args.putArray("fields"); // empty

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "empty fields must be rejected");
        ErrorInfo err = r.getError();
        assertNotNull(err);
        assertEquals(ErrorInfo.INVALID_PARAMETER, err.getCode(),
            "expected INVALID_PARAMETER; got: " + err);
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
