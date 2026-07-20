package org.jawata.mcp.learn;

import org.jawata.mcp.knowledge.ToolExperienceStore;

import java.util.List;

/**
 * The baseline (non-ML) D2 retriever (Sprint 26a): keyword match over the
 * {@code tool_experience} lane — extends the Sprint-26 keyword recall to the
 * tool-outcome rows. Deliberately simple: it exists to prove the loop
 * end-to-end (capture → retrieve → surface) so Stage 2 is a working slice, not
 * a storage layer. Sprint 27 replaces THIS class with an embedding retriever
 * behind {@link PrecedentRetriever}; nothing else changes.
 */
public final class KeywordPrecedentRetriever implements PrecedentRetriever {

    private final ToolExperienceStore store;

    public KeywordPrecedentRetriever(ToolExperienceStore store) {
        this.store = store;
    }

    @Override
    public List<ToolExperience> retrieve(String query, int limit) {
        if (store == null || query == null || query.isBlank()) {
            return List.of();
        }
        return store.recentMatching(query, limit);
    }
}
