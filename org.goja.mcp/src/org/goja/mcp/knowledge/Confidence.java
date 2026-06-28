package org.goja.mcp.knowledge;

/**
 * Confidence level for a {@link SymbolFact}. Sprint 15a — shared across the
 * naming/Javadoc knowledge tools (and reused by Sprint 15b null-safety +
 * Sprint 21 experience store).
 *
 * <p>Serialized on the wire as the lowercase name ({@code low}/{@code medium}/
 * {@code high}) to match the documented {@code symbol_fact} payload shape.</p>
 */
public enum Confidence {
    LOW,
    MEDIUM,
    HIGH;

    /** Lowercase wire form used in tool responses. */
    public String wire() {
        return name().toLowerCase();
    }
}
