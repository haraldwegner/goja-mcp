package org.goja.mcp.refactoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DiffRendererTest {

    @Test
    @DisplayName("identical content renders an empty diff")
    void identicalContent_emptyDiff() {
        assertEquals("", DiffRenderer.unifiedDiff("A.java", "a\nb\n", "a\nb\n"));
    }

    @Test
    @DisplayName("single replaced line — golden unified diff")
    void singleReplacedLine_golden() {
        String oldContent = "a\nb\nc\nd\ne\n";
        String newContent = "a\nb\nX\nd\ne\n";

        String expected = """
            --- a/A.java
            +++ b/A.java
            @@ -1,5 +1,5 @@
             a
             b
            -c
            +X
             d
             e
            """;
        assertEquals(expected, DiffRenderer.unifiedDiff("A.java", oldContent, newContent));
    }

    @Test
    @DisplayName("insertion — golden unified diff")
    void insertion_golden() {
        String oldContent = "a\nb\n";
        String newContent = "a\nX\nb\n";

        String expected = """
            --- a/A.java
            +++ b/A.java
            @@ -1,2 +1,3 @@
             a
            +X
             b
            """;
        assertEquals(expected, DiffRenderer.unifiedDiff("A.java", oldContent, newContent));
    }

    @Test
    @DisplayName("deletion — golden unified diff")
    void deletion_golden() {
        String oldContent = "a\nX\nb\n";
        String newContent = "a\nb\n";

        String expected = """
            --- a/A.java
            +++ b/A.java
            @@ -1,3 +1,2 @@
             a
            -X
             b
            """;
        assertEquals(expected, DiffRenderer.unifiedDiff("A.java", oldContent, newContent));
    }

    @Test
    @DisplayName("distant changes split into separate hunks")
    void distantChanges_splitIntoHunks() {
        StringBuilder oldContent = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            oldContent.append("line").append(i).append('\n');
        }
        String newContent = oldContent.toString()
            .replace("line2\n", "LINE2\n")
            .replace("line18\n", "LINE18\n");

        String diff = DiffRenderer.unifiedDiff("A.java", oldContent.toString(), newContent);

        long hunkCount = diff.lines().filter(l -> l.startsWith("@@")).count();
        assertEquals(2, hunkCount, "changes 16 lines apart must not merge into one hunk:\n" + diff);
        assertTrue(diff.contains("-line2\n+LINE2"), diff);
        assertTrue(diff.contains("-line18\n+LINE18"), diff);
    }

    @Test
    @DisplayName("multi-file diff concatenates per-file blocks, skipping identical files")
    void multiFile_concatenatesAndSkipsIdentical() {
        String diff = DiffRenderer.unifiedDiff(List.of(
            new DiffRenderer.FileDiff("A.java", "a\n", "b\n"),
            new DiffRenderer.FileDiff("Same.java", "x\n", "x\n"),
            new DiffRenderer.FileDiff("B.java", "1\n", "2\n")
        ));

        assertTrue(diff.contains("--- a/A.java"), diff);
        assertTrue(diff.contains("--- a/B.java"), diff);
        assertFalse(diff.contains("Same.java"), "identical file must be skipped:\n" + diff);
    }

    @Test
    @DisplayName("empty old content renders an all-additions diff")
    void emptyOldContent_allAdditions() {
        String diff = DiffRenderer.unifiedDiff("A.java", "", "a\nb\n");
        assertTrue(diff.contains("@@ -0,0 +1,2 @@"), diff);
        assertTrue(diff.contains("+a\n+b\n"), diff);
    }
}
