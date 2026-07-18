package org.jawata.mcp.knowledge;

/**
 * Sprint 21 Stage 2 — a retrieval cue. Any subset of the fields may be set; the store
 * gathers candidates by matching ANY present cue (Phase 1), then the {@link
 * ExperienceRetrieval} fit-gate keeps only entries whose scope <em>contains</em> the cue
 * (Phase 2). All-blank is an empty query (returns absence).
 *
 * @param symbol         an FQN the agent is working on (type or {@code Type#member})
 * @param packageName    a package the agent is working in
 * @param operation      the operation about to run (e.g. {@code rename_symbol})
 * @param symptom        an observed symptom / cue phrase (alias-normalized on match)
 * @param externalSystem an external dependency implicated in a symptom
 */
public record RecallQuery(String symbol, String packageName, String operation,
                          String symptom, String externalSystem) {

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** True when no cue is set — an empty query resolves to absence, never a scan. */
    public boolean isEmpty() {
        return blank(symbol) && blank(packageName) && blank(operation)
            && blank(symptom) && blank(externalSystem);
    }

    /**
     * Whether the {@code symbol} cue is set.
     * @return {@code true} when {@code symbol} is non-null and non-blank
     */
    public boolean hasSymbol() {
        return !blank(symbol);
    }

    /**
     * Whether the {@code packageName} cue is set.
     * @return {@code true} when {@code packageName} is non-null and non-blank
     */
    public boolean hasPackage() {
        return !blank(packageName);
    }

    /**
     * Whether the {@code operation} cue is set.
     * @return {@code true} when {@code operation} is non-null and non-blank
     */
    public boolean hasOperation() {
        return !blank(operation);
    }

    /**
     * Whether the {@code symptom} cue is set.
     * @return {@code true} when {@code symptom} is non-null and non-blank
     */
    public boolean hasSymptom() {
        return !blank(symptom);
    }

    /**
     * Whether the {@code externalSystem} cue is set.
     * @return {@code true} when {@code externalSystem} is non-null and non-blank
     */
    public boolean hasExternalSystem() {
        return !blank(externalSystem);
    }
}
