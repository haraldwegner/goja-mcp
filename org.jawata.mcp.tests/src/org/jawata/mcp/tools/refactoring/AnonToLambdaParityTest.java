package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ConvertAnonymousToLambdaTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 25 (spec D1a item 4) — PARITY BATTERY for the
 * {@code convert_anonymous_to_lambda} migration onto JDT's
 * {@code LambdaExpressionsFixCore}. Goldens archived from the OLD string-building
 * converter before the migration; see {@code parity/anon-to-lambda/DIVERGENCES.md}.
 */
class AnonToLambdaParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> HEADER = List.of("interfaceType", "methodName");

    @Test
    @DisplayName("parity: convert anonymous Runnable to lambda")
    void parityConvertRunnable() throws Exception {
        runParity("runnable-simple", 19, 28);
    }

    @Test
    @DisplayName("parity: convert anonymous Comparator (two params) to lambda")
    void parityConvertComparator() throws Exception {
        runParity("comparator-two-params", 33, 31);
    }

    private void runParity(String id, int line, int column) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectPath = helper.getTempDirectory().resolve("simple-maven");
        Path file = projectPath.resolve("src/main/java/com/example/AnonymousClassExamples.java");

        RefactoringChangeCache cache = new RefactoringChangeCache();
        ConvertAnonymousToLambdaTool tool = new ConvertAnonymousToLambdaTool(() -> service, cache);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", line);
        args.put("column", column);
        args.put("auto_apply", false); // stage: compare the planned change

        ToolResponse response = tool.execute(args);
        ParitySupport.assertParity("anon-to-lambda", id, response, projectPath,
            helper.getTempDirectory(), HEADER);
    }
}
