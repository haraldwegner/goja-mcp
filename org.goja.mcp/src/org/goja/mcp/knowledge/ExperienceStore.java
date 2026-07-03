package org.goja.mcp.knowledge;

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

    /** Fetch an entry's stored document by id, or empty when absent. */
    Optional<Map<String, Object>> get(String id);

    /** Total entry count — diagnostics + tests. */
    long count();

    @Override
    void close();
}
