package org.goja.mcp.knowledge;

import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.5.1 — workspace-scoped anchor judgment on the SHARED store. The live incident
 * (2026-07-08): a resident's post-load refresh judged FOREIGN-workspace anchors against
 * its own project set — `pipeline.SlotManager` doesn't resolve in the goja workspace →
 * FALSE → 304 entries superseded. A resident with a workspace identity may judge and
 * backfill ONLY entries stamped with that identity; a store WITHOUT identity (tests,
 * standalone) keeps the judge-everything semantics.
 */
class WorkspaceScopedJudgmentTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static String putAsserted(H2ExperienceStore store, String summary, String fqn) {
        return store.put(ExperienceEntry.of(
                SymbolFact.of("lesson", summary, Confidence.MEDIUM).symbol(fqn).build())
            .status(ExperienceEntry.ACCEPTED)
            .build());
    }

    private static StoredEntry bySummary(ExperienceStore store, String summary) {
        return store.all().stream()
            .filter(e -> summary.equals(e.summary()))
            .findFirst().orElseThrow(() -> new AssertionError("no entry with summary: " + summary));
    }

    @Test
    @DisplayName("THE incident shape: foreign-workspace anchor is NEVER judged — no supersede, reported foreign")
    void refresh_never_judges_foreign_workspace_anchors() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            store.setProvenance("orb-strategy", null);
            putAsserted(store, "orb lesson with orb anchor", "pipeline.SlotManager#freeSlot");

            // The goja resident's seat: own identity differs; its resolver sees nothing.
            store.setProvenance("goja", null);
            Map<String, Object> report =
                new ExperienceMaintenance(store, fqn -> Boolean.FALSE).refresh();

            assertEquals(ExperienceEntry.ACCEPTED,
                bySummary(store, "orb lesson with orb anchor").status(),
                "a foreign workspace's anchor must never be superseded");
            assertEquals(1, report.get("foreign"));
            assertEquals(0, report.get("checked"));
            assertTrue(((List<?>) report.get("staled")).isEmpty());
        }
    }

    @Test
    @DisplayName("own-workspace anchors keep today's judgment semantics")
    void refresh_still_judges_own_workspace_anchors() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            store.setProvenance("goja", null);
            putAsserted(store, "own dead anchor", "com.gone.Removed");

            Map<String, Object> report =
                new ExperienceMaintenance(store, fqn -> Boolean.FALSE).refresh();

            assertEquals(ExperienceEntry.SUPERSEDED, bySummary(store, "own dead anchor").status());
            assertEquals(1, ((List<?>) report.get("staled")).size());
        }
    }

    @Test
    @DisplayName("unstamped entries count as foreign once the store has an identity")
    void refresh_skips_unstamped_entries_when_identity_is_set() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            putAsserted(store, "unstamped legacy anchor", "com.gone.Removed");

            store.setProvenance("goja", null);
            Map<String, Object> report =
                new ExperienceMaintenance(store, fqn -> Boolean.FALSE).refresh();

            assertEquals(ExperienceEntry.ACCEPTED,
                bySummary(store, "unstamped legacy anchor").status());
            assertEquals(1, report.get("foreign"));
        }
    }

    @Test
    @DisplayName("a store WITHOUT identity keeps judge-everything semantics (tests/standalone)")
    void refresh_without_identity_judges_everything() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            putAsserted(store, "no identity anywhere", "com.gone.Removed");

            new ExperienceMaintenance(store, fqn -> Boolean.FALSE).refresh();

            assertEquals(ExperienceEntry.SUPERSEDED,
                bySummary(store, "no identity anywhere").status());
        }
    }

    @Test
    @DisplayName("backfill is workspace-scoped too: only own-stamped entries gain anchors")
    void backfill_anchors_own_workspace_entries_only(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            store.setProvenance("orb-strategy", null);
            store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "foreign text naming a local type", Confidence.MEDIUM)
                        .details("Lives in `HelloWorld.printGreeting`.").build())
                .status(ExperienceEntry.ACCEPTED)
                .build());

            store.setProvenance("goja", null);
            store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", "own text naming a local type", Confidence.MEDIUM)
                        .details("Lives in `HelloWorld.printGreeting`.").build())
                .status(ExperienceEntry.ACCEPTED)
                .build());

            Map<String, Object> report = new ExperienceMaintenance(
                store, fqn -> null, List::of, () -> service).backfillAutoAnchors();

            assertEquals(1, report.get("anchored"), "only the own-stamped entry: " + report);
            assertEquals("com.example.HelloWorld#printGreeting",
                bySummary(store, "own text naming a local type").symbolFqn());
            assertNull(bySummary(store, "foreign text naming a local type").symbolFqn(),
                "a resident never anchors a foreign workspace's entry against its own types");
        }
    }
}
