package org.goja.mcp.knowledge;

import org.goja.core.IJdtService;
import org.goja.mcp.domain.Advisor;
import org.goja.mcp.domain.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 21 Stage 3 — the store-backed {@link Advisor} that FILLS the Sprint-18 seam
 * (replacing {@code NoOpAdvisor} at the orchestration wiring). It bridges the domain
 * learning seam to the knowledge store (DIP: implements {@code domain.Advisor}, wraps the
 * {@link ExperienceStore}).
 *
 * <ul>
 *   <li><b>adviseBefore</b> → the Stage-2 terminal retrieval: a cue built from the
 *       refactoring {@code kind} (operation) + {@code target} (symbol) yields ≤1 fitting
 *       node's advice, or empty on absence. Advisory only — never a hard gate.</li>
 *   <li><b>record</b> → the write-admission filter: admit only what JDT cannot re-derive —
 *       negatives / hazards / failure modes (a {@code ROLLED_BACK} or {@code FLAGGED}
 *       outcome). A successful {@code APPLIED} outcome is the derivable normal path and is
 *       dropped; duplicates (same scope + summary) are dropped. Every admitted write is a
 *       {@code candidate} pending promotion.</li>
 * </ul>
 */
public final class ExperienceAdvisor implements Advisor {

    private static final Logger log = LoggerFactory.getLogger(ExperienceAdvisor.class);

    /** Terminal advice: at most one fitting node (the most specific). */
    private static final int MAX_ADVICE = 1;

    private final ExperienceStore store;
    private final ExperienceRetrieval retrieval;

    public ExperienceAdvisor(ExperienceStore store, Supplier<IJdtService> jdt) {
        this.store = store;
        this.retrieval = new ExperienceRetrieval(store, jdt);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> adviseBefore(String kind, String target) {
        RecallQuery q = new RecallQuery(
            looksLikeFqn(target) ? target : null,   // symbol cue
            null,                                    // package
            blankToNull(kind),                       // operation = the refactoring kind
            null,                                    // symptom
            null);                                   // external_system
        if (q.isEmpty()) {
            return List.of();
        }
        Map<String, Object> r = retrieval.recall(q);
        if (!ExperienceRetrieval.RESULT_MATCH.equals(r.get("result"))) {
            return List.of();                        // absence → nothing to advise
        }
        List<Map<String, Object>> entries = (List<Map<String, Object>>) r.get("entries");
        List<String> advice = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            if (advice.size() >= MAX_ADVICE) {
                break;
            }
            advice.add(renderAdvice(e));
        }
        return advice;
    }

    @Override
    public void record(Outcome outcome) {
        if (outcome == null) {
            return;
        }
        // Write admission — only what JDT cannot re-derive (negatives / hazards / failures).
        String type;
        if (Outcome.ROLLED_BACK.equals(outcome.status())) {
            type = "failure_mode";
        } else if (Outcome.FLAGGED.equals(outcome.status())) {
            type = "hazard";
        } else {
            log.debug("record: APPLIED is JDT-derivable — not admitted ({})", outcome.operation());
            return;                                  // successful apply = the derivable normal path
        }

        String target = outcome.target();
        String op = outcome.operation() == null ? "operation" : outcome.operation();
        String summary = op + " " + outcome.status().toLowerCase(Locale.ROOT)
            + (target != null && !target.isBlank() ? " on " + target : "");
        if (isDuplicate(target, summary)) {
            log.debug("record: duplicate candidate skipped ({})", summary);
            return;
        }

        SymbolFact.Builder fb = SymbolFact.of(type, summary, Confidence.LOW);
        if (looksLikeFqn(target)) {
            fb.symbol(target);
        }
        List<String> notes = outcome.notes();
        if (notes != null && !notes.isEmpty()) {
            fb.details(String.join("; ", notes));
        }

        ExperienceEntry.Builder eb = ExperienceEntry.of(fb.build())
            .operation(outcome.operation())
            .scopeKind(looksLikeFqn(target) ? "symbol" : null);
        if (outcome.undoChangeId() != null && !outcome.undoChangeId().isBlank()) {
            eb.addLink("undo", outcome.undoChangeId());
        }
        store.put(eb.build());
        log.info("recorded {} candidate: {}", type, summary);
    }

    /** Dedup by scope + summary — a symbol-scoped candidate already saying the same thing. */
    private boolean isDuplicate(String target, String summary) {
        if (!looksLikeFqn(target)) {
            return false;
        }
        String norm = H2ExperienceStore.normalize(summary);
        for (StoredEntry e : store.query(new RecallQuery(target, null, null, null, null))) {
            if (H2ExperienceStore.normalize(e.summary()).equals(norm)) {
                return true;
            }
        }
        return false;
    }

    private static String renderAdvice(Map<String, Object> e) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(e.get("type")).append("] ").append(e.get("summary"));
        Object status = e.get("status");
        if (status != null) {
            sb.append(" (").append(status).append(')');
        }
        Object details = e.get("details");
        if (details != null) {
            sb.append(" — ").append(details);
        }
        if (e.get("resolved_pointer") instanceof Map<?, ?> p && Boolean.TRUE.equals(p.get("stale"))) {
            sb.append(" [pointer stale]");
        }
        return sb.toString();
    }

    /** A dotted identifier with no path/space separators — a Java FQN (possibly {@code #member}). */
    private static boolean looksLikeFqn(String s) {
        return s != null && s.contains(".") && !s.contains("/") && !s.contains(" ") && !s.endsWith(".java");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
