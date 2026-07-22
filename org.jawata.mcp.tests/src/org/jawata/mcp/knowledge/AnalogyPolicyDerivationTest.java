package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Sprint 27a Stage 1 — the PIN: {@link AnalogyPolicy}'s two constants must keep
 * following from the committed evidence.
 *
 * <p>Without this they are only checked against themselves. Here they are
 * re-derived from {@code stage0-27a-profiles.json} — the measured profiles of
 * the 12 frozen calibration cues, 4 positive controls and 9 nonsense /
 * plausible-but-absent controls over a 2,040-entry snapshot of the live
 * corpus. Change a constant, or change the data, and this fails.</p>
 *
 * <p>It also pins the NEGATIVE result, which is the load-bearing half of the
 * design: no rule over the score profile can decide relevance. That claim is
 * asserted here against the data rather than only argued in prose — it is the
 * reason the meaning path nominates instead of judging.</p>
 */
class AnalogyPolicyDerivationTest {

    private static final String PROFILES = "/test-resources/embed-goldens/stage0-27a-profiles.json";

    /** One measured cue profile. */
    private record Row(String id, double top1, double top2, double top3, double median,
                       double p99, double mean, double sd,
                       boolean top1InAccept, int designatedRank, double designatedScore) {
        boolean calibration() {
            return id.startsWith("cue-") && !id.equals("cue-para");
        }

        /** Nonsense or plausible-but-absent: nothing here legitimately answers. */
        boolean mustAbstain() {
            return id.startsWith("ctl-non") || id.startsWith("ctl-abs");
        }

        /**
         * READING A (the accept-set reading — Harald's frozen option A): K
         * nominees carry a LEGITIMATE answer, because the winner is itself in
         * the frozen accept set, or the designated entry is inside the first K.
         */
        boolean servedAt(int k) {
            return top1InAccept || (designatedRank > 0 && designatedRank <= k);
        }

        /**
         * READING B (the designated reading): K nominees carry THE CANONICAL
         * entry. Stricter — a cue whose rank-1 winner is an accepted answer but
         * whose designated entry sits deeper counts as unserved here.
         */
        boolean designatedWithin(int k) {
            return designatedRank > 0 && designatedRank <= k;
        }
    }

    private static List<Row> rows() throws Exception {
        List<Row> out = new ArrayList<>();
        try (InputStream in = AnalogyPolicyDerivationTest.class.getResourceAsStream(PROFILES)) {
            assertNotNull(in, "committed profiles missing: " + PROFILES);
            com.fasterxml.jackson.databind.JsonNode root =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(in);
            for (com.fasterxml.jackson.databind.JsonNode n : root.get("profiles")) {
                out.add(new Row(n.get("cue_id").asText(),
                    n.get("top1").asDouble(), n.get("top2").asDouble(),
                    n.get("top3").asDouble(), n.get("median").asDouble(),
                    n.get("p99").asDouble(), n.get("mean").asDouble(),
                    n.get("sd").asDouble(),
                    n.get("top1_in_accept").asBoolean(),
                    n.get("designated_rank").asInt(),
                    n.get("designated_score").asDouble()));
            }
        }
        return out;
    }

    /** The three measured leaders over a flat background at the cue's median. */
    private static Map<String, Double> profileOf(Row r) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("t1", r.top1());
        m.put("t2", r.top2());
        m.put("t3", r.top3());
        for (int i = 0; i < 100; i++) {
            m.put("bg-" + i, r.median());
        }
        return m;
    }

    // --- the floor -------------------------------------------------------------------

    /**
     * The floor is a fence in an empty field: it must sit well below anything
     * that has ever been an answer, so that corpus composition cannot push an
     * answer under it. This is the property the retired margin rule lacked —
     * it sat BETWEEN two overlapping populations, and moved when the corpus did.
     */
    @Test
    void the_junk_floor_sits_far_below_every_answer_ever_measured() throws Exception {
        List<Row> rows = rows();
        assertEquals(26, rows.size(), "the committed profile set changed shape");

        double weakestAnswer = rows.stream().filter(r -> r.designatedRank() > 0)
            .mapToDouble(Row::designatedScore).min().orElseThrow();
        assertEquals(0.2084, weakestAnswer, 1e-4,
            "the weakest correct answer moved from cue-07's 0.2084");
        assertTrue(AnalogyPolicy.JUNK_FLOOR < weakestAnswer / 2,
            "the floor (" + AnalogyPolicy.JUNK_FLOOR + ") must keep at least a "
            + "factor-of-two margin below the weakest answer ever measured ("
            + weakestAnswer + "); closer than that and it is a fitted boundary "
            + "again, not a junk guard");
    }

    /**
     * Said out loud so it cannot be mistaken for a working filter: on the
     * measured set the floor never fires. Nonsense questions score 0.18–0.41
     * because they are built from real words, so NOTHING is removed by it here.
     *
     * <p>Honesty about a question the store cannot answer is therefore carried
     * entirely by LABELLING and by the reading agent — never by this constant.
     * If this test ever starts failing because a control fell below the floor,
     * that is a real change in what the store holds, and the dossier's claim
     * about how honesty is achieved has to be revisited.</p>
     */
    @Test
    void the_floor_removes_nothing_on_the_measured_set_and_that_is_recorded()
            throws Exception {
        // Asserted on the DATA, not through nominate(): the synthetic profile a
        // replay builds has a flat background at the cue's median, so with a
        // cap of 11 the count would say more about the fixture's shape than
        // about the floor. What "the floor removes nothing" means is that every
        // measured leader clears it.
        List<String> anythingDropped = new ArrayList<>();
        for (Row r : rows()) {
            if (r.top3() < AnalogyPolicy.JUNK_FLOOR) {
                anythingDropped.add(r.id() + "@" + r.top3());
            }
        }
        assertEquals(List.of(), anythingDropped,
            "the floor now removes something on the measured set — re-read "
            + "AnalogyPolicy.JUNK_FLOOR's contract and update dossier-27a: "
            + anythingDropped);

        double lowestTopScore = rows().stream().mapToDouble(Row::top1).min().orElseThrow();
        assertEquals(0.1818, lowestTopScore, 1e-4,
            "the weakest top score of any cue moved from ctl-non-5's 0.1818");
        assertTrue(lowestTopScore > AnalogyPolicy.JUNK_FLOOR,
            "even the weakest cue clears the floor — this is why the floor is a "
            + "guard for the off-corpus case and not a nonsense filter");
    }

    // --- the count ---------------------------------------------------------------------

    /**
     * K under BOTH readings of "served", because they disagree and the
     * disagreement is the whole argument.
     *
     * <p><b>Reading A, the accept-set reading</b> (Harald's frozen option A —
     * the accept sets exist precisely to say that several entries legitimately
     * answer a cue): K=3 serves ten of twelve, and so do 4, 6, 11 and 12. The
     * next cue only arrives at rank 23. On this reading a larger K buys
     * literally nothing.</p>
     *
     * <p><b>Reading B, the designated reading</b> (the single canonical entry
     * must arrive): K=3 serves seven, K=4 eight, K=6 nine, K=11 ten. On this
     * reading a larger K buys three more cues — and under the asymmetry ruling
     * ("failing to use an experience we already hold is the expensive error")
     * that argues for K=11.</p>
     *
     * <p><b>The shipped K=11 rests on reading B</b>, under Harald's ruling of
     * 2026-07-22 once the cost of a nominee was measured honestly: a nominee
     * carries a summary (median 8 words), not a body, so eleven instead of
     * three costs ~64 words and buys the canonical entry on three more cues.
     * An earlier K=3 rested on reading A plus a cost that did not exist.</p>
     *
     * <p>Both readings are pinned here so the choice between them stays
     * visible. The C2 audit caught the first version of this test computing
     * only reading A while the dossier claimed both — and computing only A also
     * made the "a larger K would buy recall" branch unfirable, since A is flat
     * from 3 to 22.</p>
     */
    @Test
    void k_is_pinned_under_both_readings_of_served() throws Exception {
        List<Row> cal = rows().stream().filter(Row::calibration).toList();
        assertEquals(12, cal.size(), "the calibration set is no longer 12 cues");

        // The shipped value itself — an earlier pin asserted a figure that held
        // for any K in 3..22, so it did not pin K at all.
        assertEquals(11, AnalogyPolicy.MAX_NOMINEES,
            "MAX_NOMINEES moved; both readings below must be re-argued, and the "
            + "choice between them is Harald's");

        // READING A — flat from 3 upward, which is why it cannot argue for more.
        assertEquals(10, (int) cal.stream().filter(r -> r.servedAt(3)).count(),
            "reading A at K=3 moved from the recorded 10 (missing cue-09, "
            + "symptom-shaped and D3's, and cue-11, an FQN the exact path answers)");
        for (int k : new int[] {4, 6, 11, 12}) {
            assertEquals(10, (int) cal.stream().filter(r -> r.servedAt(k)).count(),
                "reading A at K=" + k + " moved from the recorded 10");
        }
        assertEquals(9, (int) cal.stream().filter(r -> r.servedAt(1)).count(),
            "a single nominee served a different number than the recorded 9 — "
            + "the case for showing more than one has changed");

        // READING B — rises with K, and that is the open question, recorded
        // rather than hidden. If these numbers move, the K decision reopens.
        assertEquals(7, (int) cal.stream().filter(r -> r.designatedWithin(3)).count(),
            "reading B at K=3 moved from the recorded 7");
        assertEquals(8, (int) cal.stream().filter(r -> r.designatedWithin(4)).count(),
            "reading B at K=4 moved from the recorded 8");
        assertEquals(9, (int) cal.stream().filter(r -> r.designatedWithin(6)).count(),
            "reading B at K=6 moved from the recorded 9");
        assertEquals(10, (int) cal.stream().filter(r -> r.designatedWithin(11)).count(),
            "reading B at K=11 moved from the recorded 10");

        // The three cues where the readings disagree, named — these are exactly
        // what a larger K would additionally deliver.
        List<String> disagree = cal.stream()
            .filter(r -> r.servedAt(3) && !r.designatedWithin(3))
            .map(Row::id).toList();
        assertEquals(List.of("cue-02", "cue-04", "cue-08"), disagree,
            "the cues whose canonical entry K=3 does not reach changed; the K "
            + "decision was recorded against exactly these three");
    }

    // --- the frozen contract, and what the policy cannot move --------------------------

    /**
     * THE FROZEN CONTRACT, which no test asserted until the plan audit found it
     * missing: {@code accept-sets.json} defines a pass as the WINNER being in
     * the cue's accept_set AND the designated entry inside the top-12. It is a
     * RANKING measure — upstream of this policy, which is why the policy cannot
     * move it, and why a speak rate must never be reported in its place.
     *
     * <p><b>What this can and cannot do.</b> It freezes the RECORDED
     * measurement, so a hand-edit of the evidence trips it. It does NOT
     * re-measure the system: a real ranking regression in production code would
     * not fail this test. The behavioural gate lives at C2 (no-regression) and
     * C4 (the ≥11/12 bar), through the wired path.</p>
     */
    @Test
    void the_frozen_contract_count_is_frozen_at_the_recorded_measurement() throws Exception {
        List<String> pass = new ArrayList<>();
        List<String> fail = new ArrayList<>();
        for (Row r : rows()) {
            if (!r.calibration()) {
                continue;
            }
            (r.top1InAccept() && r.designatedRank() <= 12 ? pass : fail).add(r.id());
        }
        // 9/12 on embeddings alone; 10/12 counting cue-11, which accept-sets.json
        // documents as an FQN the symbol path answers exactly and embeddings do
        // not. Identical to Sprint 27's recorded baseline — nothing regressed.
        assertEquals(9, pass.size(),
            "the FROZEN-CONTRACT count moved from 9/12 embeddings-only. This is "
            + "the measure R1's guard is written against; a change here is a "
            + "signed-risk matter, not an editorial one. Failing: " + fail);
        assertEquals(List.of("cue-01", "cue-09", "cue-11"), fail,
            "the failing cues changed; cue-01 and cue-09 are the two standing "
            + "symptom-shaped failures D3 exists to lift, cue-11 is the FQN case");
    }

    /**
     * Stage-0 criterion (c) — the spec's third D1 measure: "the
     * percentage-paraphrase case ... returns the ratchet lesson". It does NOT,
     * and this pins the failure rather than leaving the criterion unaddressed
     * as it was until the C0 audit found it missing. The nominees ARE delivered;
     * what fails is WHO WINS, which is ranking and therefore D3's to fix.
     */
    @Test
    void criterion_c_the_paraphrase_case_currently_returns_the_wrong_answer()
            throws Exception {
        Row para = rows().stream().filter(r -> r.id().equals("cue-para"))
            .findFirst().orElseThrow();
        assertFalse(para.top1InAccept(),
            "criterion (c) now PASSES — the paraphrase case returns the ratchet "
            + "lesson. Update the dossier and turn this into the positive gate.");
        assertEquals(28, para.designatedRank(),
            "the ratchet lesson's rank for the paraphrase cue moved from 28");
        assertFalse(AnalogyPolicy.nominate(profileOf(para)).isEmpty(),
            "nominees must still be delivered here — the defect is ranking, and "
            + "silence would hide it rather than fix it");
    }

    // --- the negative result the whole design rests on ---------------------------------

    @Test
    void no_rule_over_the_score_profile_can_decide_relevance() throws Exception {
        List<Row> rows = rows();
        // For each candidate statistic: the weakest cue that must be answered
        // versus the strongest control that must not. If the first is not below
        // the second the populations have separated and a threshold WOULD work.
        record Candidate(String name, java.util.function.ToDoubleFunction<Row> f) {}
        List<Candidate> candidates = List.of(
            new Candidate("top1",        Row::top1),
            new Candidate("z-score",     r -> (r.top1() - r.mean()) / r.sd()),
            new Candidate("gap-to-next", r -> r.top1() - r.top2()),
            new Candidate("top1/p99",    r -> r.top1() / r.p99()),
            new Candidate("top1-p99",    r -> r.top1() - r.p99()),
            new Candidate("top1-median", r -> r.top1() - r.median()));

        for (Candidate c : candidates) {
            double realMin = rows.stream().filter(r -> !r.mustAbstain())
                .mapToDouble(c.f()).min().orElseThrow();
            double abstainMax = rows.stream().filter(Row::mustAbstain)
                .mapToDouble(c.f()).max().orElseThrow();
            assertTrue(realMin <= abstainMax,
                c.name() + " now separates cleanly (real min " + realMin
                + " > abstain max " + abstainMax + ") — if the corpus really has "
                + "changed this much, the whole derivation must be re-run");
        }

        // The dominance result: at least one control beats the weakest true cue
        // on top1, z-score AND gap simultaneously, so no monotone rule over
        // them can admit the one and reject the other.
        Row weakest = rows.stream().filter(r -> !r.mustAbstain())
            .min((a, b) -> Double.compare(a.top1(), b.top1())).orElseThrow();
        boolean dominated = rows.stream().filter(Row::mustAbstain).anyMatch(r ->
            r.top1() >= weakest.top1()
            && (r.top1() - r.mean()) / r.sd() >= (weakest.top1() - weakest.mean()) / weakest.sd()
            && r.top1() - r.top2() >= weakest.top1() - weakest.top2());
        assertTrue(dominated,
            "the dominance finding no longer holds — a threshold rule may now be "
            + "possible, and AnalogyPolicy's justification needs revisiting");
    }
}
