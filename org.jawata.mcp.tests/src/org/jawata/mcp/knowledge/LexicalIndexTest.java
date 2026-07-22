package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Sprint 27a D9 — word matching, and the specific failures it exists to end.
 *
 * <p>Each case here is a measured behaviour of the rule it replaces, not an
 * invented scenario: the conjunctive substring rule required every cue token to
 * appear in a row's summary or symptoms, so one common word emptied the store,
 * a row's body was never searched, and {@code 77%} never met
 * {@code 77.60→34.34%}.</p>
 */
class LexicalIndexTest {

    private static StoredEntry row(String id, String summary, String details,
                                   String... symptoms) {
        return new StoredEntry(id, "lesson", null, null, null, "accepted", "medium",
            "java", null, summary, List.of(symptoms), null, null, null,
            Instant.EPOCH, details == null ? Map.of() : Map.of("details", details));
    }

    /** Ids best-first, so a test can assert an ORDER rather than a raw score. */
    private static List<String> ranked(Map<String, Double> scores) {
        List<Map.Entry<String, Double>> es = new ArrayList<>(scores.entrySet());
        es.sort(Comparator.comparingDouble(
                (Map.Entry<String, Double> e) -> e.getValue()).reversed()
            .thenComparing(Map.Entry::getKey));
        return es.stream().map(Map.Entry::getKey).toList();
    }

    // --- tokenisation ------------------------------------------------------------------

    @Test
    void punctuation_splits_so_numbers_in_prose_meet_numbers_in_notes() {
        // THE case the substring rule could not match: neither string contains
        // the other, but they share the tokens that matter.
        assertEquals(List.of("77"), LexicalIndex.tokenize("77%"));
        assertEquals(List.of("77", "60", "34", "34"),
            LexicalIndex.tokenize("77.60→34.34%"));
    }

    @Test
    void tokenising_nothing_yields_nothing_rather_than_a_blank_token() {
        assertEquals(List.of(), LexicalIndex.tokenize(null));
        assertEquals(List.of(), LexicalIndex.tokenize("   "));
    }

    // --- what gets searched ------------------------------------------------------------

    @Test
    void a_word_only_in_the_body_still_matches() {
        // The old rule searched summary and symptoms only, so a note whose
        // explanation lived in its body was unreachable by any word of that
        // explanation.
        List<StoredEntry> corpus = List.of(
            row("a", "a short title", "the compositor path fails silently on some drivers"),
            row("b", "unrelated", "nothing to do with rendering"));
        assertEquals(List.of("a"), ranked(LexicalIndex.score("compositor", corpus)));
    }

    @Test
    void a_word_only_in_a_symptom_still_matches() {
        List<StoredEntry> corpus = List.of(
            row("a", "a short title", "body text", "BROKER_POSITION_DRIFT on short position"),
            row("b", "unrelated", "nothing"));
        assertFalse(LexicalIndex.score("broker_position_drift", corpus).isEmpty());
    }

    // --- the AND failure, ended --------------------------------------------------------

    @Test
    void one_absent_word_no_longer_empties_the_result() {
        // "our test coverage LOOKED like it fell" — under the old rule the word
        // "looked" alone took 2080 rows to zero. Here the row still scores on
        // the words it does share.
        List<StoredEntry> corpus = List.of(
            row("ratchet", "a ratchet whose measurement method is not recorded is not a "
                + "ratchet: a false 77.60 to 34.34 coverage collapse", null),
            row("other", "an unrelated note about build ordering", null));
        Map<String, Double> scores = LexicalIndex.score(
            "our test coverage looked like it fell from 77% to 34% overnight", corpus);
        assertEquals("ratchet", ranked(scores).get(0),
            "the row sharing the rare words must win despite absent cue words");
    }

    // --- rarity is what does the work --------------------------------------------------

    @Test
    void a_rare_shared_word_outranks_a_common_one() {
        List<StoredEntry> corpus = new ArrayList<>();
        // "coverage" is everywhere; "overnight" is in one row only.
        for (int i = 0; i < 40; i++) {
            corpus.add(row("common-" + i, "a note about coverage numbers", null));
        }
        corpus.add(row("rare", "a note about coverage that fell overnight", null));
        assertEquals("rare", ranked(LexicalIndex.score("coverage overnight", corpus)).get(0),
            "the row sharing the RARE word must win — this is the whole mechanism");
    }

    @Test
    void sharing_only_a_universal_word_nominates_nothing() {
        // THE regression this guard exists for, reproduced from the suite: a
        // store holding ONE row, and a question sharing exactly the word "the"
        // with it. On a one-row corpus rarity cannot be estimated, so "the"
        // scored 0.2877 as though it were rare — and an espresso-machine
        // question nominated a Javadoc seat run.
        List<StoredEntry> oneRow = List.of(
            row("seat-run", "seat javadoc-writer on PurityCheck: applied",
                "document the five undocumented public methods on this type"));
        assertEquals(Map.of(),
            LexicalIndex.score("the espresso machine leaks water onto the kitchen floor",
                oneRow),
            "sharing only 'the' must nominate NOTHING");
    }

    @Test
    void the_discrimination_rule_is_a_proportion_not_a_count() {
        // Same meaning at every corpus size — the property the retired cosine
        // margin lacked, and the reason this is a ratio.
        assertFalse(LexicalIndex.discriminates(1, 1), "a word in the only row");
        assertFalse(LexicalIndex.discriminates(600, 1000), "a word in 60% of rows");
        assertTrue(LexicalIndex.discriminates(500, 1000), "a word in exactly half");
        assertTrue(LexicalIndex.discriminates(16, 2080), "'77', measured at 0.8%");
    }

    @Test
    void a_word_in_every_row_cannot_rank_anything_backwards() {
        // A cue of nothing but a universal word must not invert the ordering;
        // the non-negative IDF form is what guarantees it.
        List<StoredEntry> corpus = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            corpus.add(row("r" + i, "coverage", null));
        }
        for (double s : LexicalIndex.score("coverage", corpus).values()) {
            assertTrue(s >= 0, "a universal term produced a negative score: " + s);
        }
    }

    // --- honest absence ----------------------------------------------------------------

    @Test
    void sharing_no_words_yields_no_entry_rather_than_a_zero() {
        Map<String, Double> scores = LexicalIndex.score("kubernetes autoscaling",
            List.of(row("a", "broker confirmation ordering", null)));
        assertEquals(Map.of(), scores,
            "a row with nothing in common must be ABSENT, not present at zero — "
            + "a zero would make it a nominee at some rank");
    }

    @Test
    void an_empty_cue_or_corpus_yields_nothing() {
        assertEquals(Map.of(), LexicalIndex.score("", List.of(row("a", "x", null))));
        assertEquals(Map.of(), LexicalIndex.score("anything", List.of()));
        assertEquals(Map.of(), LexicalIndex.score("anything", null));
    }

    @Test
    void a_corpus_of_empty_rows_does_not_divide_by_zero() {
        List<StoredEntry> corpus = List.of(row("a", null, null), row("b", null, null));
        assertEquals(Map.of(), LexicalIndex.score("anything", corpus));
    }
}
