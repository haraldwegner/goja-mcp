package org.jawata.mcp.learn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jawata.mcp.knowledge.AnalogyPolicy;
import org.jawata.mcp.knowledge.EmbeddingIndex;
import org.jawata.mcp.knowledge.ToolExperienceStore;

/**
 * Sprint 27 D3 — the semantic {@link PrecedentRetriever}: the Sprint-26a swap
 * this seam was built for.
 *
 * <p>The gather is a UNION, keyword first: everything the baseline
 * {@code recentMatching} finds (the identity set — v3.3.1's exact behaviour),
 * plus meaning-nearest captures the keyword could not reach. Widening happens
 * ONLY on what the agent can SEE; what the ledger may CHARGE stays pinned to
 * {@link IdentityMatch} at the choke, which filters this list. A broken or
 * absent embedder degrades to exactly the keyword result — the v3.3.1
 * contract, kept.</p>
 */
public final class EmbeddingPrecedentRetriever implements PrecedentRetriever {

    // Semantic widening was capped at a fixed five with a fixed floor; Sprint
    // 27a hands that judgement to AnalogyPolicy, which caps and floors by the
    // one rule every surface shares.

    private final ToolExperienceStore store;
    private final EmbeddingIndex index;      // may be null: keyword-only degrade

    public EmbeddingPrecedentRetriever(ToolExperienceStore store, EmbeddingIndex index) {
        this.store = store;
        this.index = index;
    }

    @Override
    public List<ToolExperience> retrieve(String query, int limit) {
        if (store == null || query == null || query.isBlank()) {
            return List.of();
        }
        List<ToolExperience> keyword = store.recentMatching(query, limit);
        if (index == null || !index.available()) {
            return keyword;                    // exactly v3.3.1
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ToolExperience e : keyword) {
            seen.add(e.situation() + "|" + e.tool() + "|" + e.outcome());
        }
        List<ToolExperience> out = new ArrayList<>(keyword);
        try {
            // Sprint 27a D2: the advisory tier passes THE POLICY before speaking,
            // replacing the fixed cap-of-five and the old nomination floor. The
            // policy judges the whole score profile — a similar-case advisory
            // appears when one genuinely stands out, and nothing when none does,
            // the same rule the recall front door obeys. Meaning-only here: the
            // lexical stream is over the experience store, not this lane.
            List<String> ids = AnalogyPolicy.nominate(
                index.toolExperienceProfile(query), java.util.Map.of());
            for (ToolExperience e : store.byIds(ids)) {
                if (seen.add(e.situation() + "|" + e.tool() + "|" + e.outcome())) {
                    out.add(e);
                }
            }
        } catch (RuntimeException ex) {
            // The union must never make retrieval WORSE than keyword-only.
            return keyword;
        }
        return out;
    }
}
