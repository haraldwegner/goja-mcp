package org.jawata.mcp.learn;

import java.util.List;

/**
 * The D2 retrieval SEAM (Sprint 26a): "for a situation, what did tools do in
 * similar cases before?" — the one interface the weighted precedent push reads
 * through. Sprint 26a ships a baseline keyword implementation
 * ({@link KeywordPrecedentRetriever}); Sprint 27 swaps in a MiniLM-embedding
 * implementation over the SAME capture schema and the SAME push seam, without
 * touching capture, store, or the choke. Keeping the push behind this interface
 * is what makes that swap a one-class change.
 */
public interface PrecedentRetriever {

    /**
     * The most relevant past captures for {@code query} (a target key — the
     * thing being worked on), newest/closest first, at most {@code limit}.
     * Returns an empty list when nothing matches — never null.
     */
    List<ToolExperience> retrieve(String query, int limit);
}
