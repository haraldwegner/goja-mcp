package org.goja.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.MoveClassTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 14 — bugs.md #10: {@code move_class} cross-project relocation
 * (sub-defect 1) and populated {@code modifiedFiles} on the response
 * (sub-defect 2). Sub-defect 3 (Javadoc @link import filter) is deferred to
 * v1.8.1 — see the bug entry.
 *
 * <p>Uses {@link TestProjectHelper#loadWorkspaceCopy(String...)} so both
 * fixtures share one JDT workspace — the prerequisite for cross-project
 * refactorings to resolve targets across project boundaries.</p>
 */
class MoveClassToolCrossProjectTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private MoveClassTool tool;
    private ObjectMapper objectMapper;
    private Path simpleMavenA;
    private Path simpleMavenB;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("simple-maven", "simple-maven-b");
        tool = new MoveClassTool(() -> service, new org.goja.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
        simpleMavenA = helper.getTempDirectory().resolve("simple-maven");
        simpleMavenB = helper.getTempDirectory().resolve("simple-maven-b");
    }

    @Test
    @DisplayName("bugs.md #10 sub-defect 1: move_class physically relocates a class from project A to project B (auto-detect)")
    void crossProjectMove_autoDetect_relocatesFilePhysically() throws Exception {
        Path source = simpleMavenA.resolve("src/main/java/com/example/HelloWorld.java");
        assertTrue(Files.exists(source), "source fixture must exist pre-move");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", source.toString());
        args.put("line", 5);    // public class HelloWorld {
        args.put("column", 13);
        // Target package exists in simple-maven-b but NOT in simple-maven —
        // auto-detect should pick simple-maven-b as the destination project.
        args.put("targetPackage", "com.exampleb");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "cross-project move_class must succeed via auto-detect; got: " + r.getError());

        // The original file is gone from project A.
        assertFalse(Files.exists(source),
            "source file must be gone from project A after cross-project move");
        // The file appears in project B under the target package.
        Path destination = simpleMavenB.resolve("src/main/java/com/exampleb/HelloWorld.java");
        assertTrue(Files.exists(destination),
            "moved file must appear in project B's source tree at " + destination
                + "; B-tree state: " + listB());
    }

    @Test
    @DisplayName("bugs.md #10 sub-defect 2 / Sprint 14b: response.filesModified is populated after a successful move")
    void crossProjectMove_populatesFilesModified() throws Exception {
        Path source = simpleMavenA.resolve("src/main/java/com/example/HelloWorld.java");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", source.toString());
        args.put("line", 5);
        args.put("column", 13);
        args.put("targetPackage", "com.exampleb");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> filesModified = (List<String>) data.get("filesModified");

        assertNotNull(filesModified, "filesModified must not be null");
        assertFalse(filesModified.isEmpty(),
            "filesModified must NOT be empty after a successful move (bugs.md #10 sub-defect 2); response: " + data);
        // Sanity: at least one entry mentions HelloWorld.java.
        assertTrue(filesModified.stream().anyMatch(fp -> fp.contains("HelloWorld")),
            "at least one filesModified entry should mention HelloWorld; got: " + filesModified);
        // Sprint 14b: a successful structural refactor now carries an undo handle.
        assertNotNull(data.get("undoChangeId"), "structural refactor must return an undo handle");
    }

    private String listB() {
        try (java.util.stream.Stream<Path> s = Files.walk(simpleMavenB.resolve("src/main/java"))) {
            return s.filter(Files::isRegularFile)
                .map(Path::toString)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("(empty)");
        } catch (Exception e) {
            return "(walk failed: " + e + ")";
        }
    }
}
