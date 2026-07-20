package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.coverage.MechanicalChangeJournal;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The event tap (Sprint 26, D7's wiring): sits at the ToolRegistry choke point
 * — the one place every tool outcome converges — and turns outcomes into
 * {@link LearnerEvent}s AS A SIDE EFFECT of normal work. No manual step
 * anywhere: an agent that compiles, reverts, or breaks something has already
 * fed the learners. Also feeds the {@link SessionLedger} the server-side
 * checks read.
 */
public class EventTap {

    /** The gate tools whose outcomes are labels (compile/tests/diagnostics). */
    private static final Set<String> GATE_TOOLS =
        Set.of("compile_workspace", "get_diagnostics", "run_tests");

    private final SessionLedger ledger;
    private final LearnerEventStore events;

    /** Sprint 26 C7: the pending-edit correlator — null until the application wires it. */
    private LearnerService learnerService;

    /** Sprint 26a D2: the experience-loop capture — null until the application
     *  wires it (and independent of the learner service, which D4 retires). */
    private ToolExperienceRecorder toolExperience;

    public EventTap(SessionLedger ledger, LearnerEventStore events) {
        this.ledger = ledger;
        this.events = events;
    }

    /** Sprint 26 C7: install the learner service the edit feed resolves through. */
    public void setLearnerService(LearnerService service) {
        this.learnerService = service;
    }

    /** Sprint 26a D2: install the experience-loop recorder (selective capture). */
    public void setToolExperienceRecorder(ToolExperienceRecorder recorder) {
        this.toolExperience = recorder;
    }

    public SessionLedger ledger() {
        return ledger;
    }

    /** Called after every completed tool call (success or structured error). */
    public void onCall(String sessionId, String name, JsonNode arguments, ToolResponse response) {
        int filesModified = filesModified(response);
        ledger.record(sessionId, new SessionLedger.CallRecord(
            name, response.isSuccess(), filesModified, System.currentTimeMillis()));
        // Sprint 26a D2: the experience loop's selective capture — independent of
        // the learner-event store below and of the learner models (retired in D4).
        if (toolExperience != null) {
            toolExperience.onCall(sessionId, name, arguments, response);
        }
        if (events == null) {
            return;
        }
        if (!response.isSuccess()) {
            events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_TOOL_ERROR, name,
                "{\"error\":true}"));
            return;
        }
        // The edit feed's FIRST half (C7): an observed .java edit arrives via
        // experience(kind=observe_edit) — session-aware here at the tap, so
        // the tool itself stays session-blind. Held pending until its
        // consequence.
        if ("experience".equals(name) && arguments != null
                && "observe_edit".equals(arguments.path("kind").asText(""))
                && learnerService != null) {
            String outcome = arguments.path("outcome").asText("");
            if (!outcome.isBlank()) {
                // The hook path: the observer correlated the consequence in its
                // own (client) session and delivered the edit already labeled —
                // the tool trained it; here it is journaled as both halves.
                events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_EDIT_OBSERVED, name,
                    "{\"labeled\":true}"));
                events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_EDIT_RESOLVED, name,
                    "{\"resolved\":1,\"clean\":" + "clean".equals(outcome) + "}"));
            } else {
                Boolean rule = arguments.has("ruleStructural")
                    ? arguments.path("ruleStructural").asBoolean() : null;
                learnerService.pendingEdit(sessionId,
                    arguments.path("before").asText(""),
                    arguments.path("after").asText(""), rule);
                events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_EDIT_OBSERVED, name,
                    "{\"pending\":" + learnerService.pendingCount(sessionId) + "}"));
            }
        }
        if ("refactoring".equals(name) && arguments != null
                && arguments.path("action").asText("").startsWith("undo")) {
            events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_UNDO, name,
                "{\"action\":\"" + arguments.path("action").asText() + "\"}"));
            // The edit feed's SECOND half: an undo is the strongest
            // structural-mishandled consequence for everything pending.
            resolvePending(sessionId, false, name);
        }
        if (filesModified > 0 && MechanicalChangeJournal.EXEMPT_TOOLS.contains(name)) {
            events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_MECHANICAL_TOUCH, name,
                "{\"filesModified\":" + filesModified + "}"));
        }
        if (GATE_TOOLS.contains(name)) {
            long errorCount = errorCount(response);
            events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_GATE_CALL, name,
                "{\"errors\":" + errorCount + "}"));
            // The compile-after-touch label: a failing gate while mechanically
            // touched files are pending marks the touch suspect — the
            // immediate revert-class signal the learners train on.
            if (errorCount > 0 && MechanicalChangeJournal.hasEntries()) {
                events.append(new LearnerEvent(sessionId,
                    LearnerEvent.KIND_COMPILE_AFTER_TOUCH_FAIL, name,
                    "{\"errors\":" + errorCount + "}"));
            }
            // The edit feed's SECOND half: the gate outcome IS the label for
            // every edit pending in this session.
            resolvePending(sessionId, errorCount == 0, name);
        }
    }

    /** Resolves pending edits with their consequence label and journals the count. */
    private void resolvePending(String sessionId, boolean cleanOutcome, String tool) {
        if (learnerService == null) {
            return;
        }
        int resolved = learnerService.resolvePending(sessionId, cleanOutcome);
        if (resolved > 0) {
            events.append(new LearnerEvent(sessionId, LearnerEvent.KIND_EDIT_RESOLVED, tool,
                "{\"resolved\":" + resolved + ",\"clean\":" + cleanOutcome + "}"));
        }
    }

    private static int filesModified(ToolResponse response) {
        if (response.getData() instanceof Map<?, ?> map
                && map.get("filesModified") instanceof List<?> files) {
            return files.size();
        }
        return 0;
    }

    private static long errorCount(ToolResponse response) {
        if (response.getData() instanceof Map<?, ?> map) {
            Object v = map.get("errorCount");
            if (v instanceof Number n) {
                return n.longValue();
            }
            Object failed = map.get("failed");
            if (failed instanceof Number n) {
                return n.longValue();
            }
        }
        return 0;
    }
}
