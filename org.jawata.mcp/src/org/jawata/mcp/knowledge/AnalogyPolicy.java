package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Sprint 27a D1 — what the meaning path is allowed to claim.
 *
 * <p><b>Distance nominates; it never judges.</b> A cosine score says "these
 * texts are about similar things". Whether an entry <em>answers</em> the
 * question in front of the agent right now is a reading-comprehension
 * judgement, and this class does not attempt it. It hands the agent the
 * nearest few entries, labelled as nominees, and the agent — which reads them
 * anyway — keeps what fits.</p>
 *
 * <p><b>Why relevance cannot be thresholded here (measured, Stage 0, 2,040
 * live entries).</b> Absolute scores are not comparable ACROSS questions: the
 * nonsense control "the marzipan barometer disputes tuesday's velvet
 * inventory" reaches 0.4066 against this corpus, while cue-07's genuinely
 * correct answer sits at 0.2084 — the garbage question scores nearly twice the
 * real one. {@code top1}, z-score, gap-to-next, {@code top1/p99} and peakiness
 * all overlap the same way. And the relative rule tried first — stand clear of
 * the question's own median by a margin — does not survive a change of corpus:
 * fitted on 2,040 entries it silenced two real cues on a 600-entry sample
 * (C2: 7/12 against v3.4.1's 11/12, identical corpus and sample). The
 * background moves with corpus composition, so no absolute margin over it
 * carries. Both facts point the same way: the missing ingredient is
 * comprehension, not calibration.</p>
 *
 * <p><b>What geometry IS good at, and is used for here:</b> ordering within one
 * question. On the frozen calibration set the accept-set winner is rank 1 in
 * nine of twelve cues.</p>
 *
 * <p>PURE by construction: a profile in, an ordered list out. No store, no
 * embedder, no rendering — so the rule cannot quietly differ between the four
 * surfaces that consult it.</p>
 *
 * @see EmbeddingIndex#NOMINATION_FLOOR the separate, older volume cap on fact
 *      nomination — a different job, deliberately not merged with this one
 */
public final class AnalogyPolicy {

    /**
     * Below this similarity an entry is not shown at all — the one judgement
     * distance alone can defensibly make.
     *
     * <p>This is <b>not</b> a relevance threshold. It is one-sided junk
     * rejection: at this level the model is reporting essentially no shared
     * meaning, and nothing that far down has ever been an answer. The lowest
     * correct answer measured over the whole calibration set is 0.2084
     * (cue-07), so the floor sits at less than half of it — a fence in an empty
     * field rather than a line between two overlapping populations, which is
     * precisely why it transfers where the fitted margin did not.</p>
     *
     * <p><b>Stated honestly: on the measured set this floor never fires.</b>
     * The weakest top score of any question, real or nonsense, is 0.1818
     * (ctl-non-5, a sourdough recipe) — above the floor. Nonsense questions
     * score in the 0.18–0.41 range because they are built from real words. So
     * the floor is a guard for the genuinely off-corpus case, and the honesty
     * of an answer to a question the store cannot answer is carried by
     * LABELLING (nominee, not vouched answer) and by the agent's own judgement
     * — never by this constant pretending to have filtered.</p>
     *
     * <p>Chosen at the recall-biased end of the defensible band (0.10–0.15,
     * where 0.15 is Sprint 27's shipped nomination floor) under Harald's
     * asymmetry ruling, 2026-07-22: <em>failing to use an experience we
     * already hold is the expensive error; a discarded nominee costs the agent
     * a glance.</em> Where two values are equally good at removing junk, take
     * the one further from the innocent.</p>
     */
    public static final double JUNK_FLOOR = 0.10;

    /**
     * How many nominees reach the agent's context.
     *
     * <p>Measured on the frozen calibration set, two ways. By the ACCEPT-SET
     * reading (the frozen lists name every entry that legitimately answers a
     * cue) three nominees already serve ten of twelve, and so do four, six and
     * eleven — that reading is flat from 3 to 22. By the DESIGNATED reading
     * (the one canonical entry must arrive) three serve seven, six serve nine,
     * and eleven serve ten. So eleven delivers the best-matched entry on three
     * more cues than three does.</p>
     *
     * <p><b>The cost of those three cues, measured rather than assumed:</b> a
     * nominee carries the entry's SUMMARY — never its body — and summaries in
     * the live corpus run to a median of 8 words. Eleven nominees instead of
     * three is typically ~64 extra words, ~216 at the 90th percentile. An
     * earlier version of this constant was set to 3 and justified by "context
     * cost", priced as eight extra <em>paragraphs</em>; that was wrong about
     * what the carrier contains, and Harald caught it.</p>
     *
     * <p>At 64 words the asymmetry ruling is not close: failing to use an
     * experience we already hold costs re-reading the code or repeating the
     * recorded mistake; a discarded nominee costs a glance. Hence eleven.</p>
     *
     * <p>Two cues remain unserved at any affordable K — one whose answer ranks
     * 23rd (symptom-shaped, D3's to lift) and one naming a Java method, which
     * the exact index path answers directly without needing meaning at all.</p>
     */
    public static final int MAX_NOMINEES = 11;

    private AnalogyPolicy() {
    }

    /**
     * The nearest entries worth putting in front of the agent for one cue.
     *
     * <p>These are <b>nominees, not answers</b>: callers must render them
     * through the analogy carrier, which frames every one of them as
     * {@code "analogy — judge whether it transfers"} and states its basis in
     * words — never as something the store vouches for. What the store does
     * vouch for comes from the exact index path, which returns one answer or
     * none. (Naming the actual string matters: an earlier version of this
     * javadoc mandated wording that appeared in no caller and no test, so the
     * contract could not be checked.)</p>
     *
     * <p>An empty result means the store holds nothing even in the
     * neighbourhood of this cue — not that a lookup failed.</p>
     *
     * @param profile every scored id for one cue, UNFLOORED — the full scan.
     *                Pre-truncating is harmless to the decision but wastes the
     *                ordering the caller already paid for.
     * @return 0..{@link #MAX_NOMINEES} ids, nearest first; never {@code null}
     */
    public static List<String> nominate(Map<String, Double> profile) {
        if (profile == null || profile.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(profile.entrySet());
        // Score descending, then id ascending: a tie must not resolve by hash
        // order, or the same question answers differently between runs.
        ranked.sort(Comparator.comparingDouble(
                (Map.Entry<String, Double> e) -> e.getValue()).reversed()
            .thenComparing(Map.Entry::getKey));

        List<String> nominees = new ArrayList<>(MAX_NOMINEES);
        for (Map.Entry<String, Double> e : ranked) {
            if (nominees.size() >= MAX_NOMINEES || e.getValue() < JUNK_FLOOR) {
                break;                       // ranked, so the first miss ends it
            }
            nominees.add(e.getKey());
        }
        return List.copyOf(nominees);
    }
}
