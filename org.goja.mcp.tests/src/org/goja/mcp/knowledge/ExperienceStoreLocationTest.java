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
    void open_retries_transient_lock_contention(@TempDir Path dir) {
        // v2.2.4 live find: a runtime swap restarts residents seconds apart; H2 refuses
        // the young lock ("Lock file recently modified") and the resident silently fell
        // back to a NON-PERSISTENT in-memory store (split-brain with the peer).
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        H2ExperienceStore opened = H2ExperienceStore.openWithRetry(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IllegalStateException("failed to open experience store: "
                    + "Error opening database: \"Lock file recently modified\" [8000-224]");
            }
            return H2ExperienceStore.openAt(dir);
        }, 5, 1);
        assertEquals(3, calls.get(), "two transient failures retried, third succeeds");
        opened.close();
    }

    @Test
    void open_rethrows_non_transient_failures_immediately() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        try {
            H2ExperienceStore.openWithRetry(() -> {
                calls.incrementAndGet();
                throw new IllegalStateException("schema is v99, newer than this resident supports");
            }, 5, 1);
            throw new AssertionError("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertEquals(1, calls.get(), "a real failure is not retried");
        }
    }

    @Test
    void peer_connection_self_heals_after_a_compact(@TempDir Path dir) {
        // Sprint 21b: compact = SHUTDOWN COMPACT on the SHARED AUTO_SERVER database —
        // it closes the db for EVERY attached resident. The peer must reconnect, not
        // die (live "clean up" left the other workspace permanently unreachable).
        try (H2ExperienceStore a = H2ExperienceStore.openAt(dir);
             H2ExperienceStore b = H2ExperienceStore.openAt(dir)) {
            b.put(SymbolFact.of("lesson", "before compact", Confidence.MEDIUM).build());
            a.compact();
            b.put(SymbolFact.of("lesson", "after compact", Confidence.MEDIUM).build());
            assertEquals(2L, b.count(), "peer keeps working after another resident compacts");
        }
    }

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
