package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.function.Consumer;
import java.nio.file.Path;

/**
 * Sprint 25 (D1b) — PARITY BATTERY for the {@code change_method_signature}
 * migration onto JDT's {@code ChangeSignatureProcessor}. Goldens are archived
 * from the OLD hand-rolled signature/call-site string builder before the
 * migration; see {@code parity/change-signature/DIVERGENCES.md}. Cases exercise
 * rename, reorder, and add-parameter on {@code formatMessage} (two call sites).
 */
class ChangeMethodSignatureParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> HEADER =
        List.of("oldName", "newName", "oldReturnType", "newReturnType");

    // formatMessage(String message, int count) declaration, 0-based line/col on the name.
    private static final int FM_LINE = 71;
    private static final int FM_COL = 18;

    @Test
    @DisplayName("parity: rename method formatMessage -> format (two call sites)")
    void parityRename() throws Exception {
        runParity("rename-to-format", args -> args.put("newName", "format"));
    }

    @Test
    @DisplayName("parity: reorder parameters (count, message)")
    void parityReorder() throws Exception {
        runParity("reorder-count-message", args -> {
            ArrayNode params = args.putArray("newParameters");
            params.addObject().put("name", "count").put("type", "int");
            params.addObject().put("name", "message").put("type", "String");
        });
    }

    @Test
    @DisplayName("parity: add trailing parameter with default value")
    void parityAddParam() throws Exception {
        runParity("add-suffix-param", args -> {
            ArrayNode params = args.putArray("newParameters");
            params.addObject().put("name", "message").put("type", "String");
            params.addObject().put("name", "count").put("type", "int");
            params.addObject().put("name", "suffix").put("type", "String").put("defaultValue", "\"!\"");
        });
    }

    private void runParity(String id, Consumer<ObjectNode> configure) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectPath = helper.getTempDirectory().resolve("simple-maven");
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java");

        RefactoringChangeCache cache = new RefactoringChangeCache();
        ChangeMethodSignatureTool tool = new ChangeMethodSignatureTool(() -> service, cache);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", FM_LINE);
        args.put("column", FM_COL);
        configure.accept(args);
        args.put("auto_apply", false); // stage: compare the planned change

        ToolResponse response = tool.execute(args);
        ParitySupport.assertParity("change-signature", id, response, projectPath,
            helper.getTempDirectory(), HEADER);
    }
}
