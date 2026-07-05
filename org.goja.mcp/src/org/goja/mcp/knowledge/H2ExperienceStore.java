package org.goja.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
    /** The backing {@code .mv.db} file (null for in-memory) — self-exclusion in recovery. */
    private final Path storeFile;

    // Sprint 21a (item B): provenance stamped on every write; set once at store-open from
    // workspace.json. Null when unknown (manual launches, tests).
    private volatile String workspaceId;
    private volatile String projectId;

    private H2ExperienceStore(Connection conn, Path storeDir, Path storeFile) throws SQLException {
        this.conn = conn;
        this.storeFile = storeFile;
        Map<String, Object> report = SchemaMigrations.migrate(conn, storeDir);
        if (Boolean.TRUE.equals(report.get("migrated"))) {
            log.info("Experience store schema: {}", report);
        }
    }

    /**
     * Open a file-backed store under {@code dir} in the workspace layout
     * ({@code <dir>/goja-experience/experience.mv.db}). A {@code null} dir — a manual
     * launch without {@code -data} — yields an in-memory store: the seam still works, it
     * just does not persist across restarts.
     */
    public static H2ExperienceStore open(Path dir) {
        if (dir == null) {
            return openMemory();
        }
        return openAt(dir.resolve("goja-experience"));
    }

    /** In-memory store: unique name per instance so independent stores never share state. */
    public static H2ExperienceStore openMemory() {
        String url = "jdbc:h2:mem:goja-exp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        log.info("Experience store is in-memory (non-persistent)");
        return openUrl(url, null, null);
    }

    /**
     * Sprint 21a (items A+H): open the store file directly at
     * {@code <storeDir>/experience.mv.db} with {@code AUTO_SERVER} — the first resident
     * becomes the H2 auto-server and further residents (other workspaces sharing the
     * user-level store, or concurrent sessions of one workspace) attach transparently
     * through the same URL.
     */
    public static H2ExperienceStore openAt(Path storeDir) {
        try {
            Files.createDirectories(storeDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create store dir " + storeDir + ": " + e.getMessage(), e);
        }
        Path base = storeDir.resolve("experience");
        // H2 forbids DB_CLOSE_ON_EXIT together with AUTO_SERVER — the auto-server owns the
        // database lifecycle (we still close() explicitly at stop()).
        String url = "jdbc:h2:file:" + base.toAbsolutePath() + ";AUTO_SERVER=TRUE";
        H2ExperienceStore store = openUrl(url, storeDir, storeDir.resolve("experience.mv.db"));
        log.info("Experience store opened (file: {})", storeDir);
        return store;
    }

    /**
     * Sprint 21a (item H): the user-level shared store — Harald's decision 2026-07-05:
     * this is the DEFAULT. Knowledge is the user's, not the workspace's; symbol/package
     * scope-containment keeps repo-specific recall self-scoped, while methodology/domain
     * entries deliberately cross workspaces.
     */
    public static H2ExperienceStore openShared() {
        Path dir = sharedStoreDir();
        log.info("Experience store mode: user-shared ({})", dir);
        return openAt(dir);
    }

    /** {@code goja.experience.shared.dir} property › {@code $XDG_DATA_HOME/goja} › {@code ~/.local/share/goja}. */
    static Path sharedStoreDir() {
        String override = System.getProperty("goja.experience.shared.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank()
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".local", "share");
        return base.resolve("goja");
    }

    private static H2ExperienceStore openUrl(String url, Path storeDir, Path storeFile) {
        Connection conn = null;
        try {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL(url);
            conn = ds.getConnection();
            return new H2ExperienceStore(conn, storeDir, storeFile);
        } catch (SQLException e) {
            closeQuietly(conn);
            throw new IllegalStateException("failed to open experience store: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            closeQuietly(conn);      // e.g. a refused from-the-future store must not keep the lock
            throw e;
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // best effort
            }
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
                String lang = entry.language();
                ps.setString(18, lang == null || lang.isBlank() ? "java" : lang);
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
                    "SELECT id,type,symbol_fqn,package_name,operation,status,confidence,language,"
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
        String sql = "SELECT id,type,symbol_fqn,package_name,operation,status,confidence,language,"
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
            rs.getString("language"),
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

    // --- Sprint 21a (item A): one-time recovery of orphaned per-session stores ----------

    /**
     * Earlier releases opened the store inside the launcher's session-isolation dir —
     * orphaned on every redeploy and DELETED on clean shutdown. Scan the stable workspace
     * root's session subdirs (newest first) for {@code goja-experience/experience.mv.db},
     * import their entries into THIS store (dedup by id; provenance stamped; language
     * backfilled), and mark each swept source with a {@code .goja-recovered} file so the
     * sweep is idempotent. Sources are never deleted (never merge blindly — pruning the
     * files is a human/GC decision, not this sweep's).
     */
    public synchronized Map<String, Object> recoverOrphans(Path workspaceRoot) {
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        int imported = 0;
        int duplicates = 0;
        report.put("sources", sources);
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            report.put("imported", 0);
            report.put("duplicates", 0);
            return report;
        }
        List<Path> candidates = new ArrayList<>();
        try (java.util.stream.Stream<Path> subdirs = Files.list(workspaceRoot)) {
            subdirs.filter(Files::isDirectory)
                .filter(d -> Files.isRegularFile(d.resolve("goja-experience").resolve("experience.mv.db")))
                .sorted(Comparator.comparingLong(H2ExperienceStore::lastModified).reversed())
                .forEach(candidates::add);
        } catch (IOException e) {
            log.warn("recovery: cannot list {}: {}", workspaceRoot, e.getMessage());
        }
        for (Path sessionDir : candidates) {
            Path expDir = sessionDir.resolve("goja-experience");
            Path db = expDir.resolve("experience.mv.db");
            Path marker = expDir.resolve(".goja-recovered");
            if (Files.exists(marker)) {
                continue;
            }
            if (storeFile != null
                    && db.toAbsolutePath().normalize().equals(storeFile.toAbsolutePath().normalize())) {
                continue;          // never sweep ourselves
            }
            String name = sessionDir.getFileName().toString();
            String url = "jdbc:h2:file:" + expDir.resolve("experience").toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE;ACCESS_MODE_DATA=r";
            try {
                JdbcDataSource ds = new JdbcDataSource();
                ds.setURL(url);
                try (Connection orphan = ds.getConnection()) {
                    int version = SchemaMigrations.detectVersion(orphan);
                    if (version > SchemaMigrations.LATEST) {
                        log.warn("recovery: {} is schema v{} (> v{}) — skipped, not marked",
                            db, version, SchemaMigrations.LATEST);
                        sources.add(Map.of("source", name, "skipped", "newer schema v" + version));
                        continue;
                    }
                    int[] counts = importFrom(orphan, version >= 2);
                    imported += counts[0];
                    duplicates += counts[1];
                    sources.add(Map.of("source", name, "imported", counts[0], "duplicates", counts[1]));
                }
                Files.writeString(marker, Instant.now().toString());
            } catch (Exception e) {
                log.warn("recovery: cannot import {}: {}", db, e.getMessage());
                sources.add(Map.of("source", name, "error", String.valueOf(e.getMessage())));
            }
        }
        report.put("imported", imported);
        report.put("duplicates", duplicates);
        if (imported > 0 || !sources.isEmpty()) {
            log.info("Experience store recovery: {}", report);
        }
        return report;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /** Copy every entry (+ symptoms + links) from an orphan store; dedup by id. */
    private int[] importFrom(Connection orphan, boolean hasFacets) throws SQLException {
        String cols = "id,type,scope_kind,symbol_fqn,package_name,operation,status,confidence,"
            + "fault_owner,external_system,summary,source_ref,body_json,created_at,updated_at"
            + (hasFacets ? ",workspace_id,project_id,language" : "");
        int imported = 0;
        int duplicates = 0;
        try (Statement s = orphan.createStatement();
                ResultSet rs = s.executeQuery("SELECT " + cols + " FROM experience_entry")) {
            while (rs.next()) {
                String id = rs.getString("id");
                if (idExists(id)) {
                    duplicates++;
                    continue;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO experience_entry"
                        + "(id,type,scope_kind,symbol_fqn,package_name,operation,status,confidence,"
                        + "fault_owner,external_system,summary,source_ref,body_json,created_at,updated_at,"
                        + "workspace_id,project_id,language) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (int i = 1; i <= 13; i++) {
                        ps.setString(i, rs.getString(i));
                    }
                    ps.setTimestamp(14, rs.getTimestamp(14));
                    ps.setTimestamp(15, rs.getTimestamp(15));
                    String ws = hasFacets ? rs.getString("workspace_id") : null;
                    String proj = hasFacets ? rs.getString("project_id") : null;
                    String lang = hasFacets ? rs.getString("language") : null;
                    ps.setString(16, ws != null ? ws : workspaceId);
                    ps.setString(17, proj != null ? proj : projectId);
                    ps.setString(18, lang != null ? lang : "java");
                    ps.executeUpdate();
                }
                copyChildren(orphan, id);
                imported++;
            }
        }
        return new int[] {imported, duplicates};
    }

    private void copyChildren(Connection orphan, String id) throws SQLException {
        try (PreparedStatement q = orphan.prepareStatement(
                "SELECT symptom FROM experience_symptom WHERE entry_id = ?")) {
            q.setString(1, id);
            try (ResultSet rs = q.executeQuery();
                    PreparedStatement ins = conn.prepareStatement(
                        "MERGE INTO experience_symptom(entry_id, symptom) VALUES (?, ?)")) {
                while (rs.next()) {
                    ins.setString(1, id);
                    ins.setString(2, rs.getString(1));
                    ins.executeUpdate();
                }
            }
        }
        try (PreparedStatement q = orphan.prepareStatement(
                "SELECT rel, target FROM experience_link WHERE entry_id = ?")) {
            q.setString(1, id);
            try (ResultSet rs = q.executeQuery();
                    PreparedStatement ins = conn.prepareStatement(
                        "MERGE INTO experience_link(entry_id, rel, target) VALUES (?, ?, ?)")) {
                while (rs.next()) {
                    ins.setString(1, id);
                    ins.setString(2, rs.getString(1));
                    ins.setString(3, rs.getString(2));
                    ins.executeUpdate();
                }
            }
        }
    }

    private boolean idExists(String id) {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM experience_entry WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed id lookup: " + e.getMessage(), e);
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
