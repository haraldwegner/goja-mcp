package org.jawata.mcp.embed;

/**
 * What produced a vector: model, dimension and pipeline version.
 *
 * <p>Every stored vector carries one. The rule it exists to enforce is
 * absolute: <b>vectors of different identities are never compared.</b> Cosine
 * between two embedding spaces is not a small error, it is a meaningless
 * number that still looks like a score — it would rank, sort and pass a floor
 * exactly like a real one, so nothing downstream could notice. Comparing them
 * must be impossible rather than discouraged, which is why
 * {@link #requireComparable} throws instead of returning a boolean nobody is
 * obliged to check.</p>
 *
 * <p>{@code version} is ours, not the model's: it changes when OUR pipeline
 * changes in a way that moves vectors (tokenizer fix, pooling change, a
 * different precision that failed parity) even though the checkpoint is
 * identical. An identity change triggers the one-time corpus re-embed, mirroring
 * how {@code LOADER_VERSION} self-heals the knowledge store.</p>
 *
 * @param model the checkpoint, e.g. {@code sentence-transformers/all-MiniLM-L6-v2}
 * @param dim   the vector dimension
 * @param version our pipeline version
 */
public record EmbedderIdentity(String model, int dim, int version) {

    /** Bumped when OUR pipeline starts producing different vectors for the same text. */
    public static final int CURRENT_VERSION = 1;

    /** The bundled checkpoint's name, as the model registry spells it. */
    public static final String MINILM_L6_V2 = "sentence-transformers/all-MiniLM-L6-v2";

    /** The identity this build produces. */
    public static EmbedderIdentity current() {
        return new EmbedderIdentity(MINILM_L6_V2, 384, CURRENT_VERSION);
    }

    /**
     * @throws IllegalArgumentException when the model is unnamed or the
     *         dimension is not positive — an identity that cannot be compared
     *         is worse than none, because it would still look like one.
     */
    public EmbedderIdentity {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must be named");
        }
        if (dim <= 0) {
            throw new IllegalArgumentException("dim must be positive, got " + dim);
        }
    }

    /** A stable string for persisting alongside a vector. */
    public String key() {
        return model + "/" + dim + "/v" + version;
    }

    /** Parse what {@link #key()} wrote; {@code null} for unreadable input. */
    public static EmbedderIdentity parse(String key) {
        if (key == null) {
            return null;
        }
        int lastSlash = key.lastIndexOf('/');
        int dimSlash = key.lastIndexOf('/', lastSlash - 1);
        if (lastSlash < 0 || dimSlash < 0 || !key.startsWith("v", lastSlash + 1)) {
            return null;
        }
        try {
            return new EmbedderIdentity(
                key.substring(0, dimSlash),
                Integer.parseInt(key.substring(dimSlash + 1, lastSlash)),
                Integer.parseInt(key.substring(lastSlash + 2)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Refuse to compare vectors from different embedding spaces.
     *
     * @throws IllegalArgumentException when the identities differ — including
     *     when either is {@code null}, because an unknown identity cannot be
     *     shown to match and "unknown" must never be treated as "same".
     */
    public static void requireComparable(EmbedderIdentity a, EmbedderIdentity b) {
        if (a == null || b == null || !a.equals(b)) {
            throw new IllegalArgumentException(
                "refusing to compare vectors across embedder identities: " + a + " vs " + b);
        }
    }
}
