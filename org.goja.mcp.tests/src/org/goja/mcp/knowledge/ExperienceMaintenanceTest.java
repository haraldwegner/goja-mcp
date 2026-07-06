package org.goja.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 21 Stage 4 — initial_load ingest + refresh/wipe maintenance. */
class ExperienceMaintenanceTest {

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ExperienceMaintenance maint(ExperienceMaintenance.PointerResolver r) {
        return new ExperienceMaintenance(store, r);
    }

    private void writeMemory(Path dir, String file, String frontmatter, String body) throws IOException {
        Files.writeString(dir.resolve(file), "---\n" + frontmatter + "\n---\n" + body);
    }

    @Test
    void load_ingests_memory_files_with_frontmatter_and_links(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md",
            "name: guard-lifecycle\ndescription: guard the workbench lifecycle\nmetadata:\n  type: feedback",
            "Body mentions [[recall-gap]] and [[another-note]].");
        writeMemory(dir, "b.md",
            "name: billing-dto\ndescription: billing DTOs keep no-arg ctors\ntype: domain_fact",
            "Legacy XML depends on them.");

        Map<String, Object> report = maint(fqn -> null).load(dir);
        assertEquals(2, report.get("loaded"));
        assertEquals(2L, store.count());

        List<StoredEntry> feedback = store.query(new RecallQuery(null, null, null, "guard the workbench", null));
        assertFalse(feedback.isEmpty(), "loaded entry is queryable by its summary");
        assertEquals(ExperienceEntry.ACCEPTED, feedback.get(0).status());
        assertEquals("feedback", feedback.get(0).type());

        // [[wikilinks]] became related edges in the stored document.
        Map<String, Object> doc = store.get(feedback.get(0).id()).orElseThrow();
        assertTrue(doc.get("links") instanceof List<?>);
        assertEquals(2, ((List<?>) doc.get("links")).size());
    }

    @Test
    void load_is_idempotent_per_source(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md", "name: n\ndescription: d\ntype: lesson", "body");
        maint(fqn -> null).load(dir);
        maint(fqn -> null).load(dir);   // re-load same source
        assertEquals(1L, store.count(), "re-load replaces, not duplicates");
    }

    @Test
    void load_flags_stale_symbol_on_ingest(@TempDir Path dir) throws IOException {
        writeMemory(dir, "s.md",
            "name: n\ndescription: d\ntype: lesson\nsymbol: com.gone.Removed", "body");
        Map<String, Object> report = maint(fqn -> Boolean.FALSE).load(dir);
        assertEquals(1, ((List<?>) report.get("stale")).size(), "unresolvable pointer flagged at ingest");
    }

    @Test
    void refresh_flags_stale_pointer_as_superseded() {
        String id = store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "note", Confidence.HIGH).symbol("com.gone.Removed").build()).build());

        Map<String, Object> report = maint(fqn -> Boolean.FALSE).refresh();
        assertEquals(1, report.get("checked"));
        assertEquals(1, ((List<?>) report.get("staled")).size());

        // The stale entry is superseded and no longer surfaces in recall.
        StoredEntry after = store.all().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
        assertEquals(ExperienceEntry.SUPERSEDED, after.status());
        assertTrue(store.query(new RecallQuery("com.gone.Removed", null, null, null, null)).isEmpty());
    }

    @Test
    void refresh_judges_only_active_entries() {
        // Sprint 21b: re-superseding an already superseded entry wrote an UPDATE per
        // refresh — with refresh automatic after every load, the store grew per click.
        store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "note", Confidence.HIGH).symbol("com.gone.Removed").build()).build());
        ExperienceMaintenance m = maint(fqn -> Boolean.FALSE);
        assertEquals(1, ((List<?>) m.refresh().get("staled")).size(), "first pass supersedes");

        Map<String, Object> second = m.refresh();
        assertEquals(0, second.get("checked"), "superseded entries are not re-judged");
        assertEquals(0, ((List<?>) second.get("staled")).size(), "and never re-written");
    }

    @Test
    void refresh_with_no_project_skips_without_flagging() {
        store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "note", Confidence.HIGH).symbol("com.a.Foo").build()).build());
        Map<String, Object> report = maint(fqn -> null).refresh();
        assertEquals(1, report.get("checked"));
        assertEquals(1, report.get("skipped"));
        assertEquals(0, ((List<?>) report.get("staled")).size());
    }

    // --- Sprint 21a (item C): default roots + link-graph traversal ------------------------

    @Test
    void load_follows_the_link_graph_from_an_index_file(@TempDir Path dir) throws IOException {
        // MEMORY.md-style index: a wikilink + a relative markdown link into a subdir; the
        // linked files are reachable ONLY via links (the index is the single root).
        Files.createDirectory(dir.resolve("sub"));
        Files.writeString(dir.resolve("MEMORY.md"),
            "- [Fact A](fact-a.md) — hook\n- see also [[fact-b]]\n");
        writeMemory(dir, "fact-a.md", "name: fact-a\ndescription: fact a\ntype: lesson",
            "a links onward to [b2](sub/fact-c.md)");
        writeMemory(dir, "fact-b.md", "name: fact-b\ndescription: fact b\ntype: lesson", "leaf");
        Files.writeString(dir.resolve("sub").resolve("fact-c.md"),
            "---\nname: fact-c\ndescription: fact c\ntype: lesson\n---\nleaf");

        Map<String, Object> report = maint(fqn -> null)
            .loadSources(List.of(dir.resolve("MEMORY.md")), false, 5, 200, 2_000_000L);
        // v2.2.3: the index itself is a link hub, not knowledge — 3 linked facts, no junk row.
        assertEquals(3, report.get("loaded"), "wikilink + mdlink + transitive mdlink; index skipped");
        assertEquals(3L, store.count());
        assertTrue(((List<?>) report.get("skipped")).isEmpty());
    }

    @Test
    void load_link_cycle_terminates(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md", "name: a\ndescription: a\ntype: lesson", "see [[b]]");
        writeMemory(dir, "b.md", "name: b\ndescription: b\ntype: lesson", "see [[a]]");
        Map<String, Object> report = maint(fqn -> null)
            .loadSources(List.of(dir.resolve("a.md")), false, 5, 200, 2_000_000L);
        assertEquals(2, report.get("loaded"), "a↔b crawled once each");
    }

    @Test
    void load_caps_are_honest_not_silent(@TempDir Path dir) throws IOException {
        writeMemory(dir, "a.md", "name: a\ndescription: a\ntype: lesson", "x");
        writeMemory(dir, "b.md", "name: b\ndescription: b\ntype: lesson", "x");
        writeMemory(dir, "c.md", "name: c\ndescription: c\ntype: lesson", "x");
        Map<String, Object> report = maint(fqn -> null)
            .loadSources(List.of(dir), false, 5, 2, 2_000_000L);
        assertEquals(2, report.get("loaded"), "max-files cap");
        assertFalse(((List<?>) report.get("skipped")).isEmpty(), "the drop is reported, not silent");
    }

    @Test
    void load_recursive_walks_subdirectories(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested").resolve("deep.md"),
            "---\nname: deep\ndescription: deep note\ntype: lesson\n---\nbody");
        assertEquals(0, maint(fqn -> null).load(dir, false).get("loaded"),
            "top-level listing does not see nested files");
        assertEquals(1, maint(fqn -> null).load(dir, true).get("loaded"),
            "recursive mode walks subdirectories");
    }

    @Test
    void load_skips_unchanged_files_entirely(@TempDir Path dir) throws IOException {
        // Sprint 21b (Harald): every load rewrote every entry — logical count stable but
        // the H2 file grew each click. An unchanged source must cause NO write at all.
        writeMemory(dir, "a.md", "name: a\ndescription: fact a\ntype: lesson", "body a");
        writeMemory(dir, "b.md", "name: b\ndescription: fact b\ntype: lesson", "body b");
        ExperienceMaintenance m = maint(fqn -> null);
        assertEquals(2, m.load(dir, true).get("loaded"));

        Map<String, Object> second = m.load(dir, true);
        assertEquals(0, second.get("loaded"), "no rewrite of unchanged sources");
        assertEquals(2, second.get("unchanged"));
        assertEquals(2, second.get("files"), "files = loaded + unchanged");
        assertEquals(2L, store.count());

        writeMemory(dir, "a.md", "name: a\ndescription: fact a CHANGED\ntype: lesson", "body a2");
        Map<String, Object> third = m.load(dir, true);
        assertEquals(1, third.get("loaded"), "changed source is re-ingested");
        assertEquals(1, third.get("unchanged"));
        assertEquals(2L, store.count());
    }

    @Test
    void loader_version_bump_reingests_unchanged_files_once(@TempDir Path dir) throws IOException {
        // v2.2.6 (find #14): skip-unchanged blocked retroactive enrichment — an entry
        // stored under an OLDER loader fingerprint must re-ingest even though the file
        // bytes are identical; within one version idempotency stays byte-strict.
        writeMemory(dir, "a.md", "name: a\ndescription: fact a\ntype: lesson", "body");
        String content = java.nio.file.Files.readString(dir.resolve("a.md"));
        ExperienceMaintenance m = maint(fqn -> null);
        assertEquals(1, m.load(dir, true).get("loaded"));

        // Simulate a pre-bump store: rewrite the entry with the OLD (content-only) hash.
        String sourceRef = "memory:" + dir.resolve("a.md").toAbsolutePath().normalize();
        store.deleteBySource(sourceRef);
        store.putWithSource(ExperienceEntry.of(
                SymbolFact.of("lesson", "fact a", Confidence.MEDIUM).build())
                .status(ExperienceEntry.ACCEPTED).build(),
            sourceRef, "0000-old-loader-hash");
        assertEquals(1, m.load(dir, true).get("loaded"), "stale fingerprint → re-ingested");

        Map<String, Object> again = m.load(dir, true);
        assertEquals(0, again.get("loaded"), "current fingerprint → byte-strict skip");
        assertEquals(1, again.get("unchanged"));
    }

    @Test
    void load_indexes_the_name_as_a_symptom_cue(@TempDir Path dir) throws IOException {
        // v2.2.5 dogfood find #13: the frontmatter NAME is where cue-dense phrasing lives
        // ("Tauri webview renders blank on aarch64") while the description may use other
        // words ("stays the GTK background colour") — recall by "blank webview" missed
        // the entry because the loader dropped the name entirely.
        writeMemory(dir, "webkit.md",
            "name: Tauri webview renders blank on aarch64\n"
                + "description: DMABUF compositor fails silently; content area stays the GTK background colour\n"
                + "type: reference",
            "fix body");
        assertEquals(1, maint(fqn -> null).load(dir, true).get("loaded"));

        ExperienceRetrieval retrieval = new ExperienceRetrieval(store, () -> null);
        Map<String, Object> r = retrieval.recall(new RecallQuery(null, null, null, "blank webview", null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"),
            "name tokens are recallable symptom cues");
    }

    @Test
    void load_skips_contentless_index_files_but_follows_their_links(@TempDir Path dir) throws IOException {
        // v2.2.3 dogfood find: MEMORY.md-style indexes ingested as junk rows ("MEMORY", "''").
        Files.writeString(dir.resolve("MEMORY.md"), "- [Fact](fact.md) — hook\n");
        writeMemory(dir, "fact.md", "name: f\ndescription: a real fact\ntype: lesson", "body");
        Map<String, Object> report = maint(fqn -> null).loadSources(
            java.util.List.of(dir.resolve("MEMORY.md")), false, 5, 200, 2_000_000L);
        assertEquals(1, report.get("loaded"), "index skipped, linked fact ingested");
        assertEquals(1L, store.count());
    }

    @Test
    void load_derives_summary_from_first_content_line_when_no_frontmatter(@TempDir Path dir) throws IOException {
        // CLAUDE.md-style files: no frontmatter, but the body IS the knowledge.
        Files.writeString(dir.resolve("CLAUDE.md"),
            "# Collaboration rules\n\nAlways use goja before shell text tools.\n");
        assertEquals(1, maint(fqn -> null).load(dir, false).get("loaded"));
        StoredEntry e = store.all().get(0);
        assertEquals("Collaboration rules", e.summary(), "summary derived from the first heading, not the filename");
    }

    @Test
    void load_ingests_mdc_files_from_directories(@TempDir Path dir) throws IOException {
        // Sprint 21b (item C2): Cursor project rules are .mdc — directory crawls accept them.
        Files.writeString(dir.resolve("rule.mdc"),
            "---\ndescription: prefer composition\n---\nCursor rule body");
        writeMemory(dir, "plain.md", "name: p\ndescription: plain\ntype: lesson", "x");
        assertEquals(2, maint(fqn -> null).load(dir, true).get("loaded"),
            ".mdc crawled alongside .md");
    }

    @Test
    void default_caps_are_runaway_backstops_not_tuning_values(@TempDir Path dir) throws IOException {
        // Sprint 21b (item C): "I want everything you can find" — a memory tree LARGER than
        // the old tuning caps (depth 5 / 200 files) must ingest COMPLETELY with the defaults.
        for (int i = 0; i < 210; i++) {
            writeMemory(dir, "n" + i + ".md", "name: n" + i + "\ndescription: note " + i + "\ntype: lesson", "x");
        }
        Path deep = dir;
        for (int i = 0; i < 7; i++) {
            deep = deep.resolve("d" + i);
        }
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("deepest.md"),
            "---\nname: deepest\ndescription: seven levels down\ntype: lesson\n---\nbody");

        Map<String, Object> report = maint(fqn -> null).load(dir, true);
        assertEquals(211, report.get("loaded"), "210 flat + 1 at depth 7 — nothing dropped");
        assertTrue(((List<?>) report.get("skipped")).isEmpty(), "no backstop fired");
    }

    @Test
    void load_without_path_uses_default_roots(@TempDir Path dir) throws IOException {
        writeMemory(dir, "seed.md", "name: s\ndescription: seeded\ntype: domain_fact", "x");
        ExperienceMaintenance withRoots =
            new ExperienceMaintenance(store, fqn -> null, () -> List.of(dir));
        assertTrue(withRoots.hasDefaultRoots());
        assertEquals(1, withRoots.load(null, false).get("loaded"));

        ExperienceMaintenance noRoots = maint(fqn -> null);
        assertFalse(noRoots.hasDefaultRoots());
        assertTrue(noRoots.load(null, false).containsKey("error"),
            "no path + no roots = explicit error");
    }

    // --- Sprint 21a (item I): non-Java anchors are opaque to JDT maintenance --------------

    @Test
    void refresh_never_supersedes_a_non_java_anchor() {
        String rustId = store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "peel stops at closing quote", Confidence.HIGH)
                    .symbol("manager_service::build_recall_script").build())
            .language("rust").status(ExperienceEntry.ACCEPTED).build());
        String javaId = store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", "gone type", Confidence.HIGH)
                    .symbol("com.gone.Removed").build())
            .status(ExperienceEntry.ACCEPTED).build());

        // A resolver that resolves NOTHING — the destructive case for foreign anchors.
        Map<String, Object> report = maint(fqn -> Boolean.FALSE).refresh();
        assertEquals(1, report.get("checked"), "only the Java anchor is judged");
        assertEquals(1, report.get("non_java"));
        assertEquals(1, ((List<?>) report.get("staled")).size());

        StoredEntry rust = store.all().stream().filter(e -> e.id().equals(rustId)).findFirst().orElseThrow();
        assertEquals(ExperienceEntry.ACCEPTED, rust.status(), "Rust anchor survives refresh untouched");
        assertEquals("rust", rust.language());
        StoredEntry java = store.all().stream().filter(e -> e.id().equals(javaId)).findFirst().orElseThrow();
        assertEquals(ExperienceEntry.SUPERSEDED, java.status(), "genuinely stale Java pointer still superseded");
    }

    @Test
    void load_does_not_flag_non_java_symbols_stale(@TempDir Path dir) throws IOException {
        writeMemory(dir, "rust.md",
            "name: n\ndescription: d\ntype: lesson\nsymbol: gateway::forward\nlanguage: rust", "body");
        Map<String, Object> report = maint(fqn -> Boolean.FALSE).load(dir);
        assertEquals(1, report.get("loaded"));
        assertEquals(0, ((List<?>) report.get("stale")).size(),
            "a Rust anchor is not judged by the JDT resolver at ingest");
        assertEquals("rust", store.all().get(0).language(), "frontmatter language persisted");
    }

    @Test
    void wipe_clears_everything() {
        store.put(SymbolFact.of("lesson", "a", Confidence.LOW).symbol("com.a.Foo").build());
        store.put(SymbolFact.of("lesson", "b", Confidence.LOW).symbol("com.b.Bar").build());
        Map<String, Object> report = maint(fqn -> null).wipe();
        assertEquals(2L, report.get("removed"));
        assertEquals(0L, store.count());
    }
}
