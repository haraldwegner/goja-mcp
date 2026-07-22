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
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Sprint 27a Stage 1 — the PIN: {@link AnalogyPolicy#STANDOUT_MARGIN} must keep
 * following from the committed evidence.
 *
 * <p>Without this, the constant is only checked against itself. Here it is
 * re-derived from {@code stage0-27a-profiles.json} — the measured profiles of
 * the 12 frozen calibration cues, 4 positive controls and 9 nonsense /
 * plausible-but-absent controls over a 2,040-entry snapshot of the live
 * corpus. Change the constant, or change the data, and this fails.</p>
 *
 * <p>It also pins the NEGATIVE result, which is the load-bearing half of the
 * design: no absolute-score rule can do this job. That claim is asserted here
 * against the same data rather than only argued in prose.</p>
 */
class AnalogyPolicyDerivationTest {

    private static final String PROFILES = "/test-resources/embed-goldens/stage0-27a-profiles.json";

    /** One measured cue profile. */
    private record Row(String id, double top1, double top2, double top3, double median,
                       double p99, double mean, double sd,
                       boolean top1InAccept, int designatedRank) {
        boolean calibration() {
            return id.startsWith("cue-");
        }

        boolean positiveControl() {
            return id.startsWith("ctl-pos");
        }

        /** Nonsense or plausible-but-absent: the policy should stay silent. */
        boolean mustAbstain() {
            return id.startsWith("ctl-non") || id.startsWith("ctl-abs");
        }

        double standOut() {
            return top1 - median;
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
                    n.get("designated_rank").asInt()));
            }
        }
        return out;
    }

    /** Rebuild a profile the policy can decide on: the three leaders over a flat
     *  background at the cue's measured median (so the lower median comes out
     *  exactly at that value). */
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

    @Test
    void the_committed_margin_still_holds_the_frozen_contract() throws Exception {
        List<Row> rows = rows();
        assertEquals(26, rows.size(), "the committed profile set changed shape");

        List<String> calibrationSpoken = new ArrayList<>();
        List<String> positiveSpoken = new ArrayList<>();
        List<String> wronglySpoken = new ArrayList<>();
        for (Row r : rows) {
            if (r.id().equals("cue-para")) {
                continue;              // criterion (c)'s cue, asserted separately
            }
            boolean spoke = !AnalogyPolicy.speak(profileOf(r)).isEmpty();
            if (r.calibration() && spoke) {
                calibrationSpoken.add(r.id());
            }
            if (r.positiveControl() && spoke) {
                positiveSpoken.add(r.id());
            }
            if (r.mustAbstain() && spoke) {
                wronglySpoken.add(r.id());
            }
        }

        // A SPEAK RATE — how often the policy says something. This is NOT the
        // frozen bar; an earlier version of this test called it that, which is
        // self-marking (a rule that always speaks scores 12/12 on it). The
        // frozen contract is asserted separately below.
        assertEquals(11, calibrationSpoken.size(),
            "the SPEAK RATE moved from the recorded 11/12 — re-derive and record "
            + "the change in dossier-27a before touching the constant");

        // A positive control that goes silent is a straight regression.
        assertEquals(4, positiveSpoken.size(),
            "a positive control fell silent: " + positiveSpoken);

        // The committed nonsense control is the spec's BLOCKING check.
        assertFalse(wronglySpoken.contains("ctl-non-1"),
            "the committed nonsense control 'purple elephant quantum sandwich "
            + "protocol' must return nothing — this is D1's blocking measure");

        // TRACKED DEFECT, not an allowance. Two controls still answer, and at
        // C2 they become BLOCKING — the strict reading, taken deliberately: if
        // a control that should stay silent speaks, that is signal, not
        // something to excuse. This assertion is a countdown to zero; a THIRD
        // failure means the rule has drifted and must be re-derived.
        assertEquals(2, wronglySpoken.size(),
            "controls wrongly answered changed from the recorded 2 (ctl-non-2, "
            + "ctl-non-6): " + wronglySpoken);
    }

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
            if (!r.calibration() || r.id().equals("cue-para")) {
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
     * as it was until the C0 audit found it missing. The policy SPEAKS here;
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
        assertFalse(AnalogyPolicy.speak(profileOf(para)).isEmpty(),
            "the policy should still SPEAK here — the defect is ranking, not silence");
    }

    @Test
    void the_margin_sits_inside_a_genuinely_empty_band() throws Exception {
        List<Row> rows = rows();
        // Computed WITHOUT reference to the margin. The first version filtered
        // by STANDOUT_MARGIN and then asserted the margin lay between the
        // results — true by construction, incapable of failing. The second
        // took the WIDEST gap, which picks 0.1879…0.2150 because cue-07 sits
        // there; that is the ONE calibration cue the frozen bar permits us to
        // lose, and it is named here rather than silently skipped.
        //
        // The real band: bounded above by the lowest cue that must speak AND
        // is not the permitted loss, below by the highest control beneath it.
        final String permittedLoss = "cue-07";
        double lowestSpoken = rows.stream()
            .filter(r -> !r.mustAbstain() && !r.id().equals(permittedLoss))
            .mapToDouble(Row::standOut).min().orElseThrow();
        double highestSilent = rows.stream()
            .filter(Row::mustAbstain)
            .mapToDouble(Row::standOut).filter(v -> v < lowestSpoken)
            .max().orElseThrow();
        assertEquals(0.2945, lowestSpoken, 1e-4,
            "the lowest cue that must speak moved (was cue-02 at 0.2945)");
        assertEquals(0.2707, highestSilent, 1e-4,
            "the highest silenced control below it moved (was ctl-abs-1 at 0.2707)");
        assertTrue(AnalogyPolicy.STANDOUT_MARGIN > highestSilent
                && AnalogyPolicy.STANDOUT_MARGIN < lowestSpoken,
            "the margin must sit strictly INSIDE the widest empty band ("
                + highestSilent + " … " + lowestSpoken + "), never on an edge");

        // No cue may sit inside the band — that is what makes it empty.
        for (Row r : rows) {
            double v = r.standOut();
            assertFalse(v > highestSilent && v < lowestSpoken,
                "the band is no longer empty: " + r.id() + " at " + v);
        }
        // cue-07, the permitted loss, must still sit BELOW the band — if it
        // ever rose into or above it, the rule would no longer be losing the
        // cue we accepted losing, and the 11/12 speak rate would be a different
        // trade than the one recorded.
        double permitted = rows.stream().filter(r -> r.id().equals(permittedLoss))
            .mapToDouble(Row::standOut).findFirst().orElseThrow();
        assertTrue(permitted < highestSilent,
            "cue-07 no longer sits below the band (" + permitted + ") — the "
            + "accepted 1-of-12 loss has changed character; re-derive");
        // And it is the MIDPOINT, so the rule is not fitted to either edge.
        assertEquals((highestSilent + lowestSpoken) / 2, AnalogyPolicy.STANDOUT_MARGIN, 0.002,
            "the margin should be the band's midpoint, not a value tuned to a data point");
    }

    @Test
    void no_absolute_score_rule_can_do_this_job() throws Exception {
        List<Row> rows = rows();
        // For each candidate statistic, the BEST any threshold can do while
        // holding the frozen contract (>=11/12 calibration AND 4/4 positive).
        // Every one of these must fail to beat the shape rule's 7 of 9.
        record Candidate(String name, java.util.function.ToDoubleFunction<Row> f) {}
        List<Candidate> candidates = List.of(
            new Candidate("top1",        Row::top1),
            new Candidate("z-score",     r -> (r.top1() - r.mean()) / r.sd()),
            new Candidate("gap-to-next", r -> r.top1() - r.top2()),
            new Candidate("top1/p99",    r -> r.top1() / r.p99()),
            new Candidate("top1-p99",    r -> r.top1() - r.p99()));

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
            "the dominance finding no longer holds — an absolute rule may now be "
            + "possible, and AnalogyPolicy's justification needs revisiting");
    }

    @Test
    void the_count_rule_shows_more_than_the_old_fixed_cap_of_two() throws Exception {
        TreeSet<Integer> counts = new TreeSet<>();
        int showingThree = 0;
        for (Row r : rows()) {
            if (r.mustAbstain()) {
                // The two tracked failures are pinned at the EXACT number of
                // wrong analogies they emit today. The old "<= 3" allowance was
                // vacuous: the rule could have degraded to 3 and 3 while the
                // "countdown to zero" comment read unchanged.
                int allowed = switch (r.id()) {
                    case "ctl-non-2" -> 2;
                    case "ctl-non-6" -> 1;
                    default -> 0;
                };
                assertEquals(allowed, AnalogyPolicy.speak(profileOf(r)).size(),
                    "control " + r.id() + " changed how much it wrongly says");
                continue;
            }
            int n = AnalogyPolicy.speak(profileOf(r)).size();
            counts.add(n);
            if (n == 3) {
                showingThree++;
            }
        }
        assertTrue(counts.contains(1) && counts.contains(3),
            "the count must ADAPT — a fixed number is the flaw this replaces; saw " + counts);
        assertTrue(showingThree >= 8,
            "expected most real cues to show three now that the cap of two is gone; "
            + "saw " + showingThree);
    }
}
