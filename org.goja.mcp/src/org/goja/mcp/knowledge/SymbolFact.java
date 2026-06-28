package org.goja.mcp.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 15a — the shared, symbol-anchored evidence record emitted by the
 * knowledge tools (naming conventions + Javadoc facts) and reused by Sprint 15b
 * (null-safety) and Sprint 21 (local experience store). Defining it once here
 * means those downstream sprints consume ONE shape instead of inventing their own.
 *
 * <p>Shape (NON_NULL — empty/absent fields are omitted from {@link #toMap()}):</p>
 * <pre>
 * { type, symbol | scope{packages,symbols}, summary, details?,
 *   source{kind,file?}, confidence, evidence[], exceptions[] }
 * </pre>
 *
 * <p>{@code type} values align with Sprint 21's {@code experience_entry.type}
 * enum ({@code naming_convention}, {@code api_contract}, {@code lifecycle_rule},
 * {@code deprecated_behavior}, {@code extension_point}, {@code domain_fact}, …).
 * A fact is anchored EITHER to a single {@code symbol} (FQN) OR a {@code scope}
 * (packages/symbols), not both.</p>
 *
 * <p>Deliberately minimal and additive — downstream only reads these keys.</p>
 */
public final class SymbolFact {

    private final String type;
    private final String symbol;
    private final List<String> scopePackages;
    private final List<String> scopeSymbols;
    private final String summary;
    private final String details;
    private final String sourceKind;
    private final String sourceFile;
    private final Confidence confidence;
    private final List<Object> evidence;
    private final List<String> exceptions;

    private SymbolFact(Builder b) {
        this.type = b.type;
        this.symbol = b.symbol;
        this.scopePackages = b.scopePackages;
        this.scopeSymbols = b.scopeSymbols;
        this.summary = b.summary;
        this.details = b.details;
        this.sourceKind = b.sourceKind;
        this.sourceFile = b.sourceFile;
        this.confidence = b.confidence;
        this.evidence = b.evidence;
        this.exceptions = b.exceptions;
    }

    /**
     * Start a fact builder. {@code type}, {@code summary} and {@code confidence}
     * are required; anchor it with either {@link Builder#symbol} or
     * {@link Builder#scope}.
     */
    public static Builder of(String type, String summary, Confidence confidence) {
        return new Builder(type, summary, confidence);
    }

    /** Render to the response map with deterministic key order, omitting empties. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        if (symbol != null && !symbol.isBlank()) {
            m.put("symbol", symbol);
        }
        if ((scopePackages != null && !scopePackages.isEmpty())
                || (scopeSymbols != null && !scopeSymbols.isEmpty())) {
            Map<String, Object> scope = new LinkedHashMap<>();
            scope.put("packages", scopePackages == null ? List.of() : scopePackages);
            scope.put("symbols", scopeSymbols == null ? List.of() : scopeSymbols);
            m.put("scope", scope);
        }
        m.put("summary", summary);
        if (details != null && !details.isBlank()) {
            m.put("details", details);
        }
        if (sourceKind != null && !sourceKind.isBlank()) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("kind", sourceKind);
            if (sourceFile != null && !sourceFile.isBlank()) {
                source.put("file", sourceFile);
            }
            m.put("source", source);
        }
        m.put("confidence", confidence.wire());
        if (evidence != null && !evidence.isEmpty()) {
            m.put("evidence", evidence);
        }
        if (exceptions != null && !exceptions.isEmpty()) {
            m.put("exceptions", exceptions);
        }
        return m;
    }

    public static final class Builder {
        private final String type;
        private final String summary;
        private final Confidence confidence;
        private String symbol;
        private List<String> scopePackages;
        private List<String> scopeSymbols;
        private String details;
        private String sourceKind;
        private String sourceFile;
        private List<Object> evidence;
        private List<String> exceptions;

        private Builder(String type, String summary, Confidence confidence) {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("type is required");
            }
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("summary is required");
            }
            if (confidence == null) {
                throw new IllegalArgumentException("confidence is required");
            }
            this.type = type;
            this.summary = summary;
            this.confidence = confidence;
        }

        /** Anchor to a single FQN symbol (mutually exclusive with scope). */
        public Builder symbol(String fqn) {
            this.symbol = fqn;
            return this;
        }

        /** Anchor to a scope of packages/symbols (mutually exclusive with symbol). */
        public Builder scope(List<String> packages, List<String> symbols) {
            this.scopePackages = packages;
            this.scopeSymbols = symbols;
            return this;
        }

        public Builder details(String details) {
            this.details = details;
            return this;
        }

        /** Provenance, e.g. kind = {@code javadoc} / {@code inference} / {@code doclint}. */
        public Builder source(String kind, String file) {
            this.sourceKind = kind;
            this.sourceFile = file;
            return this;
        }

        public Builder evidence(List<Object> evidence) {
            this.evidence = evidence;
            return this;
        }

        public Builder addEvidence(Object item) {
            if (this.evidence == null) {
                this.evidence = new ArrayList<>();
            }
            this.evidence.add(item);
            return this;
        }

        public Builder exceptions(List<String> exceptions) {
            this.exceptions = exceptions;
            return this;
        }

        public SymbolFact build() {
            return new SymbolFact(this);
        }

        /** Convenience: build and render in one call. */
        public Map<String, Object> toMap() {
            return build().toMap();
        }
    }
}
