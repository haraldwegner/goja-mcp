package org.jawata.mcp.knowledge;

import org.jawata.mcp.learn.ToolExperience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistence for the D2 experience loop (Sprint 26a, schema v6):
 * {@code tool_experience} — the selective outcome-bearing capture the baseline
 * retriever (Stage 2) reads back. Rides the SAME H2 file and provenance as the
 * experience store and reuses {@link LearnerEventStore#withRetry} — the exact
 * F1-proven retry/loud path — so a write that fails every attempt is COUNTED
 * ({@link #failedWrites()}) and logged, never silently dropped. An experience
 * loop that quietly drops rows would surface false "no precedent" answers — the
 * empty-result-is-a-lie class.
 */
public final class ToolExperienceStore {

    private static final Logger log = LoggerFactory.getLogger(ToolExperienceStore.class);

    private final H2ExperienceStore store;
    private final AtomicLong failedWrites = new AtomicLong();

    public ToolExperienceStore(H2ExperienceStore store) {
        this.store = store;
    }

    /** Appends one outcome-bearing capture. Never throws — a final failure is
     *  logged + counted (loud, visible). */
    public void append(ToolExperience e) {
        synchronized (store) {
            try {
                LearnerEventStore.withRetry(LearnerEventStore.WRITE_ATTEMPTS, () -> {
                    Connection conn = store.sharedConnection();
                    try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO tool_experience (session_id, situation, tool, outcome,"
                            + " detail_json, workspace_id, project_id)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                        ps.setString(1, e.sessionId());
                        ps.setString(2, e.situation());
                        ps.setString(3, e.tool());
                        ps.setString(4, e.outcome());
                        ps.setString(5, e.detailJson());
                        ps.setString(6, store.provenanceWorkspaceId());
                        ps.setString(7, store.provenanceProjectId());
                        ps.executeUpdate();
                    }
                }, store::invalidateSharedConnection);
            } catch (Exception ex) {
                failedWrites.incrementAndGet();
                log.error("TOOL EXPERIENCE DROPPED after {} attempts ({} total) — tool={}"
                        + " outcome={}: the experience loop is incomplete until this is fixed",
                    LearnerEventStore.WRITE_ATTEMPTS, failedWrites.get(), e.tool(), e.outcome(), ex);
            }
        }
    }

    /** Total captured rows; {@code -1} on a failed query — a failure is never a clean zero. */
    public long count() {
        synchronized (store) {
            try {
                Connection conn = store.sharedConnection();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM tool_experience");
                     ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            } catch (Exception e) {
                log.error("tool_experience count query failed", e);
                return -1L;
            }
        }
    }

    /**
     * The most recent captures whose situation contains ALL whitespace-separated
     * terms of {@code situationQuery} (baseline keyword retrieval — Sprint 27
     * swaps this for embeddings behind the retrieval seam). Newest first, capped
     * at {@code limit}. An empty query returns the most recent captures.
     */
    public List<ToolExperience> recentMatching(String situationQuery, int limit) {
        synchronized (store) {
            List<ToolExperience> out = new ArrayList<>();
            String[] terms = situationQuery == null || situationQuery.isBlank()
                ? new String[0]
                : situationQuery.trim().toLowerCase().split("\\s+");
            try {
                Connection conn = store.sharedConnection();
                StringBuilder sql = new StringBuilder(
                    "SELECT session_id, situation, tool, outcome, detail_json FROM tool_experience");
                for (int i = 0; i < terms.length; i++) {
                    sql.append(i == 0 ? " WHERE" : " AND").append(" LOWER(situation) LIKE ?");
                }
                sql.append(" ORDER BY id DESC LIMIT ?");
                try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                    int p = 1;
                    for (String term : terms) {
                        ps.setString(p++, "%" + term + "%");
                    }
                    ps.setInt(p, Math.max(1, limit));
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.add(new ToolExperience(rs.getString(1), rs.getString(2),
                                rs.getString(3), rs.getString(4), rs.getString(5)));
                        }
                    }
                }
            } catch (Exception e) {
                log.error("tool_experience retrieval failed for '{}'", situationQuery, e);
            }
            return out;
        }
    }

    /** Dropped-write count — surfaced, never hidden. */
    public long failedWrites() {
        return failedWrites.get();
    }
}
