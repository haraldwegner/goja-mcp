package org.jawata.mcp.knowledge;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21a Stage 0 — versioned schema + migration runner (item B). A store created by
 * jawata-mcp v2.0.0 (pre-versioning, {@code CREATE TABLE IF NOT EXISTS}, no facets) must
 * migrate forward losslessly; a store from a NEWER resident must be refused; migration
 * backs the file up first; provenance + language facets are stamped on new writes.
 */
class SchemaMigrationsTest {

    private static String url(Path dir) {
        return "jdbc:h2:file:" + dir.resolve("jawata-experience").resolve("experience").toAbsolutePath()
            + ";DB_CLOSE_ON_EXIT=FALSE";
    }

    private static Connection connect(Path dir) throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url(dir));
        return ds.getConnection();
    }

    /** Recreate EXACTLY what jawata-mcp v2.0.0 wrote: the pre-versioning schema + one row. */
    private static void createV1Fixture(Path dir, String entryId) throws Exception {
        try (Connection c = connect(dir); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS experience_entry (
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
            s.execute("""
                CREATE TABLE IF NOT EXISTS experience_symptom (
                    entry_id VARCHAR(64),
                    symptom  VARCHAR(512),
                    PRIMARY KEY (entry_id, symptom)
                )""");
            s.execute("""
                CREATE TABLE IF NOT EXISTS experience_link (
                    entry_id VARCHAR(64),
                    rel      VARCHAR(32),
                    target   VARCHAR(1024),
                    PRIMARY KEY (entry_id, rel, target)
                )""");
            s.execute("INSERT INTO experience_entry (id, type, symbol_fqn, status, confidence, summary,"
                + " body_json, created_at, updated_at) VALUES ('" + entryId + "', 'lesson',"
                + " 'com.example.Legacy', 'accepted', 'high', 'a v2.0.0-era lesson',"
                + " '{\"type\":\"lesson\",\"summary\":\"a v2.0.0-era lesson\",\"status\":\"accepted\"}',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
        }
    }

    private static String scalar(Path dir, String sql) throws Exception {
        try (Connection c = connect(dir); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    @Test
    void fresh_store_lands_at_latest_with_facet_columns(@TempDir Path dir) throws Exception {
        String id;
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            id = store.put(SymbolFact.of("lesson", "fresh store", Confidence.MEDIUM).build());
        }
        assertEquals(String.valueOf(SchemaMigrations.LATEST),
            scalar(dir, "SELECT version FROM schema_version"), "fresh install is at LATEST");
        assertEquals("java",
            scalar(dir, "SELECT language FROM experience_entry WHERE id = '" + id + "'"),
            "new writes default language=java");
        // Sprint 21b (v3): the skip-unchanged hash column exists on a fresh install.
        assertNull(scalar(dir, "SELECT source_hash FROM experience_entry WHERE id = '" + id + "'"),
            "plain put has no source hash (column exists, value null)");
    }

    @Test
    void v1_store_migrates_forward_losslessly(@TempDir Path dir) throws Exception {
        createV1Fixture(dir, "legacy-1");

        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count(), "v1 row survives the migration");
            assertTrue(store.get("legacy-1").isPresent(), "v1 body_json still readable");
            assertEquals("a v2.0.0-era lesson", store.get("legacy-1").get().get("summary"));
        }
        assertEquals(String.valueOf(SchemaMigrations.LATEST),
            scalar(dir, "SELECT version FROM schema_version"));
        assertEquals("java",
            scalar(dir, "SELECT language FROM experience_entry WHERE id = 'legacy-1'"),
            "existing rows backfilled to language=java");
        assertNull(scalar(dir, "SELECT workspace_id FROM experience_entry WHERE id = 'legacy-1'"),
            "pre-facet rows have no provenance (columns exist, values null)");
        assertNull(scalar(dir, "SELECT source_hash FROM experience_entry WHERE id = 'legacy-1'"),
            "v3: pre-hash rows have no source hash (column exists, value null)");

        try (Stream<Path> files = Files.list(dir.resolve("jawata-experience"))) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("experience-pre-migration-v1")),
                "a real migration writes a backup first");
        }
    }

    @Test
    void from_the_future_store_is_refused(@TempDir Path dir) throws Exception {
        try (Connection c = connect(dir); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE schema_version (version INT NOT NULL)");
            s.execute("INSERT INTO schema_version VALUES (" + (SchemaMigrations.LATEST + 97) + ")");
        }
        IllegalStateException e =
            assertThrows(IllegalStateException.class, () -> H2ExperienceStore.open(dir));
        assertTrue(e.getMessage().contains("newer"), "refusal names the version mismatch: " + e.getMessage());
    }

    @Test
    void reopen_after_migration_is_idempotent(@TempDir Path dir) throws Exception {
        createV1Fixture(dir, "legacy-2");
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count());
        }
        // Second open: already at LATEST — no re-migration, no second backup.
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count());
        }
        try (Stream<Path> files = Files.list(dir.resolve("jawata-experience"))) {
            assertEquals(1L, files.filter(p -> p.getFileName().toString().endsWith(".zip")).count(),
                "exactly one backup — from the one real migration");
        }
    }

    @Test
    void provenance_facets_stamped_on_write(@TempDir Path dir) throws Exception {
        String id;
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            store.setProvenance("jawata", "/home/x/CursorProjects/jawata-mcp");
            id = store.put(SymbolFact.of("lesson", "stamped", Confidence.MEDIUM).build());
        }
        assertEquals("jawata",
            scalar(dir, "SELECT workspace_id FROM experience_entry WHERE id = '" + id + "'"));
        assertEquals("/home/x/CursorProjects/jawata-mcp",
            scalar(dir, "SELECT project_id FROM experience_entry WHERE id = '" + id + "'"));
    }

    /**
     * Sprint 22b Stage 2 — the jawata-rebrand anchor migration (v4). A store carrying
     * entries anchored to the OLD product packages/classes ({@code org.goja.*},
     * {@code Goja*}/{@code IGoja*}) must have those anchors REWRITTEN — both halves,
     * package prefix AND class segment — for ALL entries, record-sourced included
     * (they exist in no file, so nothing else would ever re-anchor them). The proof is
     * FINDABILITY: recall by the NEW symbol name returns the entry. Foreign anchors
     * (other people's code) are untouched. Link targets that are old-package FQNs
     * rewrite too.
     */
    @Test
    void goja_anchors_are_rewritten_to_jawata_and_recallable_by_new_name(@TempDir Path dir) throws Exception {
        createV1Fixture(dir, "legacy-3");
        try (Connection c = connect(dir); Statement s = c.createStatement()) {
            // (a) a record-sourced entry (no source_ref — exists in NO file): the case the
            // migration exists to protect. Anchored to an old-package class + member.
            s.execute("INSERT INTO experience_entry (id, type, symbol_fqn, package_name, status,"
                + " summary, body_json, created_at, updated_at) VALUES ('rec-goja', 'lesson',"
                + " 'org.goja.mcp.GojaApplication#registerTools', 'org.goja.mcp', 'accepted',"
                + " 'a Cursor-recorded note', '{\"type\":\"lesson\",\"summary\":\"a Cursor-recorded note\"}',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // (b) an interface anchor (the .IGoja half of the class-segment rule).
            s.execute("INSERT INTO experience_entry (id, type, symbol_fqn, status, summary,"
                + " body_json, created_at, updated_at) VALUES ('rec-iface', 'api_contract',"
                + " 'org.goja.mcp.domain.IGojaService', 'accepted', 'iface note',"
                + " '{\"type\":\"api_contract\",\"summary\":\"iface note\"}',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // (c) a FOREIGN anchor — someone else's code: must be untouched.
            s.execute("INSERT INTO experience_entry (id, type, symbol_fqn, package_name, status,"
                + " summary, body_json, created_at, updated_at) VALUES ('orb-1', 'lesson',"
                + " 'com.jats2.pipeline.SlotManager#freeSlot', 'com.jats2.pipeline', 'accepted',"
                + " 'foreign lesson', '{\"type\":\"lesson\",\"summary\":\"foreign lesson\"}',"
                + " CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
            // (d) a link whose target is an old-package FQN.
            s.execute("INSERT INTO experience_link (entry_id, rel, target) VALUES"
                + " ('rec-goja', 'fixed_by', 'org.goja.mcp.knowledge.SchemaMigrations')");
        }

        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            // THE proof — findability by the NEW name through the recall API:
            var hits = store.query(new RecallQuery(
                "org.jawata.mcp.JawataApplication#registerTools", null, null, null, null));
            assertTrue(hits.stream().anyMatch(e -> e.id().equals("rec-goja")),
                "record-sourced note is recalled by its NEW symbol name after migration");
        }
        assertEquals("org.jawata.mcp.JawataApplication#registerTools",
            scalar(dir, "SELECT symbol_fqn FROM experience_entry WHERE id = 'rec-goja'"),
            "package prefix AND class segment rewritten");
        assertEquals("org.jawata.mcp",
            scalar(dir, "SELECT package_name FROM experience_entry WHERE id = 'rec-goja'"),
            "package anchor rewritten");
        assertEquals("org.jawata.mcp.domain.IJawataService",
            scalar(dir, "SELECT symbol_fqn FROM experience_entry WHERE id = 'rec-iface'"),
            "IGoja interface segment rewritten to IJawata");
        assertEquals("com.jats2.pipeline.SlotManager#freeSlot",
            scalar(dir, "SELECT symbol_fqn FROM experience_entry WHERE id = 'orb-1'"),
            "foreign anchors untouched");
        assertEquals("org.jawata.mcp.knowledge.SchemaMigrations",
            scalar(dir, "SELECT target FROM experience_link WHERE entry_id = 'rec-goja' AND rel = 'fixed_by'"),
            "old-package link target rewritten");
    }

    @Test
    void in_memory_store_migrates_without_backup(@TempDir Path dir) throws Exception {
        // dir==null → in-memory: fresh v0 → LATEST, no file, no backup, still functional.
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            String id = store.put(SymbolFact.of("lesson", "mem", Confidence.LOW).build());
            assertTrue(store.get(id).isPresent());
        }
        assertFalse(Files.exists(dir.resolve("jawata-experience")), "no file artifacts for in-memory");
    }
}
