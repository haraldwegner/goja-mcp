package com.example;

/**
 * A fully documented record whose type Javadoc has NO {@code @param} tags —
 * the shape of {@code org.jawata.mcp.knowledge.StoredEntry}, which javadoc_lack
 * correctly reports as documented (jawata-mcp#8, the control).
 *
 * <p>Every public member below carries Javadoc, so the correct finding count
 * for this file is ZERO.</p>
 */
public record RecordPlainDoc(String id, int rank) {

    /** A constant with no relation to a component. */
    public static final int DEFAULT_RANK = 0;

    /** The bundled id, as the registry spells it. */
    public static final String SEED_ID = "seed";

    /** The instance this build produces. */
    public static RecordPlainDoc seed() {
        return new RecordPlainDoc(SEED_ID, DEFAULT_RANK);
    }
}
