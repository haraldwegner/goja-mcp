package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IFile;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.codegen.OverrideMethodsTool;
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
 * Sprint 13 (v1.7.0) — {@code override_methods} contract tests.
 *
 * <p>Uses {@code OverrideTarget} → {@code OverrideBase} fixture. Base
 * declares {@code describe()} and {@code countItems()}; target overrides
 * neither at fixture-load time.</p>
 */
class OverrideMethodsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private OverrideMethodsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new OverrideMethodsTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: override one inherited method generates @Override stub")
    void happy_overrideInheritedMethod_generatesStub() throws Exception {
        IFile target = findFile("OverrideTarget.java");
        assertNotNull(target, "OverrideTarget.java fixture must exist");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        // Caret inside the class body. OverrideTarget body starts on line 7.
        // 0-based line 8 = "    private int marker;" (caret-target placeholder).
        args.put("line", 8);
        args.put("column", 16);
        ArrayNode methods = args.putArray("methods");
        methods.add("describe");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> added = (List<String>) data.get("methodsAdded");
        assertEquals(1, added.size(), "expected 1 method added; got: " + added);
        assertEquals("describe", added.get(0));

        String src = (String) data.get("generatedSource");
        assertNotNull(src);
        assertTrue(src.contains("@Override"),
            "generated stub must include @Override; got:\n" + src);
        assertTrue(src.contains("public String describe()"),
            "must declare describe with original return type; got:\n" + src);
        assertTrue(src.contains("throw new UnsupportedOperationException(\"not yet implemented\")"),
            "stub body must throw UnsupportedOperationException; got:\n" + src);
    }

    @Test
    @DisplayName("query: omitting methods returns availableMethods listing")
    void query_listAvailable_returnsCandidates() throws Exception {
        IFile target = findFile("OverrideTarget.java");
        assertNotNull(target);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        // 0-based line 8 = "    private int marker;" (caret-target placeholder).
        args.put("line", 8);
        args.put("column", 16);
        // No `methods` argument → query mode.

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "query mode must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> available = (List<String>) data.get("availableMethods");
        assertNotNull(available, "availableMethods must be populated");
        // OverrideBase contributes describe() and countItems(); both must surface.
        assertTrue(available.stream().anyMatch(s -> s.startsWith("describe")),
            "expected describe() in candidates; got: " + available);
        assertTrue(available.stream().anyMatch(s -> s.startsWith("countItems")),
            "expected countItems() in candidates; got: " + available);
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
