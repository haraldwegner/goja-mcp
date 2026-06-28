package org.goja.mcp.tools.nullness;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 15b — the nullness annotation families goja understands, with
 * source-only detection (by import FQN). Works without the annotation jars on
 * the classpath — the import string alone identifies the family, mirroring
 * {@code LombokDetector}'s approach.
 */
public enum NullnessStyle {
    JSPECIFY("org.jspecify.annotations", "Nullable", "NonNull"),
    ECLIPSE("org.eclipse.jdt.annotation", "Nullable", "NonNull"),
    JETBRAINS("org.jetbrains.annotations", "Nullable", "NotNull"),
    JSR305("javax.annotation", "Nullable", "Nonnull"),
    SPOTBUGS("edu.umd.cs.findbugs.annotations", "Nullable", "NonNull"),
    CHECKER("org.checkerframework.checker.nullness.qual", "Nullable", "NonNull"),
    ANDROIDX("androidx.annotation", "Nullable", "NonNull");

    /** Default family when a project has no detectable style (Sprint 15b decision: JSpecify). */
    public static final NullnessStyle DEFAULT = JSPECIFY;

    public final String pkg;
    public final String nullable;
    public final String nonnull;

    NullnessStyle(String pkg, String nullable, String nonnull) {
        this.pkg = pkg;
        this.nullable = nullable;
        this.nonnull = nonnull;
    }

    public String nullableFqn() {
        return pkg + "." + nullable;
    }

    public String nonnullFqn() {
        return pkg + "." + nonnull;
    }

    /** The family an import belongs to (exact package or a member of it), if any. */
    public static Optional<NullnessStyle> ofImport(String importFqn) {
        if (importFqn == null || importFqn.isBlank()) {
            return Optional.empty();
        }
        for (NullnessStyle s : values()) {
            if (importFqn.equals(s.pkg) || importFqn.startsWith(s.pkg + ".")) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** Count nullness imports per family across the given import FQNs. */
    public static Map<NullnessStyle, Integer> tally(Iterable<String> importFqns) {
        Map<NullnessStyle, Integer> counts = new EnumMap<>(NullnessStyle.class);
        for (String fqn : importFqns) {
            ofImport(fqn).ifPresent(s -> counts.merge(s, 1, Integer::sum));
        }
        return counts;
    }
}
