package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IFile;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ErrorInfo;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.codegen.GenerateConstructorTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 13 (v1.7.0) — {@code generate_constructor} contract tests.
 *
 * <p>Loads the {@code simple-maven} fixture, points the tool at
 * {@code RefactoringTarget} (which has {@code userName} and {@code count}
 * fields), and asserts the generated constructor body initializes both.</p>
 *
 * <p>Validation path: requesting a field not declared on the class returns
 * {@code INVALID_PARAMETER}.</p>
 */
class GenerateConstructorToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private GenerateConstructorTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new GenerateConstructorTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: constructor for two fields generates initializers")
    void happy_constructorForFields_generatesInitializers() throws Exception {
        IFile target = findFile("RefactoringTarget.java");
        assertNotNull(target, "RefactoringTarget.java must be present in fixture");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        // RefactoringTarget class declaration sits around line 13; pick a caret
        // inside the class body by aiming at line 16 (the userName field decl).
        args.put("line", 16);
        args.put("column", 4);
        ArrayNode fields = args.putArray("fields");
        fields.add("userName");
        fields.add("count");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "generate_constructor must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("generate_constructor", data.get("operation"));
        assertEquals("RefactoringTarget", data.get("methodName"));

        String generated = (String) data.get("generatedSource");
        assertNotNull(generated, "generatedSource must be populated");
        // The constructor must take both params and assign each via this.field = field.
        assertTrue(generated.contains("public RefactoringTarget(String userName, int count)"),
            "generated source must declare new ctor signature; got:\n" + generated);
        assertTrue(generated.contains("this.userName = userName"),
            "generated source must assign userName; got:\n" + generated);
        assertTrue(generated.contains("this.count = count"),
            "generated source must assign count; got:\n" + generated);
    }

    @Test
    @DisplayName("validation: unknown field returns INVALID_PARAMETER")
    void validation_unknownField_returnsInvalidParameter() throws Exception {
        IFile target = findFile("RefactoringTarget.java");
        assertNotNull(target, "RefactoringTarget.java must be present in fixture");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 16);
        args.put("column", 4);
        ArrayNode fields = args.putArray("fields");
        fields.add("doesNotExist");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "unknown field name must be rejected");
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
