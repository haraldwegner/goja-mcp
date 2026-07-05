package org.goja.mcp.knowledge;

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
 * {@code language} — backfilled {@code 'java'}, see item I).</p>
 */
final class SchemaMigrations {

    private static final Logger log = LoggerFactory.getLogger(SchemaMigrations.class);

    /** Current schema version — bump together with a new {@code migrateToVn} step. */
    static final int LATEST = 2;

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
                + ") — refusing read-write open; upgrade goja or use the newer resident");
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

    private static void writeVersion(Connection conn, int version) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)");
            s.execute("DELETE FROM schema_version");
            s.execute("INSERT INTO schema_version VALUES (" + version + ")");
        }
    }
}
