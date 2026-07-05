package org.goja.mcp.knowledge;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 21 (v2.0): the local experience/knowledge store — embedded, workspace-scoped,
 * H2-backed. Opened at application start, closed at stop. This is the persistence the
 * {@code ExperienceAdvisor} (which fills {@code org.goja.mcp.domain.Advisor}) reads and
 * writes.
 *
 * <p>Entries are {@link SymbolFact}s (the shared Sprint-15a shape). Stage 0 defines the
 * open/close lifecycle plus a single {@link #put}/{@link #get} round-trip and the schema;
 * richer indexed persistence (Stage 1) and two-phase fit-gated retrieval (Stage 2) build
 * on this seam. {@link #get} returns the stored document as a map (a typed reconstruction
 * is a later concern) so the store never needs to reverse {@link SymbolFact#toMap()}.</p>
 */
public interface ExperienceStore extends AutoCloseable {

    /** Persist a fact as a {@code candidate} entry; returns the generated entry id. */
    String put(SymbolFact fact);

    /** Persist a full entry (with retrieval facets: status/scope/operation/symptoms/links). */
    String put(ExperienceEntry entry);

    /**
     * Persist an entry tagged with an origin marker (Stage 4). A re-load of the same
     * {@code sourceRef} is made idempotent by {@link #deleteBySource} first — so seeding
     * from a memory file twice replaces rather than duplicates.
     */
    String putWithSource(ExperienceEntry entry, String sourceRef);

    /** Remove all entries (+ children) that came from a given source; returns rows removed. */
    int deleteBySource(String sourceRef);

    /** Remove everything (maintenance: wipe); returns the entry count removed. */
    long wipe();

    /** Every entry (maintenance: refresh re-resolves their pointers through JDT). */
    List<StoredEntry> all();

    /** Fetch an entry's stored document by id, or empty when absent. */
    Optional<Map<String, Object>> get(String id);

    /**
     * Phase-1 candidate gather (Sprint 21 Stage 2): keyword/alias-match any present cue over
     * the indexed scope columns + the symptom child table. Deliberately <em>generous</em> —
     * the {@link ExperienceRetrieval} fit-gate does the precise scope-containment. Rejected
     * entries are excluded. An empty query returns an empty list.
     */
    List<StoredEntry> query(RecallQuery query);

    /** Update an entry's curation status; returns true when a row changed. */
    boolean setStatus(String id, String status);

    /** Total entry count — diagnostics + tests. */
    long count();

    /**
     * Sprint 21a (item B): provenance stamped on every subsequent write — the workspace +
     * project this resident serves (from {@code workspace.json} at store-open). Enables the
     * user-level shared store (item H) to keep per-workspace attribution. No-op by default.
     */
    default void setProvenance(String workspaceId, String projectId) {
    }

    @Override
    void close();
}
