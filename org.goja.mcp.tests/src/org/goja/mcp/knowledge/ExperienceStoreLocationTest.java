package org.goja.mcp.knowledge;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21a Stage 1 (items A+H) — store location + continuity: the user-shared store
 * (default), AUTO_SERVER concurrent attach, and the one-time recovery sweep of orphaned
 * per-session stores (which earlier releases deleted on clean shutdown).
 */
class ExperienceStoreLocationTest {

    @Test
    void shared_store_honors_dir_override_and_persists(@TempDir Path dir) {
        System.setProperty("goja.experience.shared.dir", dir.toString());
        try {
            String id;
            try (H2ExperienceStore store = H2ExperienceStore.openShared()) {
                id = store.put(SymbolFact.of("lesson", "user-level knowledge", Confidence.HIGH).build());
            }
            assertTrue(Files.isRegularFile(dir.resolve("experience.mv.db")),
                "shared store file lives directly at the shared dir");
            try (H2ExperienceStore store = H2ExperienceStore.openShared()) {
                assertEquals(1L, store.count(), "shared store persists across reopen");
                assertTrue(store.get(id).isPresent());
            }
        } finally {
            System.clearProperty("goja.experience.shared.dir");
        }
    }

    @Test
    void auto_server_allows_two_concurrent_stores_on_one_file(@TempDir Path dir) {
        // Without AUTO_SERVER the second open would fail on the H2 single-JVM file lock.
        try (H2ExperienceStore first = H2ExperienceStore.open(dir);
                H2ExperienceStore second = H2ExperienceStore.open(dir)) {
            second.put(SymbolFact.of("lesson", "written via the second attach", Confidence.MEDIUM).build());
            assertEquals(1L, first.count(), "write through one connection visible to the other");
        }
    }

    // --- recovery sweep -------------------------------------------------------------------

    /** An orphaned pre-21a session store: v1 schema (no facets), one row per given id. */
    private static void createOrphan(Path sessionDir, String... entryIds) throws Exception {
        Path base = sessionDir.resolve("goja-experience").resolve("experience");
        Files.createDirectories(base.getParent());
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:file:" + base.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE");
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE experience_entry (
                    id              VARCHAR(64) PRIMARY KEY,
                    type            VARCHAR(64),
                    scope_kind      VARCHAR(32),
                    symbol_fqn      VARCHAR(1024),
                    package_name    VARCHAR(512),
                    operation       VARCHAR(128),
                    status          VARCHAR(32) DEFAULT 'candidate',
                    confidence      VARCHAR(16),
                    fault_owner     VARCHAR(16),
                    external_system VARCHAR(256),
                    summary         VARCHAR(4096),
                    source_ref      VARCHAR(512),
                    body_json       CLOB,
                    created_at      TIMESTAMP,
                    updated_at      TIMESTAMP
                )""");
            s.execute("CREATE TABLE experience_symptom (entry_id VARCHAR(64), symptom VARCHAR(512),"
                + " PRIMARY KEY (entry_id, symptom))");
            s.execute("CREATE TABLE experience_link (entry_id VARCHAR(64), rel VARCHAR(32),"
                + " target VARCHAR(1024), PRIMARY KEY (entry_id, rel, target))");
            for (String id : entryIds) {
                s.execute("INSERT INTO experience_entry (id, type, status, confidence, summary,"
                    + " body_json, created_at, updated_at) VALUES ('" + id + "', 'lesson',"
                    + " 'accepted', 'high', 'orphaned lesson " + id + "',"
                    + " '{\"type\":\"lesson\",\"summary\":\"orphaned lesson " + id + "\"}',"
                    + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
                s.execute("INSERT INTO experience_symptom VALUES ('" + id + "', 'orphan symptom')");
            }
        }
    }

    @Test
    void recovery_sweep_imports_orphans_dedups_and_is_idempotent(
            @TempDir Path workspaceRoot, @TempDir Path storeDir) throws Exception {
        createOrphan(workspaceRoot.resolve("aaa11111"), "orph-1");
        createOrphan(workspaceRoot.resolve("bbb22222"), "orph-1", "orph-2");   // duplicate + new

        try (H2ExperienceStore store = H2ExperienceStore.openAt(storeDir)) {
            store.setProvenance("goja", "/home/x/goja-mcp");

            Map<String, Object> report = store.recoverOrphans(workspaceRoot);
            assertEquals(2, report.get("imported"), "orph-1 + orph-2 (one of the orph-1s deduped)");
            assertEquals(1, report.get("duplicates"));
            assertEquals(2L, store.count());
            assertTrue(store.get("orph-1").isPresent());
            assertTrue(store.get("orph-2").isPresent());

            // Symptoms travel with the entry (recall by symptom must keep working).
            RecallQuery bySymptom = new RecallQuery(null, null, null, "orphan symptom", null);
            assertEquals(2, store.query(bySymptom).size());

            // Markers make the sweep idempotent.
            assertTrue(Files.exists(workspaceRoot.resolve("aaa11111")
                .resolve("goja-experience").resolve(".goja-recovered")));
            Map<String, Object> second = store.recoverOrphans(workspaceRoot);
            assertEquals(0, second.get("imported"), "second sweep is a no-op");
            assertEquals(2L, store.count());
        }
    }

    @Test
    void recovery_ignores_missing_root_and_null(@TempDir Path storeDir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(storeDir)) {
            assertEquals(0, store.recoverOrphans(null).get("imported"));
            assertEquals(0, store.recoverOrphans(storeDir.resolve("no-such")).get("imported"));
        }
    }
}
