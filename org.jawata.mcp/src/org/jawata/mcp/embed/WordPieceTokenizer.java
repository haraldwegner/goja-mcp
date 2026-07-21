package org.jawata.mcp.embed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sprint 27 D1 — the owned WordPiece tokenizer: an exact re-implementation of
 * the BERT-uncased pipeline the bundled MiniLM checkpoint was trained with
 * ({@code BertNormalizer} → {@code BertPreTokenizer} → {@code WordPiece}).
 *
 * <p>Exactness is not a stylistic goal here, it is the contract: a token
 * sequence that differs from the reference by one piece produces a different
 * vector, and the whole sprint's parity gate (cosine ≥ 0.999 against reference
 * vectors) rests on this class. The gate is the committed golden tokenizations
 * in {@code test-resources/embed-goldens/} — generated from the reference
 * implementation, never hand-written.</p>
 *
 * <p>The checkpoint's own configuration drives every switch, and the
 * combination is deliberately spelled out because it is easy to get subtly
 * wrong: {@code clean_text=true}, {@code handle_chinese_chars=true},
 * {@code lowercase=true}, and {@code strip_accents=null} — which in the
 * reference means "follow lowercase", i.e. accents ARE stripped. Two golden
 * cases pin the consequences: "München" normalizes to "munchen" (the umlaut is
 * decomposed and its mark dropped) while "ß" survives unchanged (it has no
 * canonical decomposition), and CJK ideographs are split one-per-token while
 * katakana is not (it falls outside the ideograph ranges and word-pieces
 * normally).</p>
 *
 * <p>PURE: this class depends on nothing in jawata and performs no I/O beyond
 * reading a vocabulary stream handed to it. That purity is asserted by a test —
 * it is what lets the embedder be reasoned about (and swapped) in isolation.</p>
 */
public final class WordPieceTokenizer {

    /** Reference {@code max_input_chars_per_word}: a longer word is one [UNK]. */
    static final int MAX_INPUT_CHARS_PER_WORD = 100;
    /** Reference {@code continuing_subword_prefix}. */
    static final String CONTINUING_PREFIX = "##";

    private static final String UNK = "[UNK]";
    private static final String CLS = "[CLS]";
    private static final String SEP = "[SEP]";

    /** Where the bundled vocabulary lives inside the shipped jar. */
    private static final String BUNDLED_VOCAB = "/embed/vocab.txt";

    private final Map<String, Integer> vocab;
    private final int unkId;
    private final int clsId;
    private final int sepId;

    /**
     * @param vocab token → id, which must contain {@code [UNK]}, {@code [CLS]}
     *              and {@code [SEP]}
     * @throws IllegalArgumentException when one of those three is missing —
     *         a tokenizer without them cannot encode anything the model reads
     */
    public WordPieceTokenizer(Map<String, Integer> vocab) {
        this.vocab = Map.copyOf(vocab);
        this.unkId = requireToken(UNK);
        this.clsId = requireToken(CLS);
        this.sepId = requireToken(SEP);
    }

    private int requireToken(String token) {
        Integer id = vocab.get(token);
        if (id == null) {
            throw new IllegalArgumentException(
                "vocabulary is missing the required special token " + token);
        }
        return id;
    }

    /**
     * Load the vocabulary that ships inside this bundle.
     *
     * @throws IllegalStateException if the resource is absent — a missing
     *     vocabulary is a broken build, and failing loudly here beats
     *     degrading to a tokenizer that silently emits [UNK] for everything
     *     (an embedder that answers wrongly is worse than one that refuses).
     */
    public static WordPieceTokenizer bundled() {
        try (InputStream in = WordPieceTokenizer.class.getResourceAsStream(BUNDLED_VOCAB)) {
            if (in == null) {
                throw new IllegalStateException(
                    "bundled vocabulary not found on the classpath at " + BUNDLED_VOCAB);
            }
            return fromVocabStream(in);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + BUNDLED_VOCAB, e);
        }
    }

    /** One token per line, line number = id (the reference {@code vocab.txt} format). */
    public static WordPieceTokenizer fromVocabStream(InputStream in) {
        Map<String, Integer> v = new HashMap<>(32768);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int id = 0;
            while ((line = r.readLine()) != null) {
                // Only a trailing newline is stripped; a token is never trimmed
                // (some reference vocab entries are meaningful whitespace).
                v.putIfAbsent(line, id);
                id++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("reading vocabulary", e);
        }
        return new WordPieceTokenizer(v);
    }

    /** How many tokens this tokenizer knows. */
    public int vocabSize() {
        return vocab.size();
    }

    /**
     * Encode to input ids WITH the special tokens, truncated to
     * {@code maxLength} — the shape the encoder consumes.
     *
     * <p>Truncation is applied to the content, so the closing [SEP] survives:
     * the result is always {@code [CLS] … [SEP]} and never longer than
     * {@code maxLength}.</p>
     */
    public int[] encode(String text, int maxLength) {
        if (maxLength < 2) {
            throw new IllegalArgumentException(
                "maxLength must leave room for [CLS] and [SEP], got " + maxLength);
        }
        List<String> pieces = tokenize(text);
        int room = Math.min(pieces.size(), maxLength - 2);
        int[] ids = new int[room + 2];
        ids[0] = clsId;
        for (int i = 0; i < room; i++) {
            ids[i + 1] = vocab.getOrDefault(pieces.get(i), unkId);
        }
        ids[room + 1] = sepId;
        return ids;
    }

    /** The word pieces WITHOUT special tokens — the unit the goldens pin. */
    public List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        for (String word : preTokenize(normalize(text))) {
            wordPiece(word, out);
        }
        return out;
    }

    // --- BertNormalizer -------------------------------------------------------

    /**
     * clean → handle-CJK → strip-accents → lowercase, in the reference's order.
     * The order matters: stripping accents after lowercasing would still work
     * for Latin, but the reference does it before, and "exact" means exact.
     */
    static String normalize(String text) {
        String cleaned = cleanText(text);
        String cjk = padCjk(cleaned);
        String stripped = stripAccents(cjk);
        return stripped.toLowerCase(Locale.ROOT);
    }

    /** Drop NUL / replacement / control characters; fold all whitespace to ' '. */
    private static String cleanText(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (cp == 0 || cp == 0xFFFD || isControl(cp)) {
                return;
            }
            sb.appendCodePoint(isWhitespace(cp) ? ' ' : cp);
        });
        return sb.toString();
    }

    /** Surround CJK ideographs with spaces so each becomes its own token. */
    private static String padCjk(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> {
            if (isCjk(cp)) {
                sb.append(' ').appendCodePoint(cp).append(' ');
            } else {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    /** NFD-decompose and drop the combining marks (Unicode category Mn). */
    private static String stripAccents(String text) {
        String nfd = Normalizer.normalize(text, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(nfd.length());
        nfd.codePoints().forEach(cp -> {
            if (Character.getType(cp) != Character.NON_SPACING_MARK) {
                sb.appendCodePoint(cp);
            }
        });
        return sb.toString();
    }

    // --- BertPreTokenizer -----------------------------------------------------

    /** Split on whitespace, then break each punctuation character out alone. */
    static List<String> preTokenize(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isWhitespace(cp)) {
                flush(cur, out);
            } else if (isPunctuation(cp)) {
                flush(cur, out);
                out.add(new String(Character.toChars(cp)));
            } else {
                cur.appendCodePoint(cp);
            }
        }
        flush(cur, out);
        return out;
    }

    private static void flush(StringBuilder cur, List<String> out) {
        if (cur.length() > 0) {
            out.add(cur.toString());
            cur.setLength(0);
        }
    }

    // --- WordPiece ------------------------------------------------------------

    /** Greedy longest-match-first; an unmatchable word contributes one [UNK]. */
    private void wordPiece(String word, List<String> out) {
        if (word.length() > MAX_INPUT_CHARS_PER_WORD) {
            out.add(UNK);
            return;
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < word.length()) {
            int end = word.length();
            String match = null;
            while (start < end) {
                String piece = start == 0
                    ? word.substring(start, end)
                    : CONTINUING_PREFIX + word.substring(start, end);
                if (vocab.containsKey(piece)) {
                    match = piece;
                    break;
                }
                end--;
            }
            if (match == null) {
                // Any unmatchable slice makes the WHOLE word [UNK] - the
                // reference does not emit a partial decomposition.
                out.add(UNK);
                return;
            }
            pieces.add(match);
            start = end;
        }
        out.addAll(pieces);
    }

    // --- character classes (the reference's own definitions) -------------------

    private static boolean isWhitespace(int cp) {
        return cp == ' ' || cp == '\t' || cp == '\n' || cp == '\r'
            || Character.getType(cp) == Character.SPACE_SEPARATOR;
    }

    private static boolean isControl(int cp) {
        if (cp == '\t' || cp == '\n' || cp == '\r') {
            return false;                     // handled as whitespace instead
        }
        int type = Character.getType(cp);
        return type == Character.CONTROL || type == Character.FORMAT;
    }

    /**
     * The reference treats the ASCII symbol ranges as punctuation in addition
     * to the Unicode P* categories — characters like '$' and '+' are not P* but
     * must still split.
     */
    private static boolean isPunctuation(int cp) {
        if ((cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64)
                || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126)) {
            return true;
        }
        return switch (Character.getType(cp)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
                 Character.START_PUNCTUATION, Character.END_PUNCTUATION,
                 Character.INITIAL_QUOTE_PUNCTUATION,
                 Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION -> true;
            default -> false;
        };
    }

    /** The CJK ideograph blocks BERT splits per character. */
    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)
            || (cp >= 0x3400 && cp <= 0x4DBF)
            || (cp >= 0x20000 && cp <= 0x2A6DF)
            || (cp >= 0x2A700 && cp <= 0x2B73F)
            || (cp >= 0x2B740 && cp <= 0x2B81F)
            || (cp >= 0x2B820 && cp <= 0x2CEAF)
            || (cp >= 0xF900 && cp <= 0xFAFF)
            || (cp >= 0x2F800 && cp <= 0x2FA1F);
    }
}
