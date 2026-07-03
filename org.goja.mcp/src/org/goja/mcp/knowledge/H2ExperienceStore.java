package org.goja.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 21 (v2.0): H2-backed {@link ExperienceStore}. Embedded, single-file,
 * workspace-scoped — no external daemon. Stage 0 = schema + open/close + entry
 * round-trip; the richer indexed columns (Stage 1) and full-text/fit-gated retrieval
 * (Stage 2) build on this schema. A single connection is held for the store's lifetime
 * and methods are synchronized (the resident is one process; H2 file mode is single-JVM).
 */
public final class H2ExperienceStore implements ExperienceStore {

    private static final Logger log = LoggerFactory.getLogger(H2ExperienceStore.class);

    private final ObjectMapper json = new ObjectMapper();
    private final Connection conn;

    private H2ExperienceStore(Connection conn) throws SQLException {
        this.conn = conn;
        initSchema();
    }

    /**
     * Open a file-backed store under {@code dir} (the workspace {@code -data} directory,
     * from {@code GojaApplication.resolveDataDir()}). A {@code null} dir — a manual launch
     * without {@code -data} — yields an in-memory store: the seam still works, it just does
     * not persist across restarts.
     */
    public static H2ExperienceStore open(Path dir) {
        try {
            String url;
            if (dir != null) {
                Path file = dir.resolve("goja-experience").resolve("experience");
                url = "jdbc:h2:file:" + file.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
            } else {
                // Unique name per store so independent in-memory stores never share state.
                url = "jdbc:h2:mem:goja-exp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
                log.info("No workspace data dir — experience store is in-memory (non-persistent)");
            }
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL(url);
            H2ExperienceStore store = new H2ExperienceStore(ds.getConnection());
            log.info("Experience store opened ({})", dir != null ? "file: " + dir : "in-memory");
            return store;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to open experience store: " + e.getMessage(), e);
        }
    }

    private void initSchema() throws SQLException {
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
                    body_json       CLOB,
                    created_at      TIMESTAMP,
                    updated_at      TIMESTAMP
                )""");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_type ON experience_entry(type)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_symbol ON experience_entry(symbol_fqn)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_status ON experience_entry(status)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_operation ON experience_entry(operation)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_scope_kind ON experience_entry(scope_kind)");
            s.execute("CREATE INDEX IF NOT EXISTS ix_entry_ext_system ON experience_entry(external_system)");
            // Multi-valued children — created now, populated from Stage 1/2.
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

    @Override
    public synchronized String put(SymbolFact fact) {
        return put(ExperienceEntry.candidate(fact));
    }

    @Override
    public synchronized String put(ExperienceEntry entry) {
        Map<String, Object> factMap = entry.fact().toMap();
        String id = UUID.randomUUID().toString();
        String body;
        try {
            body = json.writeValueAsString(entry.toMap());
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize entry: " + e.getMessage(), e);
        }
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO experience_entry"
                    + "(id,type,scope_kind,symbol_fqn,package_name,operation,status,confidence,"
                    + "fault_owner,external_system,summary,body_json,created_at,updated_at) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                Timestamp now = Timestamp.from(Instant.now());
                ps.setString(1, id);
                ps.setString(2, str(factMap.get("type")));
                ps.setString(3, entry.scopeKind());
                ps.setString(4, str(factMap.get("symbol")));
                ps.setString(5, firstPackage(factMap));
                ps.setString(6, entry.operation());
                ps.setString(7, entry.status());
                ps.setString(8, str(factMap.get("confidence")));
                ps.setString(9, entry.faultOwner());
                ps.setString(10, entry.externalSystem());
                ps.setString(11, str(factMap.get("summary")));
                ps.setString(12, body);
                ps.setTimestamp(13, now);
                ps.setTimestamp(14, now);
                ps.executeUpdate();
            }
            insertSymptoms(id, entry.symptoms());
            insertLinks(id, entry.links());
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to put entry: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized boolean setStatus(String id, String status) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE experience_entry SET status = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to set status: " + e.getMessage(), e);
        }
    }

    /** Symptoms are alias-normalized (lower/trim/collapse) so paraphrases index together. */
    private void insertSymptoms(String id, List<String> symptoms) throws SQLException {
        if (symptoms.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO experience_symptom(entry_id, symptom) VALUES (?, ?)")) {
            for (String s : symptoms) {
                String norm = normalize(s);
                if (norm.isEmpty()) {
                    continue;
                }
                ps.setString(1, id);
                ps.setString(2, norm);
                ps.executeUpdate();
            }
        }
    }

    private void insertLinks(String id, List<ExperienceEntry.Link> links) throws SQLException {
        if (links.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "MERGE INTO experience_link(entry_id, rel, target) VALUES (?, ?, ?)")) {
            for (ExperienceEntry.Link l : links) {
                if (l.rel() == null || l.target() == null) {
                    continue;
                }
                ps.setString(1, id);
                ps.setString(2, l.rel());
                ps.setString(3, l.target());
                ps.executeUpdate();
            }
        }
    }

    /** Alias normalization: lowercased, trimmed, whitespace-collapsed. */
    static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized Optional<Map<String, Object>> get(String id) {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT body_json FROM experience_entry WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(json.readValue(rs.getString(1), LinkedHashMap.class));
            }
        } catch (Exception e) {
            throw new IllegalStateException("failed to get entry: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized long count() {
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM experience_entry")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to count entries: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.warn("Error closing experience store: {}", e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static String firstPackage(Map<String, Object> map) {
        if (map.get("scope") instanceof Map<?, ?> scope
                && scope.get("packages") instanceof List<?> packages
                && !packages.isEmpty()) {
            return String.valueOf(packages.get(0));
        }
        return null;
    }
}
