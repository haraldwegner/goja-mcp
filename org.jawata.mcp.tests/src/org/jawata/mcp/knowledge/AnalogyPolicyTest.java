package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Sprint 27a Stage 1 — the analogy policy, tested against the Stage-0 MEASURED
 * profiles (dossier-27a "Stage 0 evidence"; raw numbers in
 * {@code test-resources/embed-goldens/stage0-27a-profiles.json}).
 *
 * <p>The design ruling these tests pin (Harald, 2026-07-22): the meaning path
 * NOMINATES and does not judge. So most of what an earlier version of this file
 * asserted — abstention when nothing "stands out" — is deliberately inverted
 * here, and the inversion is itself pinned: a rule that decides relevance from
 * the shape of the score profile was measured and did not work
 * ({@link AnalogyPolicy} carries the numbers).</p>
 *
 * <p>The policy is PURE — it decides from the profile alone, so these tests
 * need no store, no embedder and no rendering.</p>
 */
class AnalogyPolicyTest {

    /** A profile with the given leaders over 200 background entries at 0.0. */
    private static Map<String, Double> profile(double... leaders) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < leaders.length; i++) {
            m.put("lead-" + i, leaders[i]);
        }
        for (int i = 0; i < 200; i++) {
            m.put("bg-" + i, 0.0);
        }
        return m;
    }

    // --- measured profiles, replayed -------------------------------------------------

    @Test
    void a_strong_cue_nominates_its_three_nearest() {
        // cue-06 as measured: 0.3890 / 0.3197 / 0.3110
        assertEquals(List.of("lead-0", "lead-1", "lead-2"),
            AnalogyPolicy.nominate(profile(0.3890, 0.3197, 0.3110)));
    }

    @Test
    void a_weak_but_correct_cue_is_still_delivered() {
        // cue-07 as measured: 0.2084 / 0.2036 / 0.2015. Its rank-1 entry is the
        // CORRECT answer. An earlier margin rule silenced this cue; under the
        // asymmetry ruling — missing what we already hold is the expensive
        // error — it must be delivered and left to the agent to judge.
        assertEquals(List.of("lead-0", "lead-1", "lead-2"),
            AnalogyPolicy.nominate(profile(0.2084, 0.2036, 0.2015)));
    }

    @Test
    void a_nonsense_cue_is_also_delivered_because_geometry_cannot_tell() {
        // ctl-non-1 "purple elephant quantum sandwich protocol": top1 0.3478 —
        // HIGHER than cue-07's correct answer above. That overlap is why the
        // store does not judge relevance; the nominees are labelled and the
        // reading agent discards them.
        assertEquals(3, AnalogyPolicy.nominate(profile(0.3478, 0.31, 0.29)).size());
    }

    // --- the floor: one-sided junk rejection -----------------------------------------

    @Test
    void entries_below_the_junk_floor_are_dropped() {
        // Two above, one below: only the two survive.
        assertEquals(List.of("lead-0", "lead-1"),
            AnalogyPolicy.nominate(profile(0.42, 0.11, 0.09)));
    }

    @Test
    void a_cue_with_nothing_above_the_floor_yields_nothing() {
        // The genuinely off-corpus case the floor exists for: the store holds
        // nothing even in the neighbourhood.
        assertEquals(List.of(), AnalogyPolicy.nominate(profile(0.08, 0.04, 0.01)));
    }

    @Test
    void the_floor_is_absolute_and_does_not_move_with_the_corpus() {
        // THE property the previous rule lacked, and the direct cause of the C2
        // regression: a margin measured against the cue's own background moved
        // when the corpus changed, silencing real cues on a smaller sample.
        // Same leader, wildly different backgrounds, same verdict.
        Map<String, Double> lowBg = new LinkedHashMap<>();
        Map<String, Double> highBg = new LinkedHashMap<>();
        lowBg.put("top", 0.40);
        highBg.put("top", 0.40);
        for (int i = 0; i < 100; i++) {
            lowBg.put("b" + i, 0.05);
            highBg.put("b" + i, 0.30);
        }
        // Low background: only the leader clears the floor, so one nominee.
        assertEquals(List.of("top"), AnalogyPolicy.nominate(lowBg));
        // High background: the leader plus as many background entries as the cap
        // allows — the SAME leader either way. A rule that read the background
        // would have silenced the second case; this one cannot.
        List<String> high = AnalogyPolicy.nominate(highBg);
        assertEquals("top", high.get(0));
        assertEquals(AnalogyPolicy.MAX_NOMINEES, high.size());
    }

    // --- the two-stream merge (D9) ----------------------------------------------------

    @Test
    void a_row_both_streams_rank_beats_a_row_only_one_does() {
        Map<String, Double> semantic = new LinkedHashMap<>();
        semantic.put("both", 0.30);
        semantic.put("semantic-only", 0.40);        // HIGHER cosine...
        Map<String, Double> lexical = new LinkedHashMap<>();
        lexical.put("both", 12.0);
        lexical.put("lexical-only", 30.0);          // ...and higher BM25 elsewhere
        // ...yet the row BOTH streams nominate wins, because agreement across
        // two independent kinds of evidence outweighs one stream's leader.
        assertEquals("both", AnalogyPolicy.nominate(semantic, lexical).get(0));
    }

    @Test
    void a_row_only_the_word_stream_finds_is_still_nominated() {
        // THE case D9 exists for: the coverage-collapse note ranks 28th by
        // meaning and 1st by words. If one stream could veto the other, the
        // merge would buy nothing.
        Map<String, Double> lexicalOnly = Map.of("ratchet", 28.0);
        assertEquals(List.of("ratchet"), AnalogyPolicy.nominate(Map.of(), lexicalOnly));
    }

    @Test
    void the_junk_floor_applies_to_meaning_only_never_to_word_scores() {
        // A BM25 weight of 0.05 is not "almost no shared meaning" — it is a
        // number from a different measurement. Comparing it to a cosine floor
        // would silently drop word matches.
        assertEquals(List.of("worded"),
            AnalogyPolicy.nominate(Map.of(), Map.of("worded", 0.05)));
        // ...while the same value as a COSINE is junk and stays excluded.
        assertEquals(List.of(), AnalogyPolicy.nominate(Map.of("meaningless", 0.05), Map.of()));
    }

    @Test
    void two_empty_streams_yield_nothing() {
        assertEquals(List.of(), AnalogyPolicy.nominate(Map.of(), Map.of()));
        assertEquals(List.of(), AnalogyPolicy.nominate(null, null));
    }

    @Test
    void the_merge_is_deterministic_when_streams_tie() {
        Map<String, Double> semantic = new LinkedHashMap<>();
        semantic.put("zebra", 0.5);
        semantic.put("alpha", 0.5);
        Map<String, Double> lexical = new LinkedHashMap<>();
        lexical.put("zebra", 9.0);
        lexical.put("alpha", 9.0);
        List<String> first = AnalogyPolicy.nominate(semantic, lexical);
        assertEquals(List.of("alpha", "zebra"), first);
        for (int i = 0; i < 20; i++) {
            assertEquals(first, AnalogyPolicy.nominate(semantic, lexical));
        }
    }

    // --- structural guarantees --------------------------------------------------------

    @Test
    void never_nominates_more_than_the_cap_however_many_qualify() {
        double[] fifteen = new double[15];
        for (int i = 0; i < fifteen.length; i++) {
            fifteen[i] = 0.9 - i * 0.01;
        }
        assertEquals(AnalogyPolicy.MAX_NOMINEES,
            AnalogyPolicy.nominate(profile(fifteen)).size());
    }

    @Test
    void an_empty_profile_yields_nothing_rather_than_failing() {
        assertEquals(List.of(), AnalogyPolicy.nominate(Map.of()));
        assertEquals(List.of(), AnalogyPolicy.nominate(null));
    }

    @Test
    void ties_are_broken_deterministically_by_id() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("zebra", 0.5);
        m.put("alpha", 0.5);
        for (int i = 0; i < 50; i++) {
            m.put("bg" + i, 0.0);
        }
        assertEquals(List.of("alpha", "zebra"), AnalogyPolicy.nominate(m));
        // and repeatedly — no iteration-order dependence
        for (int i = 0; i < 20; i++) {
            assertEquals(List.of("alpha", "zebra"), AnalogyPolicy.nominate(m));
        }
    }

    @Test
    void the_constants_are_the_ruled_ones() {
        assertEquals(0.10, AnalogyPolicy.JUNK_FLOOR, 1e-9);
        assertEquals(11, AnalogyPolicy.MAX_NOMINEES);
    }

    /**
     * PURITY, read off the class file rather than the source.
     *
     * <p>The first version of this test asserted that every declared field was
     * static and every constructor took no arguments. Neither excludes a store:
     * a {@code private static ExperienceStore} passes both, and a static call
     * inside a method body is invisible to either. The C1 audit caught it.</p>
     *
     * <p>A class file's constant pool names every type the class actually
     * references, including ones used only inside method bodies — so scanning
     * it is the check the gate asks for. Same idiom as
     * {@code EmbedPackagePurityTest}, which guards the embed package.</p>
     */
    @Test
    void the_policy_is_pure_no_store_or_rendering_dependencies() {
        byte[] bytes = classBytes("org.jawata.mcp.knowledge.AnalogyPolicy");
        assertNotNull(bytes, "could not read AnalogyPolicy.class");
        List<String> foreign = jawataReferencesOtherThanItself(bytes);
        assertEquals(List.of(), foreign,
            "AnalogyPolicy must reference NO other jawata type — it decides from "
            + "a score profile alone. Found: " + foreign);
    }

    /** The purity check must be able to FAIL, or it proves nothing. */
    @Test
    void the_purity_check_can_actually_fail() {
        byte[] bytes = classBytes("org.jawata.mcp.knowledge.ExperienceRetrieval");
        assertNotNull(bytes, "could not read ExperienceRetrieval.class");
        assertFalse(jawataReferencesOtherThanItself(bytes).isEmpty(),
            "the scanner found nothing in a class that certainly references "
            + "other jawata types — the check is broken, not the code");
    }

    private static byte[] classBytes(String fqn) {
        String path = "/" + fqn.replace('.', '/') + ".class";
        try (java.io.InputStream in = AnalogyPolicyTest.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Every {@code org/jawata/...} name in the constant pool bar the class's own. */
    private static List<String> jawataReferencesOtherThanItself(byte[] bytes) {
        String blob = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        List<String> found = new java.util.ArrayList<>();
        String needle = "org/jawata/";
        for (int at = blob.indexOf(needle); at >= 0; at = blob.indexOf(needle, at + 1)) {
            int end = at;
            while (end < blob.length() && (Character.isLetterOrDigit(blob.charAt(end))
                    || "/_$".indexOf(blob.charAt(end)) >= 0)) {
                end++;
            }
            String ref = blob.substring(at, end);
            if (!ref.startsWith("org/jawata/mcp/knowledge/AnalogyPolicy")
                    && !found.contains(ref)) {
                found.add(ref);
            }
        }
        return found;
    }
}
