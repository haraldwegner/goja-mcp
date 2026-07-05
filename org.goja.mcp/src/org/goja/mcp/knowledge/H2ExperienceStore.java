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
import java.util.ArrayList;
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

    // Sprint 21a (item B): provenance stamped on every write; set once at store-open from
    // workspace.json. Null when unknown (manual launches, tests).
    private volatile String workspaceId;
    private volatile String projectId;

    private H2ExperienceStore(Connection conn, Path storeDir) throws SQLException {
        this.conn = conn;
        Map<String, Object> report = SchemaMigrations.migrate(conn, storeDir);
        if (Boolean.TRUE.equals(report.get("migrated"))) {
            log.info("Experience store schema: {}", report);
        }
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
            Path storeDir = null;
            if (dir != null) {
                storeDir = dir.resolve("goja-experience");
                Path file = storeDir.resolve("experience");
                url = "jdbc:h2:file:" + file.toAbsolutePath() + ";DB_CLOSE_ON_EXIT=FALSE";
            } else {
                // Unique name per store so independent in-memory stores never share state.
                url = "jdbc:h2:mem:goja-exp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
                log.info("No workspace data dir — experience store is in-memory (non-persistent)");
            }
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL(url);
            H2ExperienceStore store = new H2ExperienceStore(ds.getConnection(), storeDir);
            log.info("Experience store opened ({})", dir != null ? "file: " + dir : "in-memory");
            return store;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to open experience store: " + e.getMessage(), e);
        }
    }

    @Override
    public void setProvenance(String workspaceId, String projectId) {
        this.workspaceId = workspaceId;
        this.projectId = projectId;
    }

    // Schema DDL lives in SchemaMigrations (Sprint 21a item B) — versioned, additive-first,
    // backed-up-before-migrate. The v2.0.0 initSchema() became its v1 step.

    @Override
    public synchronized String put(SymbolFact fact) {
        return put(ExperienceEntry.candidate(fact));
    }

    @Override
    public synchronized String put(ExperienceEntry entry) {
        return insert(entry, UUID.randomUUID().toString(), null);
    }

    @Override
    public synchronized String putWithSource(ExperienceEntry entry, String sourceRef) {
        return insert(entry, UUID.randomUUID().toString(), sourceRef);
    }

    private String insert(ExperienceEntry entry, String id, String sourceRef) {
        Map<String, Object> factMap = entry.fact().toMap();
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
                    + "fault_owner,external_system,summary,source_ref,body_json,created_at,updated_at,"
                    + "workspace_id,project_id,language) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
                ps.setString(12, sourceRef);
                ps.setString(13, body);
                ps.setTimestamp(14, now);
                ps.setTimestamp(15, now);
                ps.setString(16, workspaceId);
                ps.setString(17, projectId);
                ps.setString(18, "java");    // language param lands in Stage 3 (item I)
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
    public synchronized int deleteBySource(String sourceRef) {
        if (sourceRef == null) {
            return 0;
        }
        try {
            // Remove children first (no FK cascade in this embedded schema).
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM experience_symptom WHERE entry_id IN"
                    + " (SELECT id FROM experience_entry WHERE source_ref = ?)")) {
                ps.setString(1, sourceRef);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM experience_link WHERE entry_id IN"
                    + " (SELECT id FROM experience_entry WHERE source_ref = ?)")) {
                ps.setString(1, sourceRef);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM experience_entry WHERE source_ref = ?")) {
                ps.setString(1, sourceRef);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete by source: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized long wipe() {
        try (Statement s = conn.createStatement()) {
            long n = count();
            s.execute("DELETE FROM experience_symptom");
            s.execute("DELETE FROM experience_link");
            s.execute("DELETE FROM experience_entry");
            return n;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to wipe store: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized List<StoredEntry> all() {
        List<StoredEntry> out = new ArrayList<>();
        try (Statement s = conn.createStatement();
                ResultSet rs = s.executeQuery(
                    "SELECT id,type,symbol_fqn,package_name,operation,status,confidence,"
                    + "external_system,summary,body_json,created_at FROM experience_entry")) {
            while (rs.next()) {
                out.add(mapRow(rs));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to list entries: " + e.getMessage(), e);
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
    @SuppressWarnings("unchecked")
    public synchronized List<StoredEntry> query(RecallQuery q) {
        if (q == null || q.isEmpty()) {
            return List.of();
        }
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (q.hasSymbol()) {
            // entry symbol equals/encloses the cue, cue's package holds it, or entry symbol is under the cue
            clauses.add("(symbol_fqn = ? OR ? LIKE symbol_fqn || '.%' OR ? LIKE package_name || '.%'"
                + " OR symbol_fqn LIKE ? || '.%')");
            params.add(q.symbol());
            params.add(q.symbol());
            params.add(q.symbol());
            params.add(q.symbol());
        }
        if (q.hasPackage()) {
            clauses.add("(package_name = ? OR ? LIKE package_name || '.%' OR package_name LIKE ? || '.%'"
                + " OR symbol_fqn LIKE ? || '.%')");
            params.add(q.packageName());
            params.add(q.packageName());
            params.add(q.packageName());
            params.add(q.packageName());
        }
        if (q.hasOperation()) {
            clauses.add("operation = ?");
            params.add(q.operation());
        }
        if (q.hasExternalSystem()) {
            clauses.add("LOWER(external_system) = LOWER(?)");
            params.add(q.externalSystem());
        }
        if (q.hasSymptom()) {
            String like = "%" + normalize(q.symptom()) + "%";
            clauses.add("(id IN (SELECT entry_id FROM experience_symptom WHERE symptom LIKE ?)"
                + " OR LOWER(summary) LIKE ?)");
            params.add(like);
            params.add(like);
        }
        String sql = "SELECT id,type,symbol_fqn,package_name,operation,status,confidence,"
            + "external_system,summary,body_json,created_at FROM experience_entry WHERE ("
            + String.join(" OR ", clauses)
            + ") AND status NOT IN ('rejected', 'superseded') ORDER BY created_at DESC";

        List<StoredEntry> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(mapRow(rs));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to query entries: " + e.getMessage(), e);
        }
    }

    /** Map a row (selecting the projection columns below) to a {@link StoredEntry}. */
    @SuppressWarnings("unchecked")
    private StoredEntry mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        Map<String, Object> body;
        try {
            body = json.readValue(rs.getString("body_json"), LinkedHashMap.class);
        } catch (Exception e) {
            body = new LinkedHashMap<>();
        }
        Timestamp ts = rs.getTimestamp("created_at");
        return new StoredEntry(
            id,
            rs.getString("type"),
            rs.getString("symbol_fqn"),
            rs.getString("package_name"),
            rs.getString("operation"),
            rs.getString("status"),
            rs.getString("confidence"),
            rs.getString("external_system"),
            rs.getString("summary"),
            loadSymptoms(id),
            ts == null ? null : ts.toInstant(),
            body);
    }

    private List<String> loadSymptoms(String id) throws SQLException {
        List<String> symptoms = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT symptom FROM experience_symptom WHERE entry_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    symptoms.add(rs.getString(1));
                }
            }
        }
        return symptoms;
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
