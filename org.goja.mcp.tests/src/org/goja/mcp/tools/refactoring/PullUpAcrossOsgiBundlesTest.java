package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.PullUpTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11 closeout — proves an LTK structural refactoring spans an
 * OSGi {@code Require-Bundle} relationship.
 *
 * <p>Loads the {@code pde-bundle-a} / {@code pde-bundle-b} fixtures from
 * Sprint 11 Phase B into one workspace. Bundle A {@code Require-Bundle}s
 * bundle B; A's {@code SpecificGreeter} extends B's {@code Greeter} and
 * declares an extra method {@code greetWithEmphasis}. Pulling that method
 * up moves it out of bundle A into bundle B's source tree — which is only
 * possible because the workspace bundle pool has resolved the cross-bundle
 * supertype reference.</p>
 */
class PullUpAcrossOsgiBundlesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("happy: pull SpecificGreeter.greetWithEmphasis() from bundle A up to Greeter in bundle B")
    void pullUp_acrossOsgiBundles() throws Exception {
        // Load B first so its Bundle-SymbolicName lands in the workspace bundle
        // pool before A's Require-Bundle is resolved.
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-b", "pde-bundle-a");

        Path tempRoot = helper.getTempDirectory();
        Path bundleA = tempRoot.resolve("pde-bundle-a");
        Path bundleB = tempRoot.resolve("pde-bundle-b");
        Path subtype = bundleA.resolve("src/com/example/a/SpecificGreeter.java");
        Path supertype = bundleB.resolve("src/com/example/b/Greeter.java");
        assertTrue(Files.exists(subtype), "SpecificGreeter must exist in bundle A's temp copy");
        assertTrue(Files.exists(supertype), "Greeter must exist in bundle B's temp copy");

        PullUpTool tool = new PullUpTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", subtype.toString());
        // 'public String greetWithEmphasis(String name) {' is the 6th line
        // (zero-based index 5); 'greetWithEmphasis' starts at column 18.
        args.put("line", 5);
        args.put("column", 18);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "pull_up across an OSGi Require-Bundle relationship must succeed; got: "
                + r.getError());

        // Cross-bundle effect: the method now lives in bundle B's source file.
        String supertypeContent = Files.readString(supertype);
        assertTrue(supertypeContent.contains("greetWithEmphasis"),
            "Greeter.java in bundle B must now declare greetWithEmphasis "
                + "after the cross-bundle pull_up; current content:\n" + supertypeContent);
    }
}
