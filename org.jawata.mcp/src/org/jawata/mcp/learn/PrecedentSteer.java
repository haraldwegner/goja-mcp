package org.jawata.mcp.learn;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Composes the WEIGHTED precedent steer (Sprint 26a, D2) from retrieved
 * {@link ToolExperience} rows — or {@code null} when they carry no clear
 * signal. Pure function of its inputs (no store, no I/O), so it is trivially
 * decoupled from the retriever: Sprint 27's embedding retriever feeds the SAME
 * compose. The weight is agreement — how many similar past cases pointed the
 * same way.
 *
 * <p>The high-value case is a NEGATIVE precedent (a tool that reverted/errored
 * in a similar case): surfaced with a justification-cost to defect (the guard's
 * mechanism, extended). A consistent POSITIVE precedent is surfaced as lighter
 * reinforcement; a single positive is not enough to push.</p>
 */
public final class PrecedentSteer {

    private PrecedentSteer() {
    }

    /** Positive precedents must agree at least this many times before reinforcing. */
    private static final int POSITIVE_THRESHOLD = 2;

    /**
     * @param currentTool the tool that just answered (context for the steer; may be null)
     * @param precedents  the retrieved rows for the current target
     * @return a steer block to append, or {@code null} when there is no clear signal
     */
    public static String compose(String currentTool, List<ToolExperience> precedents) {
        if (precedents == null || precedents.isEmpty()) {
            return null;
        }
        // Per tool: [good, bad] where good = compiled, bad = reverted|error.
        Map<String, int[]> byTool = new LinkedHashMap<>();
        for (ToolExperience p : precedents) {
            int[] c = byTool.computeIfAbsent(p.tool(), k -> new int[2]);
            if (ToolExperience.OUTCOME_COMPILED.equals(p.outcome())) {
                c[0]++;
            } else if (ToolExperience.OUTCOME_REVERTED.equals(p.outcome())
                    || ToolExperience.OUTCOME_ERROR.equals(p.outcome())) {
                c[1]++;
            }
        }
        String bestNegTool = null;
        int bestNeg = 0;
        String bestPosTool = null;
        int bestPos = 0;
        for (Map.Entry<String, int[]> e : byTool.entrySet()) {
            if (e.getValue()[1] > bestNeg) {
                bestNeg = e.getValue()[1];
                bestNegTool = e.getKey();
            }
            if (e.getValue()[0] > bestPos) {
                bestPos = e.getValue()[0];
                bestPosTool = e.getKey();
            }
        }
        if (bestNeg >= 1) {
            return "⚠ PRECEDENT — `" + bestNegTool + "` was reverted/errored "
                + bestNeg + "× in a case like this. Prefer what worked before, or if"
                + " you use it here, note why (defecting from precedent costs a one-line"
                + " justification, like a jawata-fallback).";
        }
        if (bestPos >= POSITIVE_THRESHOLD) {
            return "PRECEDENT — `" + bestPosTool + "` worked in " + bestPos
                + " similar case(s); reach for it here.";
        }
        return null;
    }
}
