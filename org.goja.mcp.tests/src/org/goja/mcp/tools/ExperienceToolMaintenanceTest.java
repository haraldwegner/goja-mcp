package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.mcp.knowledge.ExperienceStore;
import org.goja.mcp.knowledge.H2ExperienceStore;
import org.goja.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 21 Stage 4 — load / refresh / wipe / promote through the experience front door. */
class ExperienceToolMaintenanceTest {

    private ObjectMapper mapper;
    private ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess());
        return (Map<String, Object>) r.getData();
    }

    private String recordOne() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", "guard lifecycle");
        a.put("symbol", "com.example.WorkflowCoordinator");
        return (String) data(tool.execute(a)).get("id");
    }

    @Test
    void load_via_tool_seeds_from_directory(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("m.md"),
            "---\nname: n\ndescription: a domain note\ntype: domain_fact\n---\nbody [[x]]");
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        Map<String, Object> d = data(tool.execute(a));
        assertEquals(1, d.get("loaded"));
        assertEquals(1L, store.count());
    }

    @Test
    void load_without_recursive_arg_walks_subdirectories(@TempDir Path dir) throws IOException {
        // Sprint 21b (item C): the crawler finds everything — recursive is the DEFAULT.
        Files.createDirectory(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested").resolve("deep.md"),
            "---\nname: deep\ndescription: nested note\ntype: lesson\n---\nbody");
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        assertEquals(1, data(tool.execute(a)).get("loaded"), "no recursive arg → walks subdirs");
    }

    @Test
    void load_recursive_false_stays_flat(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("nested"));
        Files.writeString(dir.resolve("nested").resolve("deep.md"),
            "---\nname: deep\ndescription: nested note\ntype: lesson\n---\nbody");
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        a.put("recursive", false);
        assertEquals(0, data(tool.execute(a)).get("loaded"), "explicit recursive:false honored");
    }

    @Test
    void load_without_path_and_without_roots_fails() {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        assertFalse(tool.execute(a).isSuccess(), "no path + no configured default roots");
    }

    // --- Sprint 21b (item D): refresh is automatic after load / import --------------------

    @Test
    void load_auto_refreshes_preexisting_stale_entries(@TempDir Path dir) throws IOException {
        recordOne(); // symbol com.example.WorkflowCoordinator, currently unresolvable=stale
        Files.writeString(dir.resolve("m.md"),
            "---\nname: n\ndescription: unrelated note\ntype: domain_fact\n---\nbody");
        ExperienceTool staleWorld = new ExperienceTool(() -> null, store,
            java.util.List::of, fqn -> Boolean.FALSE);
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        a.put("path", dir.toString());
        Map<String, Object> d = data(staleWorld.execute(a));
        assertEquals(1, d.get("loaded"));
        Map<?, ?> refresh = (Map<?, ?>) d.get("refresh");
        assertEquals(1, ((java.util.List<?>) refresh.get("staled")).size(),
            "pre-existing dead pointer flagged by load itself — no explicit refresh call");
    }

    @Test
    void import_auto_refreshes_the_ingested_entries(@TempDir Path dir) throws IOException {
        recordOne();
        ObjectNode ex = mapper.createObjectNode();
        ex.put("kind", "export");
        Object entries = data(tool.execute(ex)).get("entries");
        ObjectNode wipe = mapper.createObjectNode();
        wipe.put("kind", "wipe");
        data(tool.execute(wipe));

        ExperienceTool staleWorld = new ExperienceTool(() -> null, store,
            java.util.List::of, fqn -> Boolean.FALSE);
        ObjectNode im = mapper.createObjectNode();
        im.put("kind", "import");
        im.set("entries", mapper.valueToTree(entries));
        Map<String, Object> d = data(staleWorld.execute(im));
        Map<?, ?> refresh = (Map<?, ?>) d.get("refresh");
        assertEquals(1, ((java.util.List<?>) refresh.get("staled")).size(),
            "imported dead pointer flagged by import itself");
    }

    @Test
    void autoRefresh_never_throws_even_on_a_broken_resolver() {
        recordOne();
        ExperienceTool broken = new ExperienceTool(() -> null, store,
            java.util.List::of, fqn -> { throw new IllegalStateException("boom"); });
        Map<String, Object> r = broken.autoRefresh();
        assertTrue(r.containsKey("error"), "the startup auto-refresh path must never throw");
    }

    // --- Sprint 21a (items C+G): default roots + the confirm-gated reseed -----------------

    @Test
    void load_without_path_seeds_from_default_roots(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("m.md"),
            "---\nname: n\ndescription: seeded note\ntype: domain_fact\n---\nbody");
        ExperienceTool rooted = new ExperienceTool(() -> null, store, () -> java.util.List.of(dir));
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "load");
        assertEquals(1, data(rooted.execute(a)).get("loaded"));
    }

    @Test
    void reseed_requires_confirm_then_wipes_and_reloads(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("m.md"),
            "---\nname: n\ndescription: seeded note\ntype: domain_fact\n---\nbody");
        ExperienceTool rooted = new ExperienceTool(() -> null, store, () -> java.util.List.of(dir));
        recordOneVia(rooted);
        assertEquals(1L, store.count());

        ObjectNode noConfirm = mapper.createObjectNode();
        noConfirm.put("kind", "reseed");
        assertFalse(rooted.execute(noConfirm).isSuccess(), "reseed is confirm-gated");
        assertEquals(1L, store.count(), "nothing wiped without confirm");

        ObjectNode confirmed = mapper.createObjectNode();
        confirmed.put("kind", "reseed");
        confirmed.put("confirm", true);
        Map<String, Object> d = data(rooted.execute(confirmed));
        assertEquals(1L, d.get("removed"), "the hand-recorded entry was wiped");
        assertEquals(1, d.get("loaded"), "the seed file was reloaded");
        assertEquals(1L, store.count());
    }

    private void recordOneVia(ExperienceTool t) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", "to be wiped");
        t.execute(a);
    }

    @Test
    void wipe_via_tool_clears() {
        recordOne();
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "wipe");
        assertEquals(1L, data(tool.execute(a)).get("removed"));
        assertEquals(0L, store.count());
    }

    @Test
    void promote_via_tool_sets_status() {
        String id = recordOne();
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "promote");
        a.put("id", id);
        Map<String, Object> d = data(tool.execute(a));
        assertEquals("accepted", d.get("status"));
        assertEquals(true, d.get("changed"));
    }

    @Test
    void refresh_via_tool_no_project_skips() {
        recordOne();
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "refresh");
        Map<String, Object> d = data(tool.execute(a));
        assertEquals(1, d.get("checked"));
        assertEquals(1, d.get("skipped"));
    }
}
