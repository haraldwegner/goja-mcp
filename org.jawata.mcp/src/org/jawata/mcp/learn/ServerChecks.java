package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * The server-side enforcement lane (Sprint 26, D4 + D5 + the D3 defect
 * rule): rides the answers every client already reads — the universal lane,
 * mode-agnostic (a deployed command and a rule-block stance look identical
 * here: both are judged by the session's own call ledger).
 *
 * <ul>
 *   <li>D4 — a seat OUTCOME record arriving in a session whose ledger lacks
 *       the seat's named gate calls → flagged (steering + journal); a
 *       session evicted with seat-shaped activity and no outcome → flagged
 *       unjournaled.</li>
 *   <li>D5 — a summary-shaped store record (decision ask / checkpoint
 *       summary / sprint result) with no audit evidence in the session →
 *       flagged + the decision test attached; an ask-shaped CALL pattern
 *       gets the decision test attached preemptively.</li>
 *   <li>D3 — a declared fallback arriving after a jawata tool error in the
 *       same session → a RELAX label AND a filed defect, together, always
 *       (silently learning around own bugs is forbidden).</li>
 * </ul>
 */
public final class ServerChecks {

    private static final Logger log = LoggerFactory.getLogger(ServerChecks.class);

    /** Event kinds this lane journals. */
    public static final String KIND_SEAT_GATE_SKIP = "seat_gate_skip";
    public static final String KIND_SEAT_UNJOURNALED = "seat_unjournaled";
    public static final String KIND_UNAUDITED_ASK = "unaudited_ask";
    public static final String KIND_RELAX_LABEL = "relax_label";
    public static final String KIND_DEFECT_FILED = "defect_filed";
    public static final String KIND_AUDIT_EVIDENCE = "audit_evidence";

    /** Gate tools a seat session must have called before its outcome. */
    private static final Set<String> SEAT_GATE_TOOLS =
        Set.of("compile_workspace", "get_diagnostics", "run_tests");

    /** The decision test, attached where a summary is forming. */
    public static final String DECISION_TEST =
        "THE DECISION TEST (enforced): a decision ask, checkpoint summary, or "
        + "sprint result must let the reader decide from the text alone — no "
        + "interpretation, no guessing, every term defined, meaning preserved, "
        + "noise (including LENGTH) stripped. Audit before sending; report "
        + "results as WHAT THEY PROVE.";

    private final LearnerEventStore events;
    /** Files a defect into the experience store: (summary, details). */
    private final BiConsumer<String, String> defectFiler;
    /** The per-situation enforcement dial this lane feeds. */
    private final PolicyDial dial;

    public ServerChecks(LearnerEventStore events, BiConsumer<String, String> defectFiler) {
        this.events = events;
        this.defectFiler = defectFiler;
        this.dial = new PolicyDial(events);
    }

    public PolicyDial dial() {
        return dial;
    }

    /**
     * Runs after every completed call. Returns a steering block to append,
     * or null. Never throws.
     */
    public String onCall(String sessionId, String name, JsonNode arguments,
            ToolResponse response, SessionLedger ledger) {
        try {
            if (!"experience".equals(name) || arguments == null) {
                return askShapedPattern(sessionId, ledger);
            }
            String kind = arguments.path("kind").asText("");
            if (!"record".equals(kind)) {
                return null;
            }
            String operation = arguments.path("operation").asText("");
            String summary = arguments.path("summary").asText("");
            if (operation.startsWith("seat:")) {
                return seatOutcomeCheck(sessionId, operation, ledger);
            }
            if ("communication-audit".equals(operation) || operation.startsWith("audit")) {
                journal(sessionId, KIND_AUDIT_EVIDENCE, name, "{}");
                return null;
            }
            if ("jawata-fallback-slip".equals(operation)) {
                return fallbackAfterError(sessionId, summary, ledger);
            }
            if (isSummaryShaped(summary)) {
                return unauditedAskCheck(sessionId, ledger);
            }
            return null;
        } catch (Exception e) {
            log.error("Server checks failed after {} — this call went unchecked", name, e);
            return null;
        }
    }

    /**
     * Session eviction hook (D4, outcome-less): seat-shaped activity —
     * detector calls plus mutations — that ends without a seat outcome is an
     * unjournaled run.
     */
    public void onSessionEvicted(String sessionId, List<SessionLedger.CallRecord> calls) {
        boolean detectorCalled = calls.stream().anyMatch(c ->
            c.tool().startsWith("find_") || "run_tests".equals(c.tool()));
        boolean mutated = calls.stream().anyMatch(c -> c.filesModified() > 0);
        boolean outcomeRecorded = calls.stream().anyMatch(c ->
            "experience".equals(c.tool()));
        if (detectorCalled && mutated && !outcomeRecorded) {
            journal(sessionId, KIND_SEAT_UNJOURNALED, "-",
                "{\"calls\":" + calls.size() + "}");
            log.warn("Session {} evicted seat-shaped with NO outcome record — unjournaled run",
                sessionId);
        }
    }

    private String seatOutcomeCheck(String sessionId, String operation, SessionLedger ledger) {
        boolean gateCalled = ledger.calls(sessionId).stream()
            .anyMatch(c -> SEAT_GATE_TOOLS.contains(c.tool()));
        if (gateCalled) {
            return null;
        }
        journal(sessionId, KIND_SEAT_GATE_SKIP, operation, "{}");
        return "SEAT DISCIPLINE: this session recorded a " + operation + " outcome "
            + "WITHOUT any gate call (compile_workspace / run_tests / "
            + "get_diagnostics). A gate you did not run has NOT passed — run the "
            + "seat's gates and re-verify before relying on this outcome.";
    }

    private String fallbackAfterError(String sessionId, String summary, SessionLedger ledger) {
        boolean errorBefore = ledger.calls(sessionId).stream().anyMatch(c -> !c.ok());
        if (!errorBefore) {
            dial.record("fallback", PolicyDial.Signal.SLIP);
            return null;
        }
        dial.record("fallback", PolicyDial.Signal.RELAX);
        // BOTH, always: the relax label AND the filed defect — never one alone.
        journal(sessionId, KIND_RELAX_LABEL, "-", "{}");
        journal(sessionId, KIND_DEFECT_FILED, "-", "{}");
        if (defectFiler != null) {
            defectFiler.accept(
                "jawata defect (auto-filed): a tool error preceded a declared fallback",
                "Session " + sessionId + ": " + summary
                    + " — the policy relaxes here AND this defect is filed; "
                    + "learning around our own bugs silently is forbidden.");
        }
        return null;
    }

    private String unauditedAskCheck(String sessionId, SessionLedger ledger) {
        boolean audited = ledger.calls(sessionId).stream()
            .anyMatch(c -> "experience".equals(c.tool()));
        // The record being stored now IS an experience call; audit evidence
        // must have arrived EARLIER (a communication-audit record).
        long experienceCalls = ledger.calls(sessionId).stream()
            .filter(c -> "experience".equals(c.tool())).count();
        if (audited && experienceCalls > 1) {
            return null;
        }
        journal(sessionId, KIND_UNAUDITED_ASK, "-", "{}");
        return DECISION_TEST;
    }

    private String askShapedPattern(String sessionId, SessionLedger ledger) {
        List<SessionLedger.CallRecord> calls = ledger.calls(sessionId);
        if (calls.size() < 4) {
            return null;
        }
        List<SessionLedger.CallRecord> tail = calls.subList(calls.size() - 4, calls.size());
        long gatherers = tail.stream().filter(c ->
            c.tool().startsWith("find_") || SEAT_GATE_TOOLS.contains(c.tool())).count();
        boolean anyMutation = tail.stream().anyMatch(c -> c.filesModified() > 0);
        if (gatherers >= 4 && !anyMutation
                && tail.stream().map(SessionLedger.CallRecord::tool).distinct().count() >= 2) {
            return DECISION_TEST;
        }
        return null;
    }

    private static boolean isSummaryShaped(String summary) {
        if (summary == null) {
            return false;
        }
        String s = summary.toUpperCase();
        return s.startsWith("DECISION:")
            || s.contains("AWAITING \"CONTINUE\"") || s.contains("⏸")
            || s.contains("CHECKPOINT") && s.contains("SHIPPED")
            || s.contains("SPRINT") && (s.contains("CLOSED") || s.contains("RESULT"));
    }

    private void journal(String sessionId, String kind, String tool, String detail) {
        if (events != null) {
            events.append(new LearnerEvent(sessionId, kind, tool, detail));
        }
    }
}
