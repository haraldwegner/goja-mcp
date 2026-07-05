package org.goja.mcp.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Sprint 21 Stage 2 — a Phase-1 candidate row projected out of {@code experience_entry}
 * (plus its alias-normalized symptoms). Carries the indexed scope columns the fit-gate
 * needs, the current column {@code status} (which the frozen {@code body_json} does not
 * reflect after a {@link ExperienceStore#setStatus}), and the parsed {@code body}
 * document to return.
 */
public record StoredEntry(String id, String type, String symbolFqn, String packageName,
                          String operation, String status, String confidence, String language,
                          String externalSystem, String summary, List<String> symptoms,
                          Instant createdAt, Map<String, Object> body) {

    /**
     * Sprint 21a (item I): true when this entry's anchor may be judged by the JDT
     * resolver. Non-Java anchors (rust, ts, …) are OPAQUE to maintenance — never staled
     * or superseded by a resolver that cannot see them. Null/blank = Java-era rows.
     */
    public boolean isJavaResolvable() {
        return language == null || language.isBlank() || "java".equalsIgnoreCase(language);
    }

    /** Scope-specificity rank for disambiguation — higher = more specific = preferred. */
    public int specificity() {
        if (symbolFqn != null && !symbolFqn.isBlank()) {
            return 3;                       // symbol-scoped: the most specific
        }
        if (packageName != null && !packageName.isBlank()) {
            return 2;                       // package-scoped
        }
        if (operation != null && !operation.isBlank()) {
            return 1;                       // operation-scoped
        }
        return 0;                           // symptom / broad
    }

    /** Confidence rank (high › medium › low › unknown) for disambiguation tie-breaks. */
    public int confidenceRank() {
        if (confidence == null) {
            return 0;
        }
        return switch (confidence.toLowerCase(java.util.Locale.ROOT)) {
            case "high" -> 3;
            case "medium" -> 2;
            case "low" -> 1;
            default -> 0;
        };
    }
}
