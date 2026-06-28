package org.goja.mcp.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug #2 (v1.7.1) — verify {@code SearchSymbolsTool.dedupeBySymbolIdentity}
 * collapses cache-path duplicates while preserving legitimate ambiguity.
 *
 * <p>Tested directly as a static helper to avoid the heavyweight multi-project
 * Maven fixture (project B depending on project A's compiled JAR) that
 * Tycho-surefire can't build automatically anyway.
 */
class SearchSymbolsToolDedupeTest {

    @Test
    @DisplayName("dedupe: cache-path duplicate dropped, source entry kept (the bugs.md #2 canonical case)")
    void dedupe_classDefinedInOneProjectUsedInAnother_keepsOnlyEntryWithCoordinates() {
        Map<String, Object> sourceEntry = newEntry("Class", "com.example.model.provider.SampleProvider",
            "/home/user/Projects/example/com.example.model/src/com/example/model/provider/SampleProvider.java",
            53, 6);
        Map<String, Object> cacheEntry1 = newEntry("Class", "com.example.model.provider.SampleProvider",
            "/home/user/.cache/goja-studio/workspaces/EXAMPLE-WS/6d65bfe1/goja-com.example.model-7e6f70c7",
            null, null);
        Map<String, Object> cacheEntry2 = newEntry("Class", "com.example.model.provider.SampleProvider",
            "/home/user/.cache/goja-studio/workspaces/EXAMPLE-WS/6d65bfe1/goja-projects_other-7e6f70c7",
            null, null);

        List<Map<String, Object>> deduped = SearchSymbolsTool.dedupeBySymbolIdentity(
            new ArrayList<>(List.of(cacheEntry1, cacheEntry2, sourceEntry)));

        assertEquals(1, deduped.size(),
            "three matches for the same FQN with coordinates on one should collapse to one");
        Map<String, Object> kept = deduped.get(0);
        assertTrue(kept.containsKey("line"), "kept entry must have line");
        assertTrue(kept.containsKey("column"), "kept entry must have column");
        assertEquals(53, kept.get("line"));
        assertFalse(((String) kept.get("filePath")).contains("/.cache/goja-studio/"),
            "cache-path entry must be dropped");
    }

    @Test
    @DisplayName("dedupe: when no entry has coordinates, keep one degraded representative")
    void dedupe_allCacheEntriesNoCoordinates_keepsOneDegraded() {
        Map<String, Object> a = newEntry("Class", "com.example.Foo",
            "/cache/path/a-jar", null, null);
        Map<String, Object> b = newEntry("Class", "com.example.Foo",
            "/cache/path/b-jar", null, null);

        List<Map<String, Object>> deduped = SearchSymbolsTool.dedupeBySymbolIdentity(
            new ArrayList<>(List.of(a, b)));

        assertEquals(1, deduped.size(),
            "no source entry to anchor — collapse to a single degraded entry");
        assertFalse(SearchSymbolsTool.hasCoordinates(deduped.get(0)),
            "degraded entry must not lie about having coordinates");
    }

    @Test
    @DisplayName("dedupe: same FQN with coordinates in two real source files is legitimate ambiguity — both preserved")
    void dedupe_classDefinedInTwoProjects_keepsBothCoordinateBearingEntries() {
        Map<String, Object> a = newEntry("Class", "com.example.Foo",
            "/proj-a/src/com/example/Foo.java", 10, 0);
        Map<String, Object> b = newEntry("Class", "com.example.Foo",
            "/proj-b/src/com/example/Foo.java", 20, 4);

        List<Map<String, Object>> deduped = SearchSymbolsTool.dedupeBySymbolIdentity(
            new ArrayList<>(List.of(a, b)));

        assertEquals(2, deduped.size(),
            "two source entries for the same FQN is legitimate ambiguity (e.g. forked class); both must survive");
        assertTrue(SearchSymbolsTool.hasCoordinates(deduped.get(0)));
        assertTrue(SearchSymbolsTool.hasCoordinates(deduped.get(1)));
    }

    @Test
    @DisplayName("dedupe: single-project search with no duplicates is unchanged (no regression on common case)")
    void dedupe_singleEntry_passThrough() {
        Map<String, Object> only = newEntry("Class", "com.example.Foo",
            "/proj/src/com/example/Foo.java", 5, 0);

        List<Map<String, Object>> deduped = SearchSymbolsTool.dedupeBySymbolIdentity(
            new ArrayList<>(List.of(only)));

        assertEquals(1, deduped.size());
        assertEquals(only, deduped.get(0));
    }

    @Test
    @DisplayName("dedupe: different FQNs with the same simple name keep separate (no over-merging)")
    void dedupe_differentQualifiedNames_doNotMerge() {
        Map<String, Object> a = newEntry("Class", "com.a.Foo",
            "/proj/a/Foo.java", 5, 0);
        Map<String, Object> b = newEntry("Class", "com.b.Foo",
            "/proj/b/Foo.java", 7, 0);

        List<Map<String, Object>> deduped = SearchSymbolsTool.dedupeBySymbolIdentity(
            new ArrayList<>(List.of(a, b)));

        assertEquals(2, deduped.size(),
            "com.a.Foo and com.b.Foo are different classes despite sharing simple name; must not merge");
    }

    @Test
    @DisplayName("dedupe: methods of same name on the same containingType collapse, methods on different types stay separate")
    void dedupe_methods_groupedByContainingTypePlusName() {
        Map<String, Object> sourceMethod = newMethod("Service", "doIt",
            "/src/Service.java", 12, 0);
        Map<String, Object> cacheMethod = newMethod("Service", "doIt", "/cache/Service.class", null, null);
        Map<String, Object> otherType = newMethod("OtherService", "doIt", "/src/OtherService.java", 8, 0);

        List<Map<String, Object>> deduped = SearchSymbolsTool.dedupeBySymbolIdentity(
            new ArrayList<>(List.of(cacheMethod, sourceMethod, otherType)));

        assertEquals(2, deduped.size(),
            "two distinct (containingType#name) groups — Service#doIt collapses, OtherService#doIt survives");
        boolean hasService = deduped.stream().anyMatch(m ->
            "Service".equals(m.get("containingType")) && m.containsKey("line"));
        boolean hasOther = deduped.stream().anyMatch(m ->
            "OtherService".equals(m.get("containingType")) && m.containsKey("line"));
        assertTrue(hasService, "Service#doIt source entry must survive");
        assertTrue(hasOther,   "OtherService#doIt must survive separately");
    }

    // ----- helpers -----

    private static Map<String, Object> newEntry(String kind, String qualifiedName,
                                                String filePath, Integer line, Integer column) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", qualifiedName.substring(qualifiedName.lastIndexOf('.') + 1));
        m.put("kind", kind);
        m.put("filePath", filePath);
        if (line != null) m.put("line", line);
        if (column != null) m.put("column", column);
        m.put("qualifiedName", qualifiedName);
        return m;
    }

    private static Map<String, Object> newMethod(String containingType, String methodName,
                                                 String filePath, Integer line, Integer column) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", methodName);
        m.put("kind", "Method");
        m.put("filePath", filePath);
        if (line != null) m.put("line", line);
        if (column != null) m.put("column", column);
        m.put("containingType", containingType);
        return m;
    }
}
