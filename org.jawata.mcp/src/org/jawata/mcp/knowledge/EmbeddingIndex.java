package org.jawata.mcp.knowledge;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 27 D2 — meaning-nearest NOMINATION over the store.
 *
 * <p>Brute-force cosine across every stored vector. At this corpus size (a few
 * thousand entries × 384 floats) that is a few milliseconds, and it is exactly
 * right: an approximate index would add a second source of "not found" that
 * nobody could distinguish from a true absence, to buy speed we do not need.</p>
 *
 * <p><b>K and the floor are VOLUME caps, never relevance decisions.</b> The
 * floor is {@link #NOMINATION_FLOOR}, derived at C0 from measured score
 * distributions rather than guessed: designated and unrelated scores OVERLAP on
 * the real corpus, so no threshold separates them and none is asked to. It sits
 * below the observed minimum of a true hit and removes only obvious noise;
 * ranking and the kind-split fit rules do the actual work. (The 0.35 that was
 * once a placeholder would have discarded a third of the true hits.)</p>
 *
 * <p>Only vectors of the CURRENT embedder identity are considered. Comparing
 * across identities would produce a number that looks like a score and means
 * nothing, so stale rows are invisible here until {@link #backfill} re-embeds
 * them.</p>
 */
public final class EmbeddingIndex {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingIndex.class);

    /** Nomination width — C0 confirmed it holds 10 of 12 calibration targets. */
    public static final int DEFAULT_K = 12;

    /** C0-derived volume cap. NOT a relevance threshold — see the class note. */
    public static final double NOMINATION_FLOOR = 0.15;

    /**
     * D5's write-path dedup line, RE-DERIVED at Stage 6 from 84 hand-labeled
     * corpus pairs ({@code dedup-labels.json}): every pair at or above it is a
     * true duplicate (measured precision 1.000), and the highest-scoring pair
     * that is NOT a duplicate sits at 0.8970 — a real margin rather than the
     * conservative guess C0 could only make from one content pair.
     *
     * <p>What this line does NOT claim: coverage. It proposes 3 of the 40
     * labeled duplicate pairs. Dedup here is deliberately high-precision and
     * low-recall, because D5 only proposes and a human confirms.</p>
     */
    public static final double DEDUP_THRESHOLD = 0.92;

    private final H2ExperienceStore store;
    private final EmbeddingService embeddings;

    /**
     * @param store      the H2 store holding the vectors (the CONCRETE one — in
     *                   production reach it through
     *                   {@link RecoveringExperienceStore#currentDelegate()})
     * @param embeddings the embedder; an unavailable one makes every query
     *                   return empty rather than fail
     */
    public EmbeddingIndex(H2ExperienceStore store, EmbeddingService embeddings) {
        this.store = store;
        this.embeddings = embeddings;
    }

    /** One nominated row and the score that ranked it. */
    public record Hit(String id, double score) {
    }

    /** Whether meaning nomination can answer at all. False = the keyword
     *  degrade path, which is a supported state and not an error. */
    public boolean available() {
        return embeddings.available();
    }

    /** Meaning-nearest knowledge entries for {@code cue}; empty when unavailable. */
    public List<Hit> nearestEntries(String cue, int k, double floor) {
        return nearest("experience_entry", "id", cue, k, floor);
    }

    /** Meaning-nearest tool-experience rows for {@code cue}; empty when unavailable. */
    public List<Hit> nearestToolExperience(String cue, int k, double floor) {
        return nearest("tool_experience", "id", cue, k, floor);
    }

    /** Meaning-nearest entries at the plan's volume caps ({@link #DEFAULT_K},
     *  {@link #NOMINATION_FLOOR}) — the ordinary nomination call. */
    public List<Hit> nearestEntries(String cue) {
        return nearestEntries(cue, DEFAULT_K, NOMINATION_FLOOR);
    }

    private List<Hit> nearest(String table, String idColumn, String cue, int k, double floor) {
        float[] q = embeddings.embed(cue);
        if (q == null) {
            return List.of();                 // unavailable or blank cue - not "nothing matched"
        }
        List<Hit> hits = new ArrayList<>();
        String sql = "SELECT " + idColumn + ", embedding FROM " + table
            + " WHERE embedding IS NOT NULL AND embedder_identity = ?";
        try (PreparedStatement ps = store.sharedConnection().prepareStatement(sql)) {
            ps.setString(1, embeddings.identityKey());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    float[] v = EmbeddingService.fromBytes(rs.getBytes(2));
                    if (v == null) {
                        continue;
                    }
                    double score = EmbeddingService.cosine(q, v);
                    if (score >= floor) {
                        hits.add(new Hit(rs.getString(1), score));
                    }
                }
            }
        } catch (SQLException e) {
            log.error("semantic nomination over {} FAILED; the caller falls back to keyword"
                + " nomination for this cue", table, e);
            return List.of();
        }
        hits.sort(Comparator.comparingDouble(Hit::score).reversed());
        return hits.size() > k ? new ArrayList<>(hits.subList(0, k)) : hits;
    }

    /**
     * Embed rows that have no CURRENT-identity vector — the self-heal path.
     *
     * <p>It covers both reasons a row can lack one: it was written before
     * embedding existed (or while the embedder was down), or it carries a vector
     * from a SUPERSEDED identity, which must be recomputed rather than compared.
     * Mirrors how {@code LOADER_VERSION} re-ingests the knowledge store when the
     * loader changes.</p>
     *
     * @param max stop after this many rows, so a first run on a large store
     *            cannot stall startup; the remainder is picked up next time
     * @return how many rows were embedded
     */
    public int backfill(int max) {
        if (!embeddings.available()) {
            return 0;
        }
        // experience_entry passes body_json too, so the backfill reconstructs
        // EXACTLY the text embed-on-write used (summary + details). If the two
        // paths disagreed, the same entry would carry different vectors
        // depending on which last touched it.
        int done = backfillTable("experience_entry", "id", "summary", "body_json", max);
        done += backfillTable("tool_experience", "id", "situation", null, max - done);
        if (done > 0) {
            log.info("embedding backfill: {} row(s) brought to identity {}",
                done, embeddings.identityKey());
        }
        return done;
    }

    private int backfillTable(String table, String idColumn, String textColumn,
                             String detailsColumn, int max) {
        if (max <= 0) {
            return 0;
        }
        record Pending(String id, String text) {
        }
        List<Pending> pending = new ArrayList<>();
        String select = "SELECT " + idColumn + ", " + textColumn
            + (detailsColumn == null ? "" : ", " + detailsColumn) + " FROM " + table
            + " WHERE embedding IS NULL OR embedder_identity IS NULL"
            + " OR embedder_identity <> ? LIMIT " + max;
        try (PreparedStatement ps = store.sharedConnection().prepareStatement(select)) {
            ps.setString(1, embeddings.identityKey());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String details = detailsColumn == null
                        ? null : detailsOf(rs.getString(3));
                    pending.add(new Pending(rs.getString(1),
                        EmbeddingService.textOf(rs.getString(2), details)));
                }
            }
        } catch (SQLException e) {
            log.error("embedding backfill could not READ {} - rows stay keyword-reachable"
                + " and will be retried", table, e);
            return 0;
        }

        int done = 0;
        String update = "UPDATE " + table + " SET embedding = ?, embedder_identity = ?"
            + " WHERE " + idColumn + " = ?";
        for (Pending p : pending) {
            float[] v = embeddings.embed(p.text());
            if (v == null) {
                continue;                      // blank text or a failure already logged
            }
            try (PreparedStatement ps = store.sharedConnection().prepareStatement(update)) {
                ps.setBytes(1, EmbeddingService.toBytes(v));
                ps.setString(2, embeddings.identityKey());
                ps.setString(3, p.id());
                ps.executeUpdate();
                done++;
            } catch (SQLException e) {
                log.error("embedding backfill could not WRITE {} row {}", table, p.id(), e);
            }
        }
        return done;
    }

    /**
     * Pull {@code details} out of a stored {@code body_json} so the backfill
     * embeds the same text the write path did. A body we cannot parse yields
     * no details rather than an exception — the summary alone still gives a
     * usable vector, and refusing to embed the row would be worse.
     */
    private static String detailsOf(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode n =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(bodyJson);
            com.fasterxml.jackson.databind.JsonNode d = n.get("details");
            return d == null || d.isNull() ? null : d.asText();
        } catch (Exception e) {
            log.debug("unparseable body_json during backfill; embedding the summary alone");
            return null;
        }
    }

    /** How many rows carry a vector of the current identity — the honest coverage number. */
    public long embeddedCount(String table) {
        try (PreparedStatement ps = store.sharedConnection().prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE embedder_identity = ?")) {
            ps.setString(1, embeddings.identityKey());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            log.error("could not count embedded rows in {}", table, e);
            return -1L;                        // -1 is "could not look", never 0
        }
    }

    /** Total rows in the table, for reporting coverage as a fraction rather than a count. */
    public long totalCount(String table) {
        // NOTE: the connection is SHARED and must never be closed here - it is
        // owned by the store and closing it would break every other caller.
        // Only the Statement/ResultSet are ours to release.
        try (Statement s = store.sharedConnection().createStatement();
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            log.error("could not count rows in {}", table, e);
            return -1L;
        }
    }
}
