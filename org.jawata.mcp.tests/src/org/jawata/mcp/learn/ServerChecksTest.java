package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 26 Stage 4 (D4 + D5 + the D3 defect rule): the server-side lane's
 * scripted seat-session fixtures — clean, gate-skipping, outcome-less, and
 * STANCE-ADOPTED (no command signal exists anywhere in these fixtures: the
 * ledger correlation is mode-agnostic BY CONSTRUCTION and these tests prove
 * it) — plus the three message shapes and the both-or-refuse defect rule.
 */
class ServerChecksTest {

    private H2ExperienceStore store;
    private LearnerEventStore events;
    private SessionLedger ledger;
    private ServerChecks checks;
    private final List<String> filedDefects = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
        ledger = new SessionLedger();
        checks = new ServerChecks(events, (s, d) -> filedDefects.add(s + "|" + d));
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    private void call(String session, String tool, boolean ok, int files) {
        ledger.record(session, new SessionLedger.CallRecord(tool, ok, files,
            System.currentTimeMillis()));
    }

    private String seatOutcome(String session, String op) throws Exception {
        return checks.onCall(session, "experience",
            mapper.readTree("{\"kind\":\"record\",\"operation\":\"" + op
                + "\",\"summary\":\"outcome\"}"),
            ToolResponse.success(Map.of()), ledger);
    }

    // ---------- D4: the four seat-session fixtures ----------

    @Test
    void clean_seat_session_passes_silently() throws Exception {
        call("s1", "compile_workspace", true, 0);
        assertNull(seatOutcome("s1", "seat:javadocs"), "gates ran — no flag");
    }

    @Test
    @DisplayName("gate-skipping (stance-adopted — no command anywhere): flagged + journaled")
    void gate_skipping_seat_session_is_flagged() throws Exception {
        call("s2", "search_symbols", true, 0);
        String warning = seatOutcome("s2", "seat:cover");
        assertNotNull(warning);
        assertTrue(warning.contains("NOT passed"));
        assertEquals(1L, events.countByKind()
            .getOrDefault(ServerChecks.KIND_SEAT_GATE_SKIP, 0L));
    }

    @Test
    void outcome_less_seat_shaped_session_is_flagged_unjournaled_at_eviction() {
        ledger.setEvictionListener(checks::onSessionEvicted);
        call("busy", "find_quality_issue", true, 0);
        call("busy", "rename_symbol", true, 2);
        // Force eviction of "busy" by flooding the LRU.
        for (int i = 0; i <= 64; i++) {
            call("filler-" + i, "health_check", true, 0);
        }
        assertEquals(1L, events.countByKind()
            .getOrDefault(ServerChecks.KIND_SEAT_UNJOURNALED, 0L),
            "seat-shaped + no outcome + evicted = unjournaled run");
    }

    // ---------- D5: the three message shapes ----------

    @Test
    void all_three_message_shapes_without_audit_evidence_are_flagged() throws Exception {
        String[] shapes = {
            "DECISION: ship or hold the release?",
            "What shipped … ⏸ awaiting \"continue\" for Stage 3",
            "SPRINT 27 CLOSED — the result summary"
        };
        int flagged = 0;
        for (int i = 0; i < shapes.length; i++) {
            String session = "shape-" + i;
            call(session, "experience", true, 0); // the record call itself
            String block = checks.onCall(session, "experience",
                mapper.readTree(mapper.createObjectNode()
                    .put("kind", "record").put("operation", "notes")
                    .put("summary", shapes[i]).toString()),
                ToolResponse.success(Map.of()), ledger);
            if (block != null && block.contains("THE DECISION TEST")) {
                flagged++;
            }
        }
        assertEquals(3, flagged, "each shape gets the decision test attached");
        assertEquals(3L, events.countByKind()
            .getOrDefault(ServerChecks.KIND_UNAUDITED_ASK, 0L));
    }

    @Test
    void audited_ask_passes_silently() throws Exception {
        // Audit evidence arrives FIRST (a communication-audit record).
        call("aud", "experience", true, 0);
        checks.onCall("aud", "experience",
            mapper.readTree("{\"kind\":\"record\",\"operation\":\"communication-audit\","
                + "\"summary\":\"audited\"}"),
            ToolResponse.success(Map.of()), ledger);
        call("aud", "experience", true, 0);
        String block = checks.onCall("aud", "experience",
            mapper.readTree(mapper.createObjectNode()
                .put("kind", "record").put("operation", "notes")
                .put("summary", "DECISION: proceed?").toString()),
            ToolResponse.success(Map.of()), ledger);
        assertNull(block, "audit evidence in the session — no flag");
    }

    @Test
    void an_ask_shaped_call_pattern_gets_the_test_preemptively() throws Exception {
        for (String t : List.of("find_quality_issue", "compile_workspace",
                "find_references", "run_tests")) {
            call("ask", t, true, 0);
        }
        String block = checks.onCall("ask", "compile_workspace",
            mapper.readTree("{}"), ToolResponse.success(Map.of()), ledger);
        assertNotNull(block);
        assertTrue(block.contains("THE DECISION TEST"));
    }

    // ---------- D3: the both-or-refuse defect rule ----------

    @Test
    void fallback_after_a_jawata_error_relaxes_AND_files_a_defect_together() throws Exception {
        call("d1", "rename_symbol", false, 0); // the jawata tool error
        checks.onCall("d1", "experience",
            mapper.readTree("{\"kind\":\"record\",\"operation\":\"jawata-fallback-slip\","
                + "\"summary\":\"declared fallback after rename failed\"}"),
            ToolResponse.success(Map.of()), ledger);
        Map<String, Long> counts = events.countByKind();
        assertEquals(1L, counts.getOrDefault(ServerChecks.KIND_RELAX_LABEL, 0L));
        assertEquals(1L, counts.getOrDefault(ServerChecks.KIND_DEFECT_FILED, 0L));
        assertEquals(1, filedDefects.size(), "the defect reached the store filer");
        assertTrue(filedDefects.get(0).contains("auto-filed"));
    }

    @Test
    void the_policy_dial_moves_both_directions_on_seeded_streams() throws Exception {
        PolicyDial dial = checks.dial();
        assertEquals(PolicyDial.Level.STEER, dial.level("fallback"), "cold start");
        // Seeded slip stream (no prior errors) → TIGHTENS to BLOCK.
        for (int i = 0; i < PolicyDial.TIGHTEN_AT; i++) {
            call("t" + i, "search_symbols", true, 0);
            checks.onCall("t" + i, "experience",
                mapper.readTree("{\"kind\":\"record\",\"operation\":\"jawata-fallback-slip\","
                    + "\"summary\":\"slip\"}"),
                ToolResponse.success(Map.of()), ledger);
        }
        assertEquals(PolicyDial.Level.BLOCK, dial.level("fallback"), "slips tighten");
        // Seeded relax stream (error-then-fallback) → LOOSENS back past STEER.
        for (int i = 0; i < PolicyDial.TIGHTEN_AT + PolicyDial.RELAX_AT + 3; i++) {
            String s = "r" + i;
            call(s, "rename_symbol", false, 0);
            checks.onCall(s, "experience",
                mapper.readTree("{\"kind\":\"record\",\"operation\":\"jawata-fallback-slip\","
                    + "\"summary\":\"after error\"}"),
                ToolResponse.success(Map.of()), ledger);
        }
        assertEquals(PolicyDial.Level.SILENT, dial.level("fallback"), "relaxes loosen");
    }

    @Test
    void a_fallback_without_a_prior_error_files_nothing() throws Exception {
        call("d2", "search_symbols", true, 0);
        checks.onCall("d2", "experience",
            mapper.readTree("{\"kind\":\"record\",\"operation\":\"jawata-fallback-slip\","
                + "\"summary\":\"plain slip\"}"),
            ToolResponse.success(Map.of()), ledger);
        assertEquals(0, filedDefects.size());
        assertEquals(0L, events.countByKind()
            .getOrDefault(ServerChecks.KIND_RELAX_LABEL, 0L));
    }
}
