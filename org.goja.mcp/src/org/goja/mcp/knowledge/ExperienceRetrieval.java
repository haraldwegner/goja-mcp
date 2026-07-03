package org.goja.mcp.knowledge;

import org.eclipse.jdt.core.IType;
import org.goja.core.IJdtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 21 Stage 2 — the two-phase, fit-gated, <em>terminal</em> retrieval contract
 * (recall-gap design notes §7). A cue resolves to a closed, scope-filtered set of fitting
 * nodes (pointers resolved to current code through JDT) OR an authoritative absence —
 * never a similarity-ranked pile the agent must sift.
 *
 * <ul>
 *   <li><b>Phase 1 (fuzzy gather):</b> {@link ExperienceStore#query} keyword/alias-matches
 *       any present cue over the indexed columns + the symptom child table. Deterministic
 *       (LIKE/normalize) — embeddings are Sprint 27, behind this same gate.</li>
 *   <li><b>Phase 2 (fit gate):</b> keep only candidates whose scope <em>contains</em> the
 *       cue (symbol equal/enclosing, package equal/enclosing, operation equal, symptom
 *       alias-match). A candidate that merely surfaced in Phase 1 but does not contain the
 *       cue is dropped — that is the denoising step.</li>
 *   <li><b>Terminal:</b> 0 fitting → {@code absence}; ≥1 → the fit set ordered by
 *       scope-specificity › confidence › recency, each pointer JDT-resolved (or flagged
 *       stale). The single most-specific node is the head; callers wanting ≤1 (the advisor)
 *       take the head.</li>
 * </ul>
 */
public final class ExperienceRetrieval {

    private static final Logger log = LoggerFactory.getLogger(ExperienceRetrieval.class);

    /** Cap the closed fit set so a pathological cue can't return a pile; report if capped. */
    private static final int MAX_TERMINAL = 5;

    public static final String RESULT_MATCH = "match";
    public static final String RESULT_ABSENCE = "absence";

    private final ExperienceStore store;
    private final Supplier<IJdtService> jdt;

    public ExperienceRetrieval(ExperienceStore store, Supplier<IJdtService> jdt) {
        this.store = store;
        this.jdt = jdt;
    }

    /** Terminal recall — a {@code match} document with the fit set, or an {@code absence}. */
    public Map<String, Object> recall(RecallQuery q) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cue", cueMap(q));
        if (q == null || q.isEmpty()) {
            out.put("result", RESULT_ABSENCE);
            out.put("reason", "no cue provided");
            out.put("message", "No cue — provide symbol / package / operation / symptom.");
            out.put("entries", List.of());
            return out;
        }

        List<StoredEntry> candidates = store.query(q);
        List<StoredEntry> fitting = new ArrayList<>();
        for (StoredEntry e : candidates) {
            if (fits(e, q)) {
                fitting.add(e);
            }
        }
        if (fitting.isEmpty()) {
            out.put("result", RESULT_ABSENCE);
            out.put("reason", candidates.isEmpty() ? "no candidates" : "no candidate fit the cue's scope");
            out.put("message", "No known knowledge for this cue.");
            out.put("entries", List.of());
            return out;
        }

        // Disambiguate: scope-specificity › confidence › recency (newest first).
        fitting.sort(Comparator
            .comparingInt(StoredEntry::specificity).reversed()
            .thenComparing(Comparator.comparingInt(StoredEntry::confidenceRank).reversed())
            .thenComparing(e -> e.createdAt() == null ? 0L : -e.createdAt().toEpochMilli()));

        boolean capped = fitting.size() > MAX_TERMINAL;
        List<StoredEntry> top = capped ? fitting.subList(0, MAX_TERMINAL) : fitting;

        List<Map<String, Object>> entries = new ArrayList<>();
        for (StoredEntry e : top) {
            entries.add(present(e));
        }
        out.put("result", RESULT_MATCH);
        out.put("count", entries.size());
        if (capped) {
            out.put("capped_from", fitting.size());
            log.info("recall fit set capped {} -> {} for cue {}", fitting.size(), MAX_TERMINAL, cueMap(q));
        }
        out.put("entries", entries);
        return out;
    }

    // --- Phase 2: fit gate (scope-containment) -----------------------------------------

    /** True iff the entry's scope contains at least one present cue. OR over cues. */
    boolean fits(StoredEntry e, RecallQuery q) {
        if (q.hasSymbol() && symbolFits(e, q.symbol())) {
            return true;
        }
        if (q.hasPackage() && packageFits(e, q.packageName())) {
            return true;
        }
        if (q.hasOperation() && eqIgnoreCase(e.operation(), q.operation())) {
            return true;
        }
        if (q.hasExternalSystem() && eqIgnoreCase(e.externalSystem(), q.externalSystem())) {
            return true;
        }
        return q.hasSymptom() && symptomFits(e, q.symptom());
    }

    /** Symbol cue fits when the entry is scoped to that symbol (equal/enclosing) or to a
     *  package that contains it. */
    private boolean symbolFits(StoredEntry e, String symbol) {
        String s = e.symbolFqn();
        if (s != null && !s.isBlank()) {
            if (s.equals(symbol) || symbol.startsWith(s + ".") || symbol.startsWith(s + "#")) {
                return true;               // entry symbol equals or encloses the cue symbol
            }
        }
        String pkg = e.packageName();
        return pkg != null && !pkg.isBlank() && symbol.startsWith(pkg + ".");
    }

    /** Package cue fits when the entry governs that package (equal/enclosing) or holds a
     *  symbol inside it. */
    private boolean packageFits(StoredEntry e, String pkg) {
        String p = e.packageName();
        if (p != null && !p.isBlank()) {
            if (p.equals(pkg) || pkg.startsWith(p + ".")) {
                return true;               // entry package equals or encloses the cue package
            }
        }
        String s = e.symbolFqn();
        return s != null && !s.isBlank() && s.startsWith(pkg + ".");
    }

    /** Symptom cue fits when it alias-matches a stored symptom or the summary. */
    private boolean symptomFits(StoredEntry e, String symptom) {
        String norm = H2ExperienceStore.normalize(symptom);
        if (norm.isEmpty()) {
            return false;
        }
        for (String s : e.symptoms()) {
            if (s.equals(norm) || s.contains(norm) || norm.contains(s)) {
                return true;
            }
        }
        String summary = e.summary();
        return summary != null && summary.toLowerCase(Locale.ROOT).contains(norm);
    }

    private static boolean eqIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    // --- Presentation: body + current status + JDT-resolved pointer ---------------------

    private Map<String, Object> present(StoredEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("status", e.status());       // current column status (body_json is frozen at insert)
        m.putAll(e.body());
        m.put("status", e.status());       // ...and win over the frozen body value
        Map<String, Object> pointer = resolvePointer(e.symbolFqn());
        if (pointer != null) {
            m.put("resolved_pointer", pointer);
        }
        return m;
    }

    /**
     * Resolve a symbol pointer to current code through JDT (design notes §4.4). The entry
     * is the coarse index; JDT gives the exact current location, or flags it stale when the
     * symbol no longer exists. Type-level resolution (strip any {@code #member}); no project
     * loaded → no resolution (the pointer stays a plain FQN).
     */
    Map<String, Object> resolvePointer(String symbolFqn) {
        if (symbolFqn == null || symbolFqn.isBlank()) {
            return null;
        }
        String typeName = symbolFqn.contains("#") ? symbolFqn.substring(0, symbolFqn.indexOf('#')) : symbolFqn;
        IJdtService service = jdt == null ? null : jdt.get();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("symbol", symbolFqn);
        if (service == null) {
            p.put("resolved", false);
            p.put("note", "no project loaded");
            return p;
        }
        try {
            IType type = service.findType(typeName);
            if (type == null || !type.exists()) {
                p.put("resolved", false);
                p.put("stale", true);
                p.put("note", "symbol not found in current workspace");
                return p;
            }
            p.put("resolved", true);
            if (type.getResource() != null && type.getResource().getLocation() != null) {
                p.put("file", type.getResource().getLocation().toOSString());
            }
        } catch (Exception ex) {
            p.put("resolved", false);
            p.put("note", "resolution error: " + ex.getMessage());
        }
        return p;
    }

    private static Map<String, Object> cueMap(RecallQuery q) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (q == null) {
            return m;
        }
        if (q.hasSymbol()) {
            m.put("symbol", q.symbol());
        }
        if (q.hasPackage()) {
            m.put("package", q.packageName());
        }
        if (q.hasOperation()) {
            m.put("operation", q.operation());
        }
        if (q.hasSymptom()) {
            m.put("symptom", q.symptom());
        }
        if (q.hasExternalSystem()) {
            m.put("external_system", q.externalSystem());
        }
        return m;
    }
}
