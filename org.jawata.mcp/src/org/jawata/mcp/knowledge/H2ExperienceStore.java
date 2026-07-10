package org.jawata.mcp.knowledge;

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
    /** Non-final: {@link #compact()} shuts the database down and reopens the connection. */
    private Connection conn;
    private final String url;
    /** The backing {@code .mv.db} file (null for in-memory) — self-exclusion in recovery. */
    private final Path storeFile;

    // Sprint 21a (item B): provenance stamped on every write; set once at store-open from
    // workspace.json. Null when unknown (manual launches, tests).
    private volatile String workspaceId;
    private volatile String projectId;

    /**
     * The live connection — self-heals when the shared AUTO_SERVER database was shut
     * down under us (Sprint 21b: a {@code compact} on ANY attached resident closes the
     * database for ALL of them; the peers must reconnect, not die).
     */
    private Connection live() {
        try {
            if (conn == null || conn.isClosed() || !conn.isValid(1)) {
                JdbcDataSource ds = new JdbcDataSource();
                ds.setURL(url);
                conn = ds.getConnection();
                log.info("Experience store connection re-established");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to re-open store connection: " + e.getMessage(), e);
        }
        return conn;
    }

    private H2ExperienceStore(Connection conn, String url, Path storeDir, Path storeFile)
            throws SQLException {
        this.conn = conn;
        this.url = url;
        this.storeFile = storeFile;
        Map<String, Object> report = SchemaMigrations.migrate(conn, storeDir);
        if (Boolean.TRUE.equals(report.get("migrated"))) {
            log.info("Experience store schema: {}", report);
        }
    }

    /**
     * Open a file-backed store under {@code dir} in the workspace layout
     * ({@code <dir>/jawata-experience/experience.mv.db}). A {@code null} dir — a manual
     * launch without {@code -data} — yields an in-memory store: the seam still works, it
     * just does not persist across restarts.
     */
    public static H2ExperienceStore open(Path dir) {
        if (dir == null) {
            return openMemory();
        }
        return openAt(dir.resolve("jawata-experience"));
    }

    /** In-memory store: unique name per instance so independent stores never share state. */
    public static H2ExperienceStore openMemory() {
        String url = "jdbc:h2:mem:jawata-exp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
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
        // v2.2.4: a runtime swap restarts residents seconds apart — the dying peer's lock
        // file is "recently modified" and H2 refuses the open. That is TRANSIENT; retry
        // before the caller degrades to a silent, non-persistent in-memory store.
        H2ExperienceStore store = openWithRetry(
            () -> openUrl(url, storeDir, storeDir.resolve("experience.mv.db")), 5, 1500);
        log.info("Experience store opened (file: {})", storeDir);
        return store;
    }

    /** Retry transient lock contention ({@code attempts} × {@code sleepMs}); rethrow anything else. */
    static H2ExperienceStore openWithRetry(java.util.function.Supplier<H2ExperienceStore> opener,
            int attempts, long sleepMs) {
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return opener.get();
            } catch (IllegalStateException e) {
                if (!isTransientLock(e)) {
                    throw e;
                }
                last = e;
                log.info("Experience store lock busy (attempt {}/{}): {}", attempt, attempts, e.getMessage());
                if (attempt < attempts) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }

    /** The two H2 messages a resident restart race produces — gone within seconds. */
    private static boolean isTransientLock(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("Lock file recently modified")
                    || m.contains("Database may be already in use"))) {
                return true;
            }
        }
        return false;
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

    /** {@code jawata.experience.shared.dir} property › {@code $XDG_DATA_HOME/jawata} › {@code ~/.local/share/jawata}. */
    static Path sharedStoreDir() {
        String override = System.getProperty("jawata.experience.shared.dir", System.getProperty("goja.experience.shared.dir")); /* legacy goja fallback — remove next release */
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank()
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".local", "share");
        return migrateLegacySharedDir(base);
    }

    /**
     * Sprint 22b (the jawata rebrand): one-time move of the pre-rebrand user-shared
     * store dir {@code <base>/goja} to {@code <base>/jawata} — the whole directory
     * (store file, backups, exports) moves as-is, content untouched; the v4 schema
     * migration then rewrites the anchors on first open. Never clobbers: if
     * {@code <base>/jawata} already exists, the legacy dir is left alone. Only the
     * DEFAULT XDG-derived path migrates — an explicit {@code *.experience.shared.dir}
     * override is used as-is. The {@code "goja"} literal is migration code
     * (grep-contract exception class 3).
     */
    static Path migrateLegacySharedDir(Path base) {
        Path dir = base.resolve("jawata");
        Path legacy = base.resolve("goja");
        if (java.nio.file.Files.isDirectory(legacy) && !java.nio.file.Files.exists(dir)) {
            try {
                java.nio.file.Files.move(legacy, dir);
                log.info("Experience store: migrated legacy shared dir {} -> {}", legacy, dir);
            } catch (java.io.IOException e) {
                log.warn("Experience store: could not migrate legacy shared dir {} -> {}: {} "
                    + "(continuing with {})", legacy, dir, e.getMessage(), dir);
            }
        }
        return dir;
    }

    private static H2ExperienceStore openUrl(String url, Path storeDir, Path storeFile) {
        Connection conn = null;
        try {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL(url);
            conn = ds.getConnection();
            return new H2ExperienceStore(conn, url, storeDir, storeFile);
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
    public String provenanceWorkspaceId() {
        return workspaceId;
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
        return insert(entry, UUID.randomUUID().toString(), null, null);
    }

    @Override
    public synchronized String putWithSource(ExperienceEntry entry, String sourceRef) {
        return insert(entry, UUID.randomUUID().toString(), sourceRef, null);
    }

    @Override
    public synchronized String putWithSource(ExperienceEntry entry, String sourceRef, String sourceHash) {
        return insert(entry, UUID.randomUUID().toString(), sourceRef, sourceHash);
    }

    /** Sprint 21b: skip-unchanged — any entry from this source with this exact hash? */
    @Override
    public synchronized boolean sourceUnchanged(String sourceRef, String sourceHash) {
        if (sourceRef == null || sourceHash == null) {
            return false;
        }
        try (PreparedStatement ps = live().prepareStatement(
                "SELECT 1 FROM experience_entry WHERE source_ref = ? AND source_hash = ? LIMIT 1")) {
            ps.setString(1, sourceRef);
            ps.setString(2, sourceHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to check source hash: " + e.getMessage(), e);
        }
    }

    private String insert(ExperienceEntry entry, String id, String sourceRef, String sourceHash) {
        Map<String, Object> factMap = entry.fact().toMap();
        String body;
        try {
            body = json.writeValueAsString(entry.toMap());
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize entry: " + e.getMessage(), e);
        }
        try {
            try (PreparedStatement ps = live().prepareStatement(
                    "INSERT INTO experience_entry"
                    + "(id,type,scope_kind,symbol_fqn,package_name,operation,status,confidence,"
                    + "fault_owner,external_system,summary,source_ref,body_json,created_at,updated_at,"
                    + "workspace_id,project_id,language,source_hash) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
                ps.setString(19, sourceHash);
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
            try (PreparedStatement ps = live().prepareStatement(
                    "DELETE FROM experience_symptom WHERE entry_id IN"
                    + " (SELECT id FROM experience_entry WHERE source_ref = ?)")) {
                ps.setString(1, sourceRef);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = live().prepareStatement(
                    "DELETE FROM experience_link WHERE entry_id IN"
                    + " (SELECT id FROM experience_entry WHERE source_ref = ?)")) {
                ps.setString(1, sourceRef);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = live().prepareStatement(
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
        try (Statement s = live().createStatement()) {
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
        try (Statement s = live().createStatement();
                ResultSet rs = s.executeQuery(
                    "SELECT id,type,symbol_fqn,package_name,operation,status,confidence,language,source_ref,scope_kind,"
                    + "external_system,summary,body_json,workspace_id,created_at FROM experience_entry")) {
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
        try (PreparedStatement ps = live().prepareStatement(
                "UPDATE experience_entry SET status = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to set status: " + e.getMessage(), e);
        }
    }

    /** Sprint 21e (item A): {@code symbol_fqn} ONLY — never package_name/source_hash/status. */
    @Override
    public synchronized boolean updateSymbolAnchor(String id, String symbolFqn) {
        try (PreparedStatement ps = live().prepareStatement(
                "UPDATE experience_entry SET symbol_fqn = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, symbolFqn);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to update symbol anchor: " + e.getMessage(), e);
        }
    }

    /** Symptoms are alias-normalized (lower/trim/collapse) so paraphrases index together. */
    private void insertSymptoms(String id, List<String> symptoms) throws SQLException {
        if (symptoms.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = live().prepareStatement(
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
        try (PreparedStatement ps = live().prepareStatement(
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
                live().prepareStatement("SELECT body_json FROM experience_entry WHERE id = ?")) {
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
            // entry symbol equals/encloses the cue, cue's package holds it, or entry symbol
            // is under the cue. Sprint 21e: '#' enclosure variants BOTH directions so a
            // type-level anchor matches a member cue and a member anchor matches its
            // type's cue (member notation never splits type-level matching).
            clauses.add("(symbol_fqn = ? OR ? LIKE symbol_fqn || '.%' OR ? LIKE symbol_fqn || '#%'"
                + " OR ? LIKE package_name || '.%'"
                + " OR symbol_fqn LIKE ? || '.%' OR symbol_fqn LIKE ? || '#%')");
            params.add(q.symbol());
            params.add(q.symbol());
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
            // v2.2.3: tokenized — each cue token must match a symptom OR the summary;
            // the old single-substring LIKE missed non-adjacent cue words.
            List<String> tokenClauses = new ArrayList<>();
            for (String token : normalize(q.symptom()).split("\\s+")) {
                if (token.isBlank()) {
                    continue;
                }
                tokenClauses.add("(id IN (SELECT entry_id FROM experience_symptom WHERE symptom LIKE ?)"
                    + " OR LOWER(summary) LIKE ?)");
                params.add("%" + token + "%");
                params.add("%" + token + "%");
            }
            if (!tokenClauses.isEmpty()) {
                clauses.add("(" + String.join(" AND ", tokenClauses) + ")");
            }
        }
        String sql = "SELECT id,type,symbol_fqn,package_name,operation,status,confidence,language,source_ref,scope_kind,"
            + "external_system,summary,body_json,workspace_id,created_at FROM experience_entry WHERE ("
            + String.join(" OR ", clauses)
            + ") AND status NOT IN ('rejected', 'superseded') ORDER BY created_at DESC";

        List<StoredEntry> out = new ArrayList<>();
        try (PreparedStatement ps = live().prepareStatement(sql)) {
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
            rs.getString("source_ref"),
            rs.getString("scope_kind"),
            rs.getString("workspace_id"),
            ts == null ? null : ts.toInstant(),
            body);
    }

    private List<String> loadSymptoms(String id) throws SQLException {
        List<String> symptoms = new ArrayList<>();
        try (PreparedStatement ps = live().prepareStatement(
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
        try (Statement s = live().createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM experience_entry")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to count entries: " + e.getMessage(), e);
        }
    }

    // --- Sprint 21a (item G): curation — export / import / list -------------------------

    private static final String ALL_COLUMNS =
        "id,type,scope_kind,symbol_fqn,package_name,operation,status,confidence,"
        + "fault_owner,external_system,summary,source_ref,body_json,created_at,updated_at,"
        + "workspace_id,project_id,language";

    @Override
    @SuppressWarnings("unchecked")
    public synchronized List<Map<String, Object>> exportEntries(String status, String type) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            clauses.add("status = ?");
            params.add(status);
        }
        if (type != null && !type.isBlank()) {
            clauses.add("type = ?");
            params.add(type);
        }
        String sql = "SELECT " + ALL_COLUMNS + " FROM experience_entry"
            + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
            + " ORDER BY created_at";
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = live().prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String id = rs.getString("id");
                    row.put("id", id);
                    for (String col : new String[] {"type", "scope_kind", "symbol_fqn",
                            "package_name", "operation", "status", "confidence", "fault_owner",
                            "external_system", "summary", "source_ref", "workspace_id",
                            "project_id", "language"}) {
                        Object v = rs.getString(col);
                        if (v != null) {
                            row.put(col, v);
                        }
                    }
                    Timestamp created = rs.getTimestamp("created_at");
                    Timestamp updated = rs.getTimestamp("updated_at");
                    if (created != null) {
                        row.put("created_at", created.toInstant().toString());
                    }
                    if (updated != null) {
                        row.put("updated_at", updated.toInstant().toString());
                    }
                    try {
                        row.put("body", json.readValue(rs.getString("body_json"), LinkedHashMap.class));
                    } catch (Exception e) {
                        row.put("body", new LinkedHashMap<>());
                    }
                    List<String> symptoms = loadSymptoms(id);
                    if (!symptoms.isEmpty()) {
                        row.put("symptoms", symptoms);
                    }
                    List<Map<String, Object>> links = loadLinks(id);
                    if (!links.isEmpty()) {
                        row.put("links", links);
                    }
                    out.add(row);
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to export entries: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Map<String, Object> importEntries(List<Map<String, Object>> entries) {
        int imported = 0;
        int duplicates = 0;
        int invalid = 0;
        for (Map<String, Object> row : entries == null ? List.<Map<String, Object>>of() : entries) {
            String id = str(row.get("id"));
            if (id == null || id.isBlank()) {
                invalid++;
                continue;
            }
            if (idExists(id)) {
                duplicates++;
                continue;
            }
            try {
                String body;
                Object bodyObj = row.get("body");
                body = bodyObj == null ? "{}" : json.writeValueAsString(bodyObj);
                try (PreparedStatement ps = live().prepareStatement(
                        "INSERT INTO experience_entry (" + ALL_COLUMNS
                        + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, id);
                    ps.setString(2, str(row.get("type")));
                    ps.setString(3, str(row.get("scope_kind")));
                    ps.setString(4, str(row.get("symbol_fqn")));
                    ps.setString(5, str(row.get("package_name")));
                    ps.setString(6, str(row.get("operation")));
                    ps.setString(7, row.get("status") == null ? ExperienceEntry.CANDIDATE : str(row.get("status")));
                    ps.setString(8, str(row.get("confidence")));
                    ps.setString(9, str(row.get("fault_owner")));
                    ps.setString(10, str(row.get("external_system")));
                    ps.setString(11, str(row.get("summary")));
                    ps.setString(12, str(row.get("source_ref")));
                    ps.setString(13, body);
                    ps.setTimestamp(14, parseInstant(row.get("created_at")));
                    ps.setTimestamp(15, parseInstant(row.get("updated_at")));
                    ps.setString(16, str(row.get("workspace_id")));
                    ps.setString(17, str(row.get("project_id")));
                    String lang = str(row.get("language"));
                    ps.setString(18, lang == null || lang.isBlank() ? "java" : lang);
                    ps.executeUpdate();
                }
                if (row.get("symptoms") instanceof List<?> symptoms) {
                    List<String> ss = new ArrayList<>();
                    for (Object s : symptoms) {
                        ss.add(String.valueOf(s));
                    }
                    insertSymptoms(id, ss);
                }
                if (row.get("links") instanceof List<?> links) {
                    List<ExperienceEntry.Link> ls = new ArrayList<>();
                    for (Object l : links) {
                        if (l instanceof Map<?, ?> lm) {
                            ls.add(new ExperienceEntry.Link(
                                str(lm.get("rel")), str(lm.get("target"))));
                        }
                    }
                    insertLinks(id, ls);
                }
                imported++;
            } catch (Exception e) {
                log.warn("import: cannot insert {}: {}", id, e.getMessage());
                invalid++;
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("imported", imported);
        report.put("duplicates", duplicates);
        report.put("invalid", invalid);
        return report;
    }

    @Override
    public synchronized List<StoredEntry> listEntries(String type, String status, String scope,
            String language, int limit) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            clauses.add("type = ?");
            params.add(type);
        }
        if (status != null && !status.isBlank()) {
            clauses.add("status = ?");
            params.add(status);
        }
        if (scope != null && !scope.isBlank()) {
            clauses.add("(symbol_fqn = ? OR symbol_fqn LIKE ? OR package_name = ? OR package_name LIKE ?)");
            params.add(scope);
            params.add(scope + ".%");
            params.add(scope);
            params.add(scope + ".%");
        }
        if (language != null && !language.isBlank()) {
            clauses.add("LOWER(language) = LOWER(?)");
            params.add(language);
        }
        String sql = "SELECT id,type,symbol_fqn,package_name,operation,status,confidence,language,source_ref,scope_kind,"
            + "external_system,summary,body_json,workspace_id,created_at FROM experience_entry"
            + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
            + " ORDER BY created_at DESC LIMIT " + Math.max(1, limit);
        List<StoredEntry> out = new ArrayList<>();
        try (PreparedStatement ps = live().prepareStatement(sql)) {
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
            throw new IllegalStateException("failed to list entries: " + e.getMessage(), e);
        }
    }

    // --- Sprint 21a (item G): hygiene — prune / compact ---------------------------------

    @Override
    public synchronized int pruneAged(int days) {
        Timestamp cutoff = Timestamp.from(Instant.now().minusSeconds(Math.max(0, days) * 86400L));
        try {
            for (String child : new String[] {"experience_symptom", "experience_link"}) {
                try (PreparedStatement ps = live().prepareStatement(
                        "DELETE FROM " + child + " WHERE entry_id IN (SELECT id FROM experience_entry"
                        + " WHERE status IN ('rejected','superseded') AND updated_at < ?)")) {
                    ps.setTimestamp(1, cutoff);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = live().prepareStatement(
                    "DELETE FROM experience_entry"
                    + " WHERE status IN ('rejected','superseded') AND updated_at < ?")) {
                ps.setTimestamp(1, cutoff);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to prune: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Map<String, Object> compact() {
        Map<String, Object> report = new LinkedHashMap<>();
        if (storeFile == null) {
            report.put("compacted", false);
            report.put("reason", "in-memory store");
            return report;
        }
        long before = fileSize(storeFile);
        try {
            try (Statement s = live().createStatement()) {
                s.execute("SHUTDOWN COMPACT");       // closes the database (and this conn)
            }
            closeQuietly(conn);
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL(url);
            conn = ds.getConnection();               // reopen on the compacted file
        } catch (SQLException e) {
            throw new IllegalStateException("failed to compact: " + e.getMessage(), e);
        }
        report.put("compacted", true);
        report.put("bytes_before", before);
        report.put("bytes_after", fileSize(storeFile));
        log.info("Experience store compacted: {}", report);
        return report;
    }

    private static long fileSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }

    @Override
    public synchronized Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", count());
        out.put("by_status", groupCount("status"));
        out.put("by_language", groupCount("language"));
        Map<String, Object> store = new LinkedHashMap<>();
        if (storeFile != null) {
            store.put("file", storeFile.toAbsolutePath().toString());
            store.put("bytes", fileSize(storeFile));
        } else {
            store.put("file", "in-memory");
        }
        out.put("store", store);
        return out;
    }

    private Map<String, Object> groupCount(String column) {
        Map<String, Object> counts = new LinkedHashMap<>();
        try (Statement s = live().createStatement();
                ResultSet rs = s.executeQuery(
                    "SELECT " + column + ", COUNT(*) FROM experience_entry GROUP BY " + column
                    + " ORDER BY " + column)) {
            while (rs.next()) {
                String key = rs.getString(1);
                counts.put(key == null ? "(none)" : key, rs.getLong(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to count by " + column + ": " + e.getMessage(), e);
        }
        return counts;
    }

    private List<Map<String, Object>> loadLinks(String id) throws SQLException {
        List<Map<String, Object>> links = new ArrayList<>();
        try (PreparedStatement ps = live().prepareStatement(
                "SELECT rel, target FROM experience_link WHERE entry_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    links.add(Map.of("rel", rs.getString(1), "target", rs.getString(2)));
                }
            }
        }
        return links;
    }

    private static Timestamp parseInstant(Object iso) {
        if (iso == null) {
            return Timestamp.from(Instant.now());
        }
        try {
            return Timestamp.from(Instant.parse(String.valueOf(iso)));
        } catch (Exception e) {
            return Timestamp.from(Instant.now());
        }
    }

    // --- Sprint 21a (item A): one-time recovery of orphaned per-session stores ----------

    /**
     * Earlier releases opened the store inside the launcher's session-isolation dir —
     * orphaned on every redeploy and DELETED on clean shutdown. Scan the stable workspace
     * root's session subdirs (newest first) for {@code jawata-experience/experience.mv.db},
     * import their entries into THIS store (dedup by id; provenance stamped; language
     * backfilled), and mark each swept source with a {@code .jawata-recovered} file so the
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
                .filter(d -> Files.isRegularFile(d.resolve("jawata-experience").resolve("experience.mv.db")))
                .sorted(Comparator.comparingLong(H2ExperienceStore::lastModified).reversed())
                .forEach(candidates::add);
        } catch (IOException e) {
            log.warn("recovery: cannot list {}: {}", workspaceRoot, e.getMessage());
        }
        for (Path sessionDir : candidates) {
            Path expDir = sessionDir.resolve("jawata-experience");
            Path db = expDir.resolve("experience.mv.db");
            Path marker = expDir.resolve(".jawata-recovered");
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
                try (PreparedStatement ps = live().prepareStatement(
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
                    PreparedStatement ins = live().prepareStatement(
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
                    PreparedStatement ins = live().prepareStatement(
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
        try (PreparedStatement ps = live().prepareStatement(
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
