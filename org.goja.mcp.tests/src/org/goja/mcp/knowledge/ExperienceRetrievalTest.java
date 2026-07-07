package org.goja.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21 Stage 2 — the two-phase, fit-gated, terminal retrieval contract. Runs with a
 * {@code null} JDT service (pointer resolution reports "no project loaded", the algorithm
 * is unaffected).
 */
class ExperienceRetrievalTest {

    private H2ExperienceStore store;
    private ExperienceRetrieval retrieval;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        retrieval = new ExperienceRetrieval(store, () -> null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private String putSymbol(String type, String summary, String symbolFqn, String... symptoms) {
        ExperienceEntry.Builder b = ExperienceEntry.of(
            SymbolFact.of(type, summary, Confidence.HIGH).symbol(symbolFqn).build());
        for (String s : symptoms) {
            b.addSymptom(s);
        }
        return store.put(b.build());
    }

    private String putPackage(String type, String summary, String pkg) {
        return store.put(ExperienceEntry.of(
            SymbolFact.of(type, summary, Confidence.MEDIUM).scope(List.of(pkg), List.of()).build())
            .scopeKind("package").build());
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> entries(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("entries");
    }

    /** Sprint 21c: a file-backed family member (parent or section) sharing a source_ref. */
    private String putFamily(String sourceRef, String summary, boolean section, String... symptoms) {
        ExperienceEntry.Builder b = ExperienceEntry.of(
            SymbolFact.of("lesson", summary, Confidence.MEDIUM).build());
        if (section) {
            b.scopeKind("section");
        }
        for (String s : symptoms) {
            b.addSymptom(s);
        }
        return store.putWithSource(b.build(), sourceRef, "hash");
    }

    private static java.util.Set<Object> ids(List<Map<String, Object>> entries) {
        java.util.Set<Object> ids = new java.util.HashSet<>();
        for (Map<String, Object> e : entries) {
            ids.add(e.get("id"));
        }
        return ids;
    }

    @Test
    void parent_is_dropped_when_its_own_section_fits() {
        // Sprint 21c (item B): the section IS the fact — recall answers with it, not
        // the file bundle; injection pays only the fact's tokens.
        String parent = putFamily("memory:/m/webkit.md", "webkit notes bundle", false,
            "tauri webview renders blank on aarch64");
        String section = putFamily("memory:/m/webkit.md", "Tauri blank webview fix", true,
            "tauri webview renders blank on aarch64");

        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "blank webview", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertTrue(ids(entries(r)).contains(section), "the fact answers");
        assertFalse(ids(entries(r)).contains(parent), "the bundle is dropped from the fit set");
    }

    @Test
    void parent_still_answers_when_only_it_fits() {
        String parent = putFamily("memory:/m/f.md", "frontmatter cue lives here", false,
            "orphan preamble cue");
        putFamily("memory:/m/f.md", "Some unrelated section", true, "different topic entirely");

        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "orphan preamble cue", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertTrue(ids(entries(r)).contains(parent), "no fitting section → the parent answers");
    }

    @Test
    void sibling_sections_stay_an_ordered_fit_set() {
        // The hook's ambiguity signal (Sprint 21c item D): 2+ fitting siblings are
        // returned ordered — single-fact-or-nothing is enforced at the hook boundary.
        String s1 = putFamily("memory:/m/a.md", "Lock retry on open", true, "lock file recently modified");
        String s2 = putFamily("memory:/m/b.md", "Lock race at swap", true, "lock file recently modified");

        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "lock file recently modified", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertEquals(2, entries(r).size(), "both siblings visible — ambiguity stays honest");
        assertTrue(ids(entries(r)).containsAll(java.util.Set.of(s1, s2)));
    }

    @Test
    void recorded_entries_are_never_family_dropped() {
        ExperienceEntry.Builder rb = ExperienceEntry.of(
            SymbolFact.of("lesson", "recorded lesson", Confidence.MEDIUM).build());
        rb.addSymptom("shared cue words");
        String recorded = store.put(rb.build());
        String section = putFamily("memory:/m/x.md", "Sectioned fact", true, "shared cue words");

        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "shared cue words", null));
        assertEquals(2, entries(r).size(), "a recorded entry has no family — never dropped");
        assertTrue(ids(entries(r)).containsAll(java.util.Set.of(recorded, section)));
    }

    @Test
    void empty_cue_is_absence() {
        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, r.get("result"));
        assertEquals("no cue provided", r.get("reason"));
    }

    @Test
    void fuzzy_symptom_alias_hits_not_exact() {
        putSymbol("failure_mode", "OSGi resolve NPE running Maven tests", "com.example.alpol.Gateway",
            "OSGi NPE", "null service at startup");
        // Cue is a paraphrase with different case/spacing — alias normalization bridges it.
        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "  osgi   NPE ", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertEquals(1, entries(r).size());
        assertEquals("failure_mode", entries(r).get(0).get("type"));
    }

    @Test
    void symptom_tokens_match_summary_non_adjacently() {
        // v2.2.3 dogfood find: md-loaded entries carry no symptom rows, and the old
        // contiguous-substring match missed summaries where the cue words are not
        // adjacent — "blank webview" found nothing despite this entry.
        putSymbol("reference",
            "WebKitGTK DMABUF compositor fails silently; window chrome renders, "
                + "webview content area stays blank (GTK background colour)",
            null);
        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "blank webview", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertEquals(1, entries(r).size());
    }

    @Test
    void symptom_requires_all_tokens_not_any() {
        putSymbol("reference", "the webview initializes fine on wayland", null);
        // Only one of the two cue tokens appears — a loose ANY-match would return this;
        // the fit gate must not.
        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "blank webview", null));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, r.get("result"));
    }

    @Test
    void scope_mismatch_returns_absence() {
        putSymbol("lesson", "guard the workbench lifecycle", "com.a.Foo");
        // A symbol in a different tree does not fit — terminal absence, not a loose match.
        Map<String, Object> r = retrieval.recall(new RecallQuery("com.b.Bar", null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, r.get("result"));
        assertTrue(entries(r).isEmpty());
    }

    @Test
    void package_scoped_entry_fits_a_symbol_inside_it() {
        putPackage("domain_fact", "billing DTOs keep no-arg constructors", "com.example.billing");
        Map<String, Object> r = retrieval.recall(
            new RecallQuery("com.example.billing.InvoiceDto", null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertEquals(1, entries(r).size());
    }

    @Test
    void disambiguation_prefers_more_specific_scope() {
        putPackage("domain_fact", "package-level note", "com.example.billing");
        putSymbol("lesson", "symbol-level note", "com.example.billing.InvoiceDto");
        Map<String, Object> r = retrieval.recall(
            new RecallQuery("com.example.billing.InvoiceDto", null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        // Both fit; the symbol-scoped node is more specific → it is the head.
        assertEquals("symbol-level note", entries(r).get(0).get("summary"));
    }

    @Test
    void rejected_entries_are_excluded() {
        String id = putSymbol("lesson", "obsolete note", "com.a.Foo");
        store.setStatus(id, ExperienceEntry.REJECTED);
        Map<String, Object> r = retrieval.recall(new RecallQuery("com.a.Foo", null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, r.get("result"));
    }

    @Test
    void operation_cue_fits_when_entry_is_operation_scoped() {
        store.put(ExperienceEntry.of(
            SymbolFact.of("failure_mode", "run_tests OSGi NPE on plain Maven", Confidence.HIGH)
                .symbol("com.example.alpol.Gateway").build())
            .operation("run_tests").build());
        Map<String, Object> hit = retrieval.recall(new RecallQuery(null, null, "run_tests", null, null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, hit.get("result"));
        assertFalse(entries(hit).isEmpty());
        // A different operation does not fit → absence.
        Map<String, Object> miss = retrieval.recall(new RecallQuery(null, null, "rename_symbol", null, null));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, miss.get("result"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void pointer_resolution_flags_no_project_when_jdt_absent() {
        putSymbol("lesson", "guard lifecycle", "com.example.WorkflowCoordinator");
        Map<String, Object> r = retrieval.recall(
            new RecallQuery("com.example.WorkflowCoordinator", null, null, null, null));
        Map<String, Object> pointer = (Map<String, Object>) entries(r).get(0).get("resolved_pointer");
        assertEquals(false, pointer.get("resolved"));
        assertEquals("no project loaded", pointer.get("note"));
    }
}
