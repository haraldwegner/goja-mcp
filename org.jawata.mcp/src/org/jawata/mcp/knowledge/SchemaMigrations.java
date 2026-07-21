package org.jawata.mcp.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sprint 21a (item B): versioned schema + ordered, additive-first migrations for the
 * experience store — replacing the v2.0.0 implicit {@code CREATE TABLE IF NOT EXISTS}
 * contract, which creates a fresh store correctly but can never evolve an existing one.
 *
 * <p>Version detection: a {@code schema_version} table wins; a pre-versioning v2.0.0
 * store (has {@code experience_entry}, no version table) is version 1; an empty database
 * is version 0. A store whose version is <em>newer</em> than {@link #LATEST} is refused
 * read-write (fail clearly, don't corrupt). A real migration (version ≥ 1) writes an H2
 * online {@code BACKUP TO} zip beside the store file first, so a failed step is
 * recoverable.</p>
 *
 * <p>Migration steps are cumulative: v1 = the v2.0.0 base schema; v2 = the Sprint-21a
 * provenance + language facets ({@code workspace_id}, {@code project_id},
 * {@code language} — backfilled {@code 'java'}, see item I); v3 = the Sprint-21b
 * {@code source_hash} (skip-unchanged loads — an unmodified memory file causes no
 * write, so repeated loads stop growing the MVStore file); v4 = the Sprint-22b jawata
 * rebrand — code anchors REWRITTEN ({@code org.goja.*} → {@code org.jawata.*} package
 * prefix AND {@code Goja*}/{@code IGoja*} → {@code Jawata*}/{@code IJawata*} class
 * segments) across {@code symbol_fqn}, {@code package_name} and FQN link targets, for
 * ALL entries — record-sourced ones exist in no file, so nothing else would ever
 * re-anchor them; superseding instead of rewriting would kill their findability.</p>
 */
final class SchemaMigrations {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    /** Current schema version — bump together with a new {@code migrateToVn} step. */
    static final int LATEST = 7;

    private SchemaMigrations() {
    }

    /**
     * Bring the connected database to {@link #LATEST}. Returns a report
     * ({@code from}/{@code to}/{@code migrated}/{@code backup}). {@code storeDir} is the
     * directory holding the store file ({@code null} for in-memory — no backup possible).
     *
     * @throws IllegalStateException when the store is from a newer resident.
     */
    static Map<String, Object> migrate(Connection conn, Path storeDir) throws SQLException {
        int from = detectVersion(conn);
        if (from > LATEST) {
            throw new IllegalStateException("experience store schema is v" + from
                + ", newer than this resident supports (v" + LATEST
                + ") — refusing read-write open; upgrade jawata or use the newer resident");
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("from", from);
        report.put("to", LATEST);
        if (from == LATEST) {
            report.put("migrated", false);
            return report;
        }
        if (from >= 1 && storeDir != null) {
            Path backup = storeDir.resolve("experience-pre-migration-v" + from + ".zip");
            try (Statement s = conn.createStatement()) {
                s.execute("BACKUP TO '" + backup.toAbsolutePath().toString().replace("'", "''") + "'");
            }
            report.put("backup", backup.toString());
            log.info("Experience store backup before migration v{} -> v{}: {}", from, LATEST, backup);
        }
        if (from < 1) {
            migrateToV1(conn);
        }
        if (from < 2) {
            migrateToV2(conn);
        }
        if (from < 3) {
            migrateToV3(conn);
        }
        if (from < 4) {
            migrateToV4(conn);
        }
        if (from < 5) {
            migrateToV5(conn);
        }
        if (from < 6) {
            migrateToV6(conn);
        }
        if (from < 7) {
            migrateToV7(conn);
        }
        writeVersion(conn, LATEST);
        report.put("migrated", true);
        log.info("Experience store schema migrated v{} -> v{}", from, LATEST);
        return report;
    }

    /** 0 = fresh; 1 = pre-versioning v2.0.0 schema; else the {@code schema_version} row. */
    static int detectVersion(Connection conn) throws SQLException {
        if (tableExists(conn, "SCHEMA_VERSION")) {
            try (Statement s = conn.createStatement();
                    ResultSet rs = s.executeQuery("SELECT version FROM schema_version")) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
            return 0;      // version table exists but empty — treat as fresh
        }
        return tableExists(conn, "EXPERIENCE_ENTRY") ? 1 : 0;
    }

    private static boolean tableExists(Connection conn, String upperName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?")) {
            ps.setString(1, upperName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        }
    }

    /** v1 — the v2.0.0 base schema (verbatim; was {@code H2ExperienceStore.initSchema}). */
    private static void migrateToV1(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
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
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_source ON experience_entry(source_ref)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_type ON experience_entry(type)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_symbol ON experience_entry(symbol_fqn)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_status ON experience_entry(status)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_operation ON experience_entry(operation)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_scope_kind ON experience_entry(scope_kind)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_ext_system ON experience_entry(external_system)");
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
        }
    }

    /**
     * v2 — Sprint 21a facets: provenance ({@code workspace_id}, {@code project_id} — item H
     * merge-ability) + {@code language} (item I multi-language guard; existing rows are
     * Java-era, backfilled {@code 'java'}).
     */
    private static void migrateToV2(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS workspace_id VARCHAR(256)");
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS project_id VARCHAR(1024)");
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS language VARCHAR(32)");
            s.execute("UPDATE experience_entry SET language = 'java' WHERE language IS NULL");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_language ON experience_entry(language)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_workspace ON experience_entry(workspace_id)");
        }
    }

    /** v3 (Sprint 21b): content hash of the ingested memory file — lets {@code load}
     *  skip unchanged sources without any write (NULL = unknown, next load rewrites once). */
    private static void migrateToV3(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS source_hash VARCHAR(64)");
        }
    }

    /**
     * v4 (Sprint 22b, the jawata rebrand): REWRITE the old product's code anchors to the
     * renamed coordinates — never supersede-without-replacement (that preserves content
     * but kills findability, and record-sourced entries have no file to re-anchor from).
     * Both halves per anchor: the package prefix {@code org.goja.} → {@code org.jawata.}
     * and the class segments {@code .IGoja} → {@code .IJawata} / {@code .Goja} →
     * {@code .Jawata}. Applies to {@code symbol_fqn}, {@code package_name} and
     * old-package {@code experience_link.target} FQNs. Foreign anchors (any other
     * codebase) never match the {@code org.goja.} guards and are untouched.
     * The migration itself must contain the OLD names — it is the code that rewrites
     * them (grep-contract exception class 3).
     */
    private static void migrateToV4(Connection conn) throws SQLException {
        String rewrite = "REPLACE(REPLACE(REPLACE(%s, 'org.goja.', 'org.jawata.'),"
            + " '.IGoja', '.IJawata'), '.Goja', '.Jawata')";
        try (Statement s = conn.createStatement()) {
            s.execute("UPDATE experience_entry SET symbol_fqn = " + rewrite.formatted("symbol_fqn")
                + " WHERE symbol_fqn LIKE 'org.goja.%'");
            s.execute("UPDATE experience_entry SET package_name = "
                + "REPLACE(package_name, 'org.goja', 'org.jawata')"
                + " WHERE package_name = 'org.goja' OR package_name LIKE 'org.goja.%'");
            s.execute("UPDATE experience_link SET target = " + rewrite.formatted("target")
                + " WHERE target LIKE 'org.goja.%'");
        }
    }

    /**
     * v5 (Sprint 26): the injector's learning layer — {@code learner_event}, the
     * continuous label stream (one row per immediate signal: a tool error, an
     * undo, a mechanical touch, a gate call, a compile-failure after a touched
     * file), session-scoped; and {@code learner_state}, each learner's
     * serialized model + rolling record. Sibling tables on the same store file
     * so learner data inherits the store's per-workspace provenance, backup and
     * privacy boundary (local only — nothing crosses).
     */
    private static void migrateToV5(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS learner_event ("
                + "id IDENTITY PRIMARY KEY, "
                + "ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "session_id VARCHAR(64), "
                + "kind VARCHAR(40) NOT NULL, "
                + "tool VARCHAR(80), "
                + "detail_json CLOB, "
                + "workspace_id VARCHAR(80), "
                + "project_id VARCHAR(80))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_learner_event_kind"
                + " ON learner_event(kind)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_learner_event_session"
                + " ON learner_event(session_id)");
            s.execute("CREATE TABLE IF NOT EXISTS learner_state ("
                + "learner VARCHAR(60) PRIMARY KEY, "
                + "state_json CLOB, "
                + "updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /**
     * v6 (Sprint 26a, D2): the experience loop's capture lane —
     * {@code tool_experience}, one row per SELECTIVE outcome-bearing event (a
     * mutate's compile result, or a tool error; a jawata-fallback is captured
     * studio-side as a {@code failure_mode} entry, not here). {@code situation} is
     * the keyword-rich key baseline retrieval matches on (Sprint 27 adds an
     * embedding column beside it). A sibling table on the same store file, so it
     * inherits the store's per-workspace provenance, backup and privacy boundary
     * (local only — nothing crosses).
     */
    private static void migrateToV6(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS tool_experience ("
                + "id IDENTITY PRIMARY KEY, "
                + "ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP, "
                + "session_id VARCHAR(64), "
                + "situation VARCHAR(1024), "
                + "tool VARCHAR(80) NOT NULL, "
                + "outcome VARCHAR(20) NOT NULL, "
                + "detail_json CLOB, "
                + "workspace_id VARCHAR(80), "
                + "project_id VARCHAR(80))");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tool_experience_tool"
                + " ON tool_experience(tool)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tool_experience_outcome"
                + " ON tool_experience(outcome)");
        }
    }

    /**
     * v7 (Sprint 27, D2): the semantic-recall lane — an embedding vector and the
     * identity that produced it, on BOTH the knowledge entries and the
     * {@code tool_experience} rows.
     *
     * <p>Purely additive: existing rows get NULLs, which is the honest state
     * ("not embedded yet") and is what the backfill looks for. Nothing already
     * stored is rewritten, so a v6 store keeps working exactly as before if the
     * embedder never runs.</p>
     *
     * <p>{@code embedder_identity} is stored beside every vector rather than
     * once for the database, because a store outlives model changes: vectors
     * from different identities must be distinguishable per row so the stale
     * ones can be found and re-embedded, and so a comparison across identities
     * can be REFUSED rather than silently producing a meaningless score.</p>
     */
    private static void migrateToV7(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS embedding BLOB");
            s.execute("ALTER TABLE experience_entry ADD COLUMN IF NOT EXISTS embedder_identity VARCHAR(128)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_embedder ON experience_entry(embedder_identity)");
            s.execute("ALTER TABLE tool_experience ADD COLUMN IF NOT EXISTS embedding BLOB");
            s.execute("ALTER TABLE tool_experience ADD COLUMN IF NOT EXISTS embedder_identity VARCHAR(128)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_tool_experience_embedder"
                + " ON tool_experience(embedder_identity)");
            // Sprint 27 D6 — the quality ledger's persistence rides v7 (no v8).
            // One narrow table: a counter name and its count. Deliberately NOT
            // an event log — 27's boundary is read-only measurement, and a
            // per-event table would invite exactly the analysis Sprint 33 is
            // supposed to decide on evidence rather than inherit as machinery.
            s.execute("CREATE TABLE IF NOT EXISTS quality_counter ("
                + "name VARCHAR(160) PRIMARY KEY, count BIGINT NOT NULL DEFAULT 0)");
        }
    }

    private static void writeVersion(Connection conn, int version) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)");
            s.execute("DELETE FROM schema_version");
            s.execute("INSERT INTO schema_version VALUES (" + version + ")");
        }
    }
}
