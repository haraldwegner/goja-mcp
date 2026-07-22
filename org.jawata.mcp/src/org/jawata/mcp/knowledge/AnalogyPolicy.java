package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Sprint 27a D1 — the single authority on whether an analogy surface speaks,
 * and how many answers it shows.
 *
 * <p><b>Judge the peak, not the score.</b> A cue that the store can actually
 * answer produces one entry standing clear of that cue's OWN typical score; a
 * cue it cannot answer produces a flat profile where nothing does. The
 * background is a free by-product of the brute-force scan that already runs,
 * and before this class it was computed and thrown away.</p>
 *
 * <p><b>Why an absolute bar cannot do this job.</b> Stage 0 measured every
 * candidate over 2,040 live entries: {@code top1}, z-score, gap-to-next,
 * {@code top1/p99} and peakiness ALL overlap between cues that must speak and
 * cues that must stay silent. Four nonsense controls beat the weakest true cue
 * on {@code top1}, z-score AND gap simultaneously, so no monotone rule over
 * those statistics can admit the one and reject the others. Standing clear of
 * the cue's own background is the only measured separator — hence this class,
 * and hence {@link EmbeddingIndex#NOMINATION_FLOOR} keeping its original,
 * different job (a volume cap for fact nomination) and losing this one.</p>
 *
 * <p>PURE by construction: a profile in, an ordered decision out. No store, no
 * embedder, no rendering — so it is exhaustively testable, and so the rule
 * cannot quietly differ between the four surfaces that consult it.</p>
 */
public final class AnalogyPolicy {

    /**
     * How far the best answer must stand above the cue's own background before
     * anything is said.
     *
     * <p>DERIVED, not chosen: Sprint 27a Stage 0, against a 2,040-entry snapshot
     * of the live corpus. It is <em>margin-centred in an empty band</em> —
     * the highest control that must stay silent sits at 0.2707, the lowest cue
     * that must speak at 0.2945, and this value is the midpoint of that gap
     * rather than either edge, so the rule is not fitted to a data point.</p>
     *
     * <p>At this value 11 of the 12 calibration cues SPEAK — which is a speak
     * rate, NOT the frozen bar. The frozen contract (winner in accept_set AND
     * designated ≤ K=12) reads 9/12 on embeddings alone, 10/12 with the symbol
     * path, unchanged from Sprint 27; this policy cannot move it, because it
     * decides whether to speak and not who wins. Also: all 4 positive controls
     * speak, and 7 of 9 nonsense /
     * plausible-but-absent controls stay silent — including the committed
     * "purple elephant quantum sandwich protocol". Evidence:
     * {@code test-resources/embed-goldens/stage0-27a-profiles.json}, re-derived
     * on every build by {@code AnalogyPolicyDerivationTest}; reasoning in
     * {@code dossier-27a.md}.</p>
     */
    public static final double STANDOUT_MARGIN = 0.283;

    /**
     * At most this many analogies reach the agent's context.
     *
     * <p>It is a ceiling, NOT a quota — the count comes from how many entries
     * actually stand out, which is the flaw this replaces: a fixed cap of two
     * hid a correct third answer and padded a thin one to two.</p>
     */
    public static final int MAX_SPOKEN = 3;

    private AnalogyPolicy() {
    }

    /**
     * The ordered set of entry ids worth speaking for this cue — empty when
     * nothing stands out.
     *
     * <p><b>Silence is an answer here, not a failure.</b> An empty result means
     * "nothing in the store stands out for this cue", which is different in kind
     * from a lookup that failed, and callers must render it as such.</p>
     *
     * @param profile every scored id for one cue, UNFLOORED — the full scan, not
     *                a pre-truncated top-K. Truncating before this point would
     *                destroy the background the decision is made against.
     * @return 0..{@link #MAX_SPOKEN} ids, best first; never {@code null}
     */
    public static List<String> speak(Map<String, Double> profile) {
        if (profile == null || profile.isEmpty()) {
            return List.of();
        }
        double background = background(profile);
        List<Map.Entry<String, Double>> ranked = new ArrayList<>(profile.entrySet());
        // Score descending, then id ascending: a tie must not resolve by hash
        // order, or the same question answers differently between runs.
        ranked.sort(Comparator.comparingDouble(
                (Map.Entry<String, Double> e) -> e.getValue()).reversed()
            .thenComparing(Map.Entry::getKey));

        List<String> spoken = new ArrayList<>(MAX_SPOKEN);
        for (Map.Entry<String, Double> e : ranked) {
            if (spoken.size() >= MAX_SPOKEN || e.getValue() - background < STANDOUT_MARGIN) {
                break;                       // ranked, so the first miss ends it
            }
            spoken.add(e.getKey());
        }
        return List.copyOf(spoken);
    }

    /**
     * The cue's own typical score — the lower median of the profile.
     *
     * <p>The median rather than the mean because a handful of strong hits must
     * not drag the background up and silence the very cue they answer. The
     * <em>lower</em> median specifically, because that is what the Stage-0
     * derivation measured; a different definition would silently invalidate
     * {@link #STANDOUT_MARGIN}.</p>
     */
    static double background(Map<String, Double> profile) {
        double[] scores = new double[profile.size()];
        int i = 0;
        for (double s : profile.values()) {
            scores[i++] = s;
        }
        java.util.Arrays.sort(scores);
        return scores[(int) Math.floor(0.5 * (scores.length - 1))];
    }
}
