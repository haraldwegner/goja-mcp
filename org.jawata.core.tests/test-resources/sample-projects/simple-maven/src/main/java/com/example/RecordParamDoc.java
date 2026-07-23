package com.example;

/**
 * A fully documented record whose type Javadoc documents its components with
 * param tags and inline tags — the shape of
 * org.jawata.mcp.embed.EmbedderIdentity, which javadoc_lack falsely flagged as
 * undocumented, and whose served summary was truncated at the first inline tag
 * (jawata-mcp#8). Comparing across identities is refused, which is why
 * {@link #current} exists.
 *
 * <p>Every public member below carries Javadoc, so the correct finding count
 * for this file is ZERO.</p>
 *
 * @param model   the checkpoint, e.g. {@code sentence-transformers/all-MiniLM-L6-v2}
 * @param dim     the vector dimension
 * @param version our pipeline version
 */
public record RecordParamDoc(String model, int dim, int version) {

    /** Bumped when the pipeline starts producing different vectors. */
    public static final int CURRENT_VERSION = 1;

    /** The bundled checkpoint's name, as the registry spells it. */
    public static final String MINILM_L6_V2 = "sentence-transformers/all-MiniLM-L6-v2";

    /** The identity this build produces. */
    public static RecordParamDoc current() {
        return new RecordParamDoc(MINILM_L6_V2, 384, CURRENT_VERSION);
    }
}
