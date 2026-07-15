package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 25 (D1a) — the PARITY BATTERY for the {@code rename_symbol} migration
 * onto JDT's rename processors. Each case runs {@code rename_symbol} in STAGE
 * mode against a fresh fixture copy and compares a normalized result — the
 * semantic header (symbolKind / oldName / newName / fileRenamed) plus a
 * path-stripped, file-sorted unified diff — against a golden captured from the
 * PRE-migration tool.
 *
 * <p>Goldens live at {@code <fixtures>/../parity/rename/<id>.golden}, a sibling
 * of the {@code sample-projects} fixture tree. Regenerate or refresh them with
 * {@code -Djawata.test.parity.record=true}.</p>
 *
 * <p>Divergence between the JDT engine and the archived golden is EXPECTED in
 * exactly three classes, and is RECORDED (never hidden) in
 * {@code parity/rename/DIVERGENCES.md}:</p>
 * <ol>
 *   <li>JDT resolves references the hand-rolled AST walker missed → JDT wins,
 *       the golden is refreshed and the extra references recorded;</li>
 *   <li>whitespace / formatting drift → normalized away here (never a real
 *       behavior difference);</li>
 *   <li>JDT REFUSES on a precondition where the old tool blindly emitted edits
 *       → JDT wins, the refusal recorded.</li>
 * </ol>
 *
 * <p>Staging (not applying) is deliberate: parity is about the TRANSFORMATION
 * the tool plans, so we compare the previewed change, independent of the
 * apply-time compile-verify gate (exercised by {@link RenameSymbolToolTest}).</p>
 */
class RenameParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** {@code -Djawata.test.parity.record=true} writes/refreshes goldens instead of asserting. */
    private static final boolean RECORD = Boolean.getBoolean("jawata.test.parity.record");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private record Case(String id, String fixture, String file, int line, int col, String newName) {}

    // ---- the parity set: one case per element kind the migration must preserve ----

    @Test
    @DisplayName("parity: rename TYPE HelloWorld -> Greeting (constructors + file rename, reference-heavy)")
    void parityRenameType() throws Exception {
        runParity(new Case("type-helloworld-to-greeting", "simple-maven",
            "src/main/java/com/example/HelloWorld.java", 5, 13, "Greeting"));
    }

    @Test
    @DisplayName("parity: rename METHOD add -> sum")
    void parityRenameMethod() throws Exception {
        runParity(new Case("method-add-to-sum", "simple-maven",
            "src/main/java/com/example/Calculator.java", 14, 15, "sum"));
    }

    @Test
    @DisplayName("parity: rename FIELD userName -> userFullName (multi-usage)")
    void parityRenameField() throws Exception {
        runParity(new Case("field-username-to-userfullname", "simple-maven",
            "src/main/java/com/example/RefactoringTarget.java", 15, 19, "userFullName"));
    }

    @Test
    @DisplayName("parity: rename LOCAL variable -> renamedLocal")
    void parityRenameLocal() throws Exception {
        runParity(new Case("local-to-renamedlocal", "simple-maven",
            "src/main/java/com/example/RefactoringTarget.java", 88, 12, "renamedLocal"));
    }

    // ---- harness ----

    @SuppressWarnings("unchecked")
    private void runParity(Case c) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy(c.fixture());
        Path projectPath = helper.getTempDirectory().resolve(c.fixture());
        Path file = projectPath.resolve(c.file());

        RefactoringChangeCache cache = new RefactoringChangeCache();
        RenameSymbolTool tool = new RenameSymbolTool(() -> service, cache);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", c.line());
        args.put("column", c.col());
        args.put("newName", c.newName());
        args.put("auto_apply", false); // stage: compare the PLANNED change

        ToolResponse response = tool.execute(args);
        String actual = normalize(response, projectPath, helper.getTempDirectory());

        Path golden = goldenDir().resolve(c.id() + ".golden");
        if (RECORD) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual);
            return;
        }

        assertTrue(Files.exists(golden), () ->
            "missing golden " + golden + " — run with -Djawata.test.parity.record=true to create it, "
                + "then commit. Actual result was:\n" + actual);
        String expected = Files.readString(golden);
        assertEquals(expected, actual, () ->
            "PARITY DIVERGENCE for '" + c.id() + "': rename_symbol output differs from the archived "
                + "(pre-migration) golden. Classify it — (1) JDT found more references, (2) formatting "
                + "drift, (3) JDT refused a precondition — record it in parity/rename/DIVERGENCES.md, and "
                + "refresh the golden with -Djawata.test.parity.record=true when JDT legitimately wins.");
    }

    /** Canonical, machine-independent rendering of a rename result. */
    @SuppressWarnings("unchecked")
    private static String normalize(ToolResponse response, Path projectPath, Path tempDir) {
        if (!response.isSuccess()) {
            String code = response.getError() == null ? "?" : response.getError().getCode();
            String msg = response.getError() == null ? "" : response.getError().getMessage();
            return "REFUSED code=" + code + "\n" + stripPaths(msg, projectPath, tempDir);
        }
        Map<String, Object> data = (Map<String, Object>) response.getData();
        StringBuilder head = new StringBuilder("APPLIED-STAGED");
        head.append(" symbolKind=").append(data.get("symbolKind"));
        head.append(" oldName=").append(data.get("oldName"));
        head.append(" newName=").append(data.get("newName"));
        head.append(" fileRenamed=").append(data.getOrDefault("fileRenamed", "-"));
        String diff = (String) data.get("diff");
        return head + "\n---DIFF---\n" + normalizeDiff(diff, projectPath, tempDir);
    }

    /** Strip machine-specific temp paths, then sort per-file diff blocks for order-stability. */
    private static String normalizeDiff(String diff, Path projectPath, Path tempDir) {
        String d = stripPaths(diff, projectPath, tempDir);
        String[] lines = d.split("\n", -1);
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = null;
        List<String> preamble = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("--- ")) {
                current = new ArrayList<>();
                blocks.add(current);
            }
            if (current == null) {
                preamble.add(line);
            } else {
                current.add(line);
            }
        }
        blocks.sort(Comparator.comparing(b -> b.isEmpty() ? "" : b.get(0)));
        StringBuilder sb = new StringBuilder();
        for (String p : preamble) {
            if (!p.isEmpty()) {
                sb.append(p).append("\n");
            }
        }
        for (List<String> block : blocks) {
            for (String line : block) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    private static String stripPaths(String text, Path projectPath, Path tempDir) {
        if (text == null) {
            return "";
        }
        // projectPath is nested under tempDir — replace the longer prefix first.
        return text
            .replace(projectPath.toString(), "<P>")
            .replace(tempDir.toString(), "<T>");
    }

    private static Path goldenDir() {
        String fixtures = System.getProperty("jawata.test.fixtures");
        Path base;
        if (fixtures != null) {
            base = Path.of(fixtures).getParent(); // .../test-resources
        } else {
            base = Path.of("org.jawata.core.tests/test-resources");
            if (!Files.exists(base)) {
                base = Path.of("../org.jawata.core.tests/test-resources");
            }
        }
        return base.resolve("parity/rename");
    }
}
