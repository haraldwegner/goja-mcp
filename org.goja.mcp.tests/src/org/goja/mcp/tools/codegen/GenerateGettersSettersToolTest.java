package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IFile;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.codegen.GenerateGettersSettersTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 13 (v1.7.0) — {@code generate_getters_setters} contract tests.
 *
 * <p>Targets the {@code UnusedCode} fixture (has fields with no accessors)
 * for the happy path and {@code RefactoringTarget} (already has
 * {@code getUserName}/{@code setUserName}) for the conflict path.</p>
 */
class GenerateGettersSettersToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private GenerateGettersSettersTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new GenerateGettersSettersTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: both for two fields generates four methods")
    void happy_bothForTwoFields_generatesFourMethods() throws Exception {
        IFile target = findFile("UnusedCode.java");
        assertNotNull(target, "UnusedCode.java must be present in fixture");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        // Caret anywhere inside the class body is fine — UnusedCode has no
        // package-level decl quirks, line ~7 sits inside the class.
        args.put("line", 7);
        args.put("column", 4);
        ArrayNode fields = args.putArray("fields");
        fields.add("unusedField");
        fields.add("unusedStringField");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "generate_getters_setters must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> added = (List<String>) data.get("methodsAdded");
        assertEquals(4, added.size(),
            "expected 4 methods (2 getters + 2 setters); got: " + added);
        assertTrue(added.contains("getUnusedField"), "missing getUnusedField in " + added);
        assertTrue(added.contains("setUnusedField"), "missing setUnusedField in " + added);
        assertTrue(added.contains("getUnusedStringField"), "missing getUnusedStringField in " + added);
        assertTrue(added.contains("setUnusedStringField"), "missing setUnusedStringField in " + added);

        String generated = (String) data.get("generatedSource");
        assertNotNull(generated);
        assertTrue(generated.contains("public int getUnusedField()"),
            "generated source must declare getUnusedField; got:\n" + generated);
        assertTrue(generated.contains("public void setUnusedField(int unusedField)"),
            "generated source must declare setUnusedField; got:\n" + generated);
    }

    @Test
    @DisplayName("conflict: existing accessor is skipped and reported in warnings")
    void conflict_existingAccessor_isSkipped() throws Exception {
        IFile target = findFile("RefactoringTarget.java");
        assertNotNull(target, "RefactoringTarget.java must be present");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 16);
        args.put("column", 4);
        ArrayNode fields = args.putArray("fields");
        fields.add("userName"); // RefactoringTarget already has getUserName/setUserName

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "tool itself succeeds even when both accessors are skipped; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> added = (List<String>) data.get("methodsAdded");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) data.get("warnings");

        assertEquals(0, added.size(),
            "no methods should be added; both accessors already exist; got: " + added);
        assertEquals(2, warnings.size(),
            "exactly two warnings expected (one per skipped accessor); got: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("getUserName")),
            "expected getUserName skip warning; got: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("setUserName")),
            "expected setUserName skip warning; got: " + warnings);
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
