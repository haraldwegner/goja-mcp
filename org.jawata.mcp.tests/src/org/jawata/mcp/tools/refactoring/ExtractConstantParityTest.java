package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractConstantTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 25 (D1c) — PARITY BATTERY for the {@code extract_constant} migration
 * onto JDT's {@code ExtractConstantRefactoring}. Goldens archived from the OLD
 * hand-rolled string-building extractor before the migration; see
 * {@code parity/extract-constant/DIVERGENCES.md}.
 */
class ExtractConstantParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> HEADER = List.of("constantName", "constantType");

    @Test
    @DisplayName("parity: extract constant DEFAULT_PREFIX from \"PREFIX_\"")
    void parityExtractConstant() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectPath = helper.getTempDirectory().resolve("simple-maven");
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java");

        RefactoringChangeCache cache = new RefactoringChangeCache();
        ExtractConstantTool tool = new ExtractConstantTool(() -> service, cache);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("startLine", 35);
        args.put("startColumn", 24);
        args.put("endLine", 35);
        args.put("endColumn", 33);
        args.put("constantName", "DEFAULT_PREFIX");
        args.put("auto_apply", false); // stage: compare the planned change

        ToolResponse response = tool.execute(args);
        ParitySupport.assertParity("extract-constant", "default-prefix-from-literal", response,
            projectPath, helper.getTempDirectory(), HEADER);
    }
}
