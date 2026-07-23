package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jawata.mcp.knowledge.EmbeddingIndex;
import org.jawata.mcp.knowledge.EmbeddingService;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 27a Stage 6 (D5's first half) — embedding coverage is VISIBLE through
 * the tool path: {@code experience(kind=stats)} shows n-of-total while rows
 * are unembedded and total-of-total once the backfill has converged. Driven
 * through {@code tool.execute}, not a helper — the wired path or nothing.
 */
class StatsEmbeddingCoverageTest {

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

    private boolean embedderAvailable() {
        boolean up = EmbeddingService.shared().available();
        if (!up) {
            System.out.println("StatsEmbeddingCoverageTest: NOT RUN — no embedder"
                + " (the coverage block asserts nothing in this run)");
        }
        return up;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stats() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "stats");
        ToolResponse resp = tool.execute(args);
        assertTrue(resp.isSuccess());
        return (Map<String, Object>) resp.getData();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> entryLane(Map<String, Object> stats) {
        Object embedding = stats.get("embedding");
        assertNotNull(embedding, "stats carries the embedding block: " + stats);
        return (Map<String, Object>) ((Map<String, Object>) embedding).get("experience_entry");
    }

    /** The two states the plan's C6 exit names, driven through the tool. */
    @Test
    void stats_shows_n_of_total_during_backfill_and_total_of_total_after() {
        if (!embedderAvailable()) {
            return;
        }
        // Seed THROUGH the tool, as an IMPORT: a live record embeds on write,
        // but a restored backup does not — the backfill owns its coverage.
        // That is exactly the real n-of-total state (a restore, or an
        // identity bump) rather than an artificial one.
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "import");
        var entries = args.putArray("entries");
        int n = 1;
        for (String s : new String[] {
                "the first seeded lesson about queue retries",
                "the second seeded lesson about socket timeouts",
                "the third seeded lesson about cache warmup"}) {
            ObjectNode e = entries.addObject();
            e.put("id", "0a1b2c3d-0000-4000-8000-00000000000" + n++);
            e.put("type", "lesson");
            e.put("summary", s);
            e.put("status", "accepted");
        }
        assertTrue(tool.execute(args).isSuccess());

        Map<String, Object> before = entryLane(stats());
        long embeddedBefore = ((Number) before.get("embedded")).longValue();
        long totalBefore = ((Number) before.get("total")).longValue();
        assertEquals(3L, totalBefore, "three rows exist");
        assertTrue(embeddedBefore < totalBefore,
            "during the backfill window the stats show n of total, honestly: "
            + embeddedBefore + "/" + totalBefore);

        // Converge the backfill (the Stage-3b loop's unit), then re-read.
        EmbeddingIndex index = new EmbeddingIndex(
            (H2ExperienceStore) store, EmbeddingService.shared());
        while (index.backfill(1000) > 0) {
            // each pass persists rows; loop until the delta closes
        }
        assertEquals(0L, index.remainingUnembedded(), "backfill converged");

        Map<String, Object> after = entryLane(stats());
        assertEquals(((Number) after.get("total")).longValue(),
            ((Number) after.get("embedded")).longValue(),
            "after convergence the stats show total of total: " + after);
    }
}
