package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IFile;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.codegen.GenerateTestSkeletonTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerateTestSkeletonToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private GenerateTestSkeletonTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new GenerateTestSkeletonTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: skeleton for class generates @Test per public method")
    void happy_testSkeletonForClass_generatesTestPerPublicMethod() throws Exception {
        IFile target = findFile("Calculator.java");
        assertNotNull(target, "Calculator.java fixture must exist");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        // Caret inside the class body. Calculator at 0-based line 10 sits inside.
        args.put("line", 10);
        args.put("column", 4);
        // Tycho-test runtime doesn't resolve the simple-maven fixture's
        // external Maven deps onto JDT's classpath — auto-detect can't see
        // junit-jupiter. Set explicitly. (Same fixture limitation that has
        // RunTestsTool happy-paths @Disabled in v1.6.0.)
        args.put("framework", "junit5");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> stubbed = (List<String>) data.get("methodsStubbed");
        assertNotNull(stubbed, "methodsStubbed must be present");
        assertTrue(stubbed.size() >= 3,
            "Calculator has at least 3 public methods; got: " + stubbed);
        assertEquals("junit5", data.get("framework"));

        String src = (String) data.get("generatedSource");
        assertNotNull(src);
        assertTrue(src.contains("class CalculatorTest"),
            "skeleton must declare CalculatorTest; got:\n" + src);
        assertTrue(src.contains("org.junit.jupiter.api.Test"),
            "junit5 framework should pull jupiter Test import; got:\n" + src);
        assertTrue(src.contains("@BeforeEach"),
            "junit5 setUp must use @BeforeEach; got:\n" + src);
    }

    @Test
    @Disabled("v1.7.0 known limitation: simple-maven fixture's external Maven "
        + "deps don't resolve onto JDT's classpath in Tycho-surefire test "
        + "runtime, so FrameworkDetection finds nothing. Production usage "
        + "(real workspace with M2E-resolved classpath) detects correctly. "
        + "Same fixture-build gap that has run_tests happy-paths @Disabled. "
        + "See docs/upgrade-checklist.md.")
    @DisplayName("auto: framework detection picks junit5 for jupiter on classpath")
    void frameworkAutoDetect_picksJUnit5() throws Exception {
        IFile target = findFile("HelloWorld.java");
        assertNotNull(target);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 10);
        args.put("column", 4);
        // Don't set framework — let auto-detect run.

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "tool must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("junit5", data.get("framework"),
            "expected auto-detected junit5; got: " + data.get("framework"));
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
