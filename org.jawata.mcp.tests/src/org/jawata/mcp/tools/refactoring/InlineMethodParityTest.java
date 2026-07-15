package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.InlineMethodTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 25 (D1b) — PARITY BATTERY for the {@code inline_method} migration onto
 * JDT's {@code InlineMethodRefactoring}. Goldens are archived from the OLD
 * hand-rolled string-substitution inliner before the migration; see
 * {@code parity/inline-method/DIVERGENCES.md}. The header carries the stable
 * {@code methodName}/{@code methodClass}; the diff carries the inlining itself.
 */
class InlineMethodParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> HEADER = List.of("methodName", "methodClass");

    @Test
    @DisplayName("parity: inline single call doubleValue(x) in an assignment")
    void parityInlineDoubleValue() throws Exception {
        runParity("inline-doublevalue", 64, 22);
    }

    @Test
    @DisplayName("parity: inline single call formatMessage(\"Items\", 5) (two params)")
    void parityInlineFormatMessage() throws Exception {
        runParity("inline-formatmessage", 79, 24);
    }

    private void runParity(String id, int line, int column) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectPath = helper.getTempDirectory().resolve("simple-maven");
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java");

        RefactoringChangeCache cache = new RefactoringChangeCache();
        InlineMethodTool tool = new InlineMethodTool(() -> service, cache);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", line);
        args.put("column", column);
        args.put("auto_apply", false); // stage: compare the planned change

        ToolResponse response = tool.execute(args);
        ParitySupport.assertParity("inline-method", id, response, projectPath,
            helper.getTempDirectory(), HEADER);
    }
}
