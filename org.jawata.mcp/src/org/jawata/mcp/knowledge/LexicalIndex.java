package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sprint 27a D9 — matching on WORDS, scored by how rare they are.
 *
 * <p><b>Why this exists.</b> The store's older word-matching required every
 * token of a cue to appear as a substring of a row's summary or one of its
 * symptoms, joined by AND, un-stopworded and unscored, with the row's body
 * never searched. On the cue "our test coverage looked like it fell from 77% to
 * 34% overnight" the token {@code looked} alone takes 2,080 rows to zero. That
 * path answers 2 of the 12 frozen calibration cues.</p>
 *
 * <p><b>What rarity buys.</b> The entry that answers that cue shares four rare
 * words with it — {@code fell} occurs in 6 of 2,080 rows, {@code 77} in 16,
 * {@code 34} in 33, {@code overnight} in 25. Weighted by rarity it ranks
 * <b>1st of 2,080</b>; the meaning path ranks it 28th, because averaging a
 * 300-word note into one 384-number vector dissolves exactly that rarity.</p>
 *
 * <p><b>And why this does NOT replace meaning-matching.</b> Measured alone
 * against the frozen contract it scores 3 of 12, against the meaning path's 9.
 * The two fail on DIFFERENT cues, which is the whole reason both run and their
 * rankings are merged — see {@link AnalogyPolicy#nominate(Map, Map)}. A word
 * matcher cannot see that "bet against a stock" is a short position; a vector
 * cannot see that two texts share the same four rare tokens.</p>
 *
 * <p>No stop-word list, deliberately: inverse document frequency already drives
 * words like "the" and "our" to near zero, and a hand-maintained list is one
 * more thing to be wrong about this corpus.</p>
 *
 * <p>PURE: rows in, scores out. No store, no connection, no embedder.</p>
 */
public final class LexicalIndex {

    /**
     * BM25 term-frequency saturation. The published default from the method's
     * own literature, NOT fitted to this corpus — this sprint has already paid
     * once for a constant that held only on the data it was chosen against.
     */
    public static final double K1 = 1.5;

    /** BM25 length normalisation; the published default, for the same reason. */
    public static final double B = 0.75;

    private static final Pattern WORD = Pattern.compile("[a-z0-9]+");

    /**
     * Does sharing this word tell you anything about WHICH row to pick?
     *
     * <p>A word in more than half the rows does not: it is a property of the
     * corpus, not of the match. Inverse document frequency already discounts
     * such words toward zero on a large corpus — but "toward zero" is not zero,
     * and on a SMALL one it fails outright, because rarity cannot be estimated
     * from a handful of documents. Against a store holding a single row, the
     * word "the" has df = 1 of n = 1 and scores 0.2877 as though it were rare.
     * That is not hypothetical: it is how the question "the espresso machine
     * leaks water onto the kitchen floor" came to nominate a Javadoc seat run,
     * matching on "the" and nothing else.</p>
     *
     * <p>A proportion, deliberately, so the rule means the same thing whatever
     * the corpus size — the property the fitted margin retired at C2 lacked.</p>
     */
    static boolean discriminates(int docFreq, int corpusSize) {
        return docFreq * 2 <= corpusSize;
    }

    private LexicalIndex() {
    }

    /**
     * Lower-case runs of letters and digits.
     *
     * <p>Splitting on punctuation is what lets the cue's {@code 77%} meet the
     * note's {@code 77.60→34.34%}: as raw strings neither contains the other,
     * and the older substring rule therefore missed a row that names the very
     * numbers the question asks about.</p>
     */
    public static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        var m = WORD.matcher(text.toLowerCase(java.util.Locale.ROOT));
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    /**
     * The words a row is matched on: its summary, its symptoms, and its body
     * detail — everything a human wrote about it.
     *
     * <p>The body is included because the older rule searched only the summary,
     * so a note whose explanation lived in its detail was unmatchable by any
     * word that explanation used.</p>
     */
    public static String textOf(StoredEntry e) {
        if (e == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (e.summary() != null) {
            sb.append(e.summary());
        }
        if (e.symptoms() != null) {
            for (String s : e.symptoms()) {
                sb.append(' ').append(s);
            }
        }
        Object details = e.body() == null ? null : e.body().get("details");
        if (details != null) {
            sb.append(' ').append(details);
        }
        return sb.toString();
    }

    /**
     * BM25 score per row id for one cue — rows scoring zero are omitted rather
     * than listed, so an empty map means "no shared words", which is a real
     * answer and not a failed lookup.
     *
     * @param cue    the question, in the words the caller asked it
     * @param corpus every row that may answer; statistics are computed over
     *               exactly this set, so a caller that pre-filters changes what
     *               "rare" means and should not
     */
    public static Map<String, Double> score(String cue, List<StoredEntry> corpus) {
        List<String> query = tokenize(cue);
        if (query.isEmpty() || corpus == null || corpus.isEmpty()) {
            return Map.of();
        }
        List<String> ids = new ArrayList<>(corpus.size());
        List<Map<String, Integer>> termFreqs = new ArrayList<>(corpus.size());
        int[] lengths = new int[corpus.size()];
        Map<String, Integer> docFreq = new HashMap<>();
        long total = 0;
        for (int i = 0; i < corpus.size(); i++) {
            StoredEntry e = corpus.get(i);
            List<String> words = tokenize(textOf(e));
            Map<String, Integer> tf = new HashMap<>();
            for (String w : words) {
                tf.merge(w, 1, Integer::sum);
            }
            for (String w : tf.keySet()) {
                docFreq.merge(w, 1, Integer::sum);
            }
            ids.add(e.id());
            termFreqs.add(tf);
            lengths[i] = words.size();
            total += words.size();
        }
        int n = corpus.size();
        double avgLength = total == 0 ? 1.0 : (double) total / n;

        Map<String, Double> scores = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            Map<String, Integer> tf = termFreqs.get(i);
            double score = 0.0;
            for (String w : query) {
                Integer f = tf.get(w);
                if (f == null) {
                    continue;
                }
                int df = docFreq.getOrDefault(w, 0);
                if (!discriminates(df, n)) {
                    continue;
                }
                // Lucene's non-negative IDF form: a word in EVERY row scores
                // near zero rather than negative, so a cue of only common words
                // ranks nothing rather than ranking it backwards.
                double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
                double norm = f + K1 * (1 - B + B * lengths[i] / avgLength);
                score += idf * (f * (K1 + 1)) / norm;
            }
            if (score > 0) {
                scores.put(ids.get(i), score);
            }
        }
        return scores;
    }
}
