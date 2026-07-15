package org.jawata.mcp.tools.refactoring;

import org.jawata.mcp.models.ToolResponse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 25 (D1) — shared harness for the refactoring-tool PARITY BATTERIES.
 * Each tool migrated onto a JDT engine keeps a golden captured from the OLD
 * tool BEFORE the migration; the battery re-runs the tool in STAGE mode and
 * compares a normalized result (a semantic header of chosen data keys + a
 * path-stripped, file-sorted unified diff) against that golden.
 *
 * <p>Goldens live at {@code <fixtures>/../parity/<tool>/<id>.golden}. Record or
 * refresh with {@code -Djawata.test.parity.record=true}. Divergence between the
 * JDT engine and the archived golden is expected in three classes and RECORDED
 * (never hidden) in {@code parity/<tool>/DIVERGENCES.md}: (1) JDT resolves more,
 * (2) formatting drift normalized here, (3) JDT refuses a precondition.</p>
 */
final class ParitySupport {

    /** {@code -Djawata.test.parity.record=true} writes/refreshes goldens instead of asserting. */
    static final boolean RECORD = Boolean.getBoolean("jawata.test.parity.record");

    private ParitySupport() {}

    static Path goldenDir(String tool) {
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
        return base.resolve("parity").resolve(tool);
    }

    /**
     * Assert (or, in record mode, write) parity of a staged tool response
     * against the golden for {@code tool/id}. {@code headerKeys} names the
     * data-map keys rendered into the canonical semantic header (choose stable,
     * both-tools keys; the diff carries the actual transformation).
     */
    static void assertParity(String tool, String id, ToolResponse response,
                             Path projectPath, Path tempDir, List<String> headerKeys)
            throws IOException {
        String actual = render(response, projectPath, tempDir, headerKeys);
        Path golden = goldenDir(tool).resolve(id + ".golden");
        if (RECORD) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual);
            return;
        }
        assertTrue(Files.exists(golden), () ->
            "missing golden " + golden + " — run with -Djawata.test.parity.record=true to create it, "
                + "then commit. Actual result was:\n" + actual);
        assertEquals(Files.readString(golden), actual, () ->
            "PARITY DIVERGENCE for '" + tool + "/" + id + "': output differs from the archived "
                + "(pre-migration) golden. Classify it — (1) JDT resolved more, (2) formatting drift, "
                + "(3) JDT refused a precondition — record it in parity/" + tool + "/DIVERGENCES.md, and "
                + "refresh the golden with -Djawata.test.parity.record=true when JDT legitimately wins.");
    }

    @SuppressWarnings("unchecked")
    static String render(ToolResponse response, Path projectPath, Path tempDir, List<String> headerKeys) {
        if (!response.isSuccess()) {
            String code = response.getError() == null ? "?" : response.getError().getCode();
            String msg = response.getError() == null ? "" : response.getError().getMessage();
            return "REFUSED code=" + code + "\n" + stripPaths(msg, projectPath, tempDir);
        }
        Map<String, Object> data = (Map<String, Object>) response.getData();
        StringBuilder head = new StringBuilder("APPLIED-STAGED");
        for (String key : headerKeys) {
            head.append(' ').append(key).append('=').append(data.getOrDefault(key, "-"));
        }
        String diff = (String) data.get("diff");
        return head + "\n---DIFF---\n" + normalizeDiff(diff, projectPath, tempDir);
    }

    /** Strip machine-specific temp paths, then sort per-file diff blocks for order-stability. */
    static String normalizeDiff(String diff, Path projectPath, Path tempDir) {
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

    static String stripPaths(String text, Path projectPath, Path tempDir) {
        if (text == null) {
            return "";
        }
        // projectPath is nested under tempDir — replace the longer prefix first.
        return text
            .replace(projectPath.toString(), "<P>")
            .replace(tempDir.toString(), "<T>");
    }
}
