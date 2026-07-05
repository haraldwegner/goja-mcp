package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.mcp.knowledge.ExperienceStore;
import org.goja.mcp.knowledge.H2ExperienceStore;
import org.goja.mcp.knowledge.RecallQuery;
import org.goja.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 21a Stage 6 (item G) — hygiene verbs: prune / dedup / compact. */
class ExperienceToolHygieneTest {

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
        assertTrue(r.isSuccess(), () -> "expected success: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    private String record(String summary, String symbol) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", summary);
        if (symbol != null) {
            a.put("symbol", symbol);
        }
        return (String) data(tool.execute(a)).get("id");
    }

    private void setStatus(String id, String status) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "promote");
        a.put("id", id);
        a.put("status", status);
        data(tool.execute(a));
    }

    private ToolResponse exec(String kind, java.util.function.Consumer<ObjectNode> fill) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", kind);
        fill.accept(a);
        return tool.execute(a);
    }

    @Test
    void prune_removes_only_aged_rejected_or_superseded() {
        record("stays active", null);
        setStatus(record("was rejected", null), "rejected");
        setStatus(record("was superseded", null), "superseded");

        // Everything is seconds old: the default 30-day threshold removes nothing...
        assertEquals(0, data(exec("prune", a -> { })).get("removed"));
        // ...days=0 removes exactly the two dead entries, never the active one.
        assertEquals(2, data(exec("prune", a -> a.put("days", 0))).get("removed"));
        assertEquals(1L, store.count());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dedup_reports_then_merges_on_confirm() {
        String older = record("goja peel stops at quote", "com.example.Hook");
        String newer = record("GOJA peel stops at   quote", "com.example.Hook");   // alias-equal
        setStatus(older, "accepted");                       // status beats recency
        record("something unrelated", "com.example.Other");

        Map<String, Object> surfaced = data(exec("dedup", a -> { }));
        assertEquals(1, surfaced.get("group_count"));
        assertEquals(0, surfaced.get("merged"), "without confirm dedup only reports");
        List<Map<String, Object>> groups = (List<Map<String, Object>>) surfaced.get("groups");
        assertEquals(older, groups.get(0).get("keep"), "accepted wins over newer candidate");

        Map<String, Object> merged = data(exec("dedup", a -> a.put("confirm", true)));
        assertEquals(1, merged.get("merged"));
        // The loser is superseded: recall no longer returns it, curation list still can.
        List<?> hits = store.query(new RecallQuery("com.example.Hook", null, null, null, null));
        assertEquals(1, hits.size());
        assertEquals(1, data(exec("list", a -> a.put("status", "superseded"))).get("count"));
        assertTrue(store.get(newer).isPresent(), "merge supersedes, never deletes");
    }

    @Test
    void compact_reclaims_space_and_store_stays_usable(@TempDir Path dir) {
        try (H2ExperienceStore fileStore = H2ExperienceStore.openAt(dir)) {
            ExperienceTool fileTool = new ExperienceTool(() -> null, fileStore);
            for (int i = 0; i < 50; i++) {
                ObjectNode a = mapper.createObjectNode();
                a.put("kind", "record");
                a.put("type", "lesson");
                a.put("summary", "churn " + i + " ".repeat(500));
                fileTool.execute(a);
            }
            ObjectNode wipe = mapper.createObjectNode();
            wipe.put("kind", "wipe");
            fileTool.execute(wipe);

            ObjectNode compact = mapper.createObjectNode();
            compact.put("kind", "compact");
            @SuppressWarnings("unchecked")
            Map<String, Object> report = (Map<String, Object>) fileTool.execute(compact).getData();
            assertEquals(true, report.get("compacted"));
            assertTrue(((Number) report.get("bytes_after")).longValue() > 0);

            // The connection was swapped under the hood — the store must still work.
            ObjectNode after = mapper.createObjectNode();
            after.put("kind", "record");
            after.put("type", "lesson");
            after.put("summary", "post-compact write");
            assertTrue(fileTool.execute(after).isSuccess(), "store usable after compact reopen");
            assertEquals(1L, fileStore.count());
        }
    }

    @Test
    void compact_is_a_safe_noop_in_memory() {
        Map<String, Object> report = data(exec("compact", a -> { }));
        assertEquals(false, report.get("compacted"));
    }
}
