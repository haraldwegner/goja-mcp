package org.goja.mcp.knowledge;

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

    public boolean hasSymbol() {
        return !blank(symbol);
    }

    public boolean hasPackage() {
        return !blank(packageName);
    }

    public boolean hasOperation() {
        return !blank(operation);
    }

    public boolean hasSymptom() {
        return !blank(symptom);
    }

    public boolean hasExternalSystem() {
        return !blank(externalSystem);
    }
}
