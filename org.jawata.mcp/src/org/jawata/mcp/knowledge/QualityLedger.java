package org.jawata.mcp.knowledge;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 27 D6 — what the recall system is actually doing, counted as a side
 * effect of use.
 *
 * <p>Sprint 33 has to answer "is this good enough?", and the only way that
 * question gets an honest answer is if the evidence accumulates <em>without
 * anyone deciding to collect it</em>. So every counter here advances from
 * normal operation: no manual step, no separate mode, nothing to remember.</p>
 *
 * <p>Four families, each answering a question the others cannot:</p>
 * <ul>
 *   <li><b>fired.&lt;surface&gt;</b> — how often each surface actually reaches
 *       the agent. A surface that never fires is not "working quietly"; it is
 *       not working, and only a count distinguishes the two.</li>
 *   <li><b>followed / defected</b> — a warned (tool, target) that is not
 *       re-attempted CONFORMED; a `precedentOverride` paid DEFECTED. Defection
 *       is not a failure — it is the agent judging this case different, which
 *       the design explicitly allows. Counting both is the only way to see
 *       whether steering is informing or merely obstructing.</li>
 *   <li><b>outcome_after.&lt;outcome&gt;</b> — what happened on a steered
 *       target afterwards.</li>
 *   <li><b>gate.&lt;criterion&gt;.{checked,passed,rejected}</b> — the fit gate
 *       per criterion, so a criterion that rejects everything (or admits
 *       everything) is visible as a number rather than as a vague sense that
 *       recall feels off.</li>
 * </ul>
 *
 * <p><b>What these numbers are not.</b> They are correlation. "Followed, then
 * compiled" does not establish that following caused the compile, and no
 * reading of this table can supply the counterfactual. The stats surface says
 * so in words every time it renders, because a bare table of counts invites
 * exactly the causal reading it cannot support — and at the start the counts
 * are additionally too thin to mean anything at all.</p>
 *
 * <p>Writes never throw. A counter that cannot be incremented must not fail the
 * operation it was measuring — measurement that can break the thing being
 * measured is worse than no measurement. A write failure is logged once per
 * counter and the work proceeds.</p>
 */
public final class QualityLedger {

    private static final Logger log = LoggerFactory.getLogger(QualityLedger.class);

    // The surfaces a recall can reach the agent through.
    /** An ordinary recall: a question asked, an answer returned. */
    public static final String SURFACE_QUESTION_HOOK = "question_hook";
    /** The choke warning that a tool went wrong on THIS target before. */
    public static final String SURFACE_CHOKE_PRECEDENT = "choke_precedent";
    /** The choke's advisory tier: a similar case on a DIFFERENT target. */
    public static final String SURFACE_CHOKE_ADVISORY = "choke_advisory";
    /** The session-start domain primer. */
    public static final String SURFACE_PRIMER = "primer";
    /** A driven seat run's recall — the caller must NAME this one
     *  ({@code surface: "seat"}); it is indistinguishable on the wire. */
    public static final String SURFACE_SEAT = "seat";

    /** The honesty label the stats surface renders with every quality block. */
    public static final String CORRELATION_LABEL =
        "These are counts of what happened, not evidence of what caused it: a steer"
        + " followed and then a clean compile is a CORRELATION, and nothing here"
        + " supplies the counterfactual. Early numbers are additionally too thin to"
        + " read at all; they grow with use.";

    private final H2ExperienceStore store;
    /** Failures are reported once per counter, not once per call. */
    private final java.util.Set<String> reportedFailures =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * @param store the H2 store whose v7 schema carries {@code quality_counter}
     */
    public QualityLedger(H2ExperienceStore store) {
        this.store = store;
    }

    // ---- the four families -------------------------------------------------

    /** A recall reached the agent through {@code surface}. */
    public void fired(String surface) {
        bump("fired." + safe(surface));
    }

    /**
     * A recall through {@code surface} found nothing to say and STAYED SILENT.
     *
     * <p>The symmetric counterpart of {@link #fired}, and not new machinery —
     * the same per-surface bump, a second key. Sprint 27a wires it; Sprint 33
     * reads it. Without it a surface's silence is invisible, and "how often does
     * this surface actually reach the agent" — {@code fired / (fired + silent)}
     * — cannot be computed at all: a surface that never speaks and a surface
     * that is never consulted look identical, which is the honesty gap the whole
     * ledger exists to close.</p>
     */
    public void silent(String surface) {
        bump("silent." + safe(surface));
    }

    /**
     * A negative precedent was SURFACED for a (tool, target) — the steer was
     * shown and the justification-cost is now owed if it is used anyway.
     *
     * <p>There is deliberately no {@code followed()} event, because conforming
     * is an ABSENCE: the agent simply does not re-attempt. Inventing an event
     * for it would mean guessing when the absence became final. Instead this
     * counts the warnings, {@link #defected()} counts the ones paid for, and
     * the difference is reported for what it is — warned and not (yet)
     * re-attempted.</p>
     */
    public void warned() {
        bump("warned");
    }

    /** A warned (tool, target) was used anyway, with the justification paid. */
    public void defected() {
        bump("defected");
    }

    /** What the tool_experience outcome was on a steered target afterwards. */
    public void outcomeAfter(String outcome) {
        bump("outcome_after." + safe(outcome));
    }

    /**
     * One fit-gate criterion was consulted, and what it said.
     *
     * @param criterion symbol / package / symptom / external_system / operation
     * @param passed    whether this criterion admitted the candidate
     */
    public void gate(String criterion, boolean passed) {
        String c = "gate." + safe(criterion);
        bump(c + ".checked");
        bump(passed ? c + ".passed" : c + ".rejected");
    }

    // ---- reading -----------------------------------------------------------

    /**
     * Every counter, name → count. An empty map means nothing has been
     * measured yet — which is a real state (a fresh store), and is reported as
     * itself rather than as zeros for counters that were never touched.
     */
    public Map<String, Long> counters() {
        Map<String, Long> out = new LinkedHashMap<>();
        try (PreparedStatement ps = store.sharedConnection().prepareStatement(
                "SELECT name, count FROM quality_counter ORDER BY name");
                ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            // An empty map on failure would be a LIE — it reads as "nothing
            // measured". Say what happened instead, in the data.
            log.error("quality counters could not be READ", e);
            out.put("(unavailable: " + e.getMessage() + ")", -1L);
        }
        return out;
    }

    /**
     * The {@code quality} block for {@code experience(kind=stats)} — the
     * counters, grouped, and ALWAYS the correlation label. The label is not
     * optional and not conditional: a caller must not be able to obtain the
     * numbers without the sentence that says how to read them.
     */
    public Map<String, Object> statsBlock() {
        Map<String, Long> all = counters();
        Map<String, Object> block = new LinkedHashMap<>();
        Map<String, Object> fired = new LinkedHashMap<>();
        Map<String, Object> gate = new LinkedHashMap<>();
        Map<String, Object> outcome = new LinkedHashMap<>();
        Map<String, Object> steer = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : all.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("fired.")) {
                fired.put(k.substring("fired.".length()), e.getValue());
            } else if (k.startsWith("gate.")) {
                gate.put(k.substring("gate.".length()), e.getValue());
            } else if (k.startsWith("outcome_after.")) {
                outcome.put(k.substring("outcome_after.".length()), e.getValue());
            } else {
                steer.put(k, e.getValue());
            }
        }
        // Conformity is the difference, and it is LABELED as the difference —
        // never presented as an observed "followed" event, which nothing here
        // observes. A negative value would mean the counters disagree; report
        // that rather than clamp it to something tidy.
        long warned = all.getOrDefault("warned", 0L);
        long defected = all.getOrDefault("defected", 0L);
        if (warned > 0 || defected > 0) {
            steer.put("warned_and_not_re_attempted", warned - defected);
            steer.put("warned_and_not_re_attempted_note",
                "DERIVED as warned minus defected, not observed: conforming is the"
                + " absence of a second attempt, and a still-open warning is counted"
                + " here until it is paid for.");
        }
        block.put("recalls_fired", fired);
        block.put("steering", steer);
        block.put("outcome_after", outcome);
        block.put("fit_gate", gate);
        block.put("total_counters", all.size());
        block.put("how_to_read", CORRELATION_LABEL);
        return block;
    }

    // ---- the write ---------------------------------------------------------

    private void bump(String name) {
        try {
            // MERGE is H2's upsert: first sighting inserts 1, later ones add.
            try (PreparedStatement ps = store.sharedConnection().prepareStatement(
                    "MERGE INTO quality_counter (name, count) KEY(name)"
                    + " VALUES (?, COALESCE((SELECT count FROM quality_counter"
                    + " WHERE name = ?), 0) + 1)")) {
                ps.setString(1, name);
                ps.setString(2, name);
                ps.executeUpdate();
            }
        } catch (SQLException | RuntimeException e) {
            // Measurement must never break what it measures.
            if (reportedFailures.add(name)) {
                log.warn("quality counter '{}' could not be advanced ({}); the operation"
                    + " itself is unaffected and this is reported once", name, e.getMessage());
            }
        }
    }

    private static String safe(String s) {
        if (s == null || s.isBlank()) {
            return "unknown";
        }
        return s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]+", "_");
    }
}
