package org.goja.mcp.tools.smell;

import org.goja.mcp.domain.DetectorCatalog;

/**
 * Sprint 19 (Kerievsky) — registers the pattern-opportunity {@link
 * org.goja.mcp.domain.Detector}s under family {@code kerievsky}. These are the
 * detect triggers for the {@code refactor_to_pattern} transforms; the "toward"
 * triggers largely reuse existing Fowler kinds ({@code switch_statements},
 * {@code long_method}, {@code speculative_generality}), so this contributor holds
 * only the pattern-specific shapes with no existing equivalent.
 */
public final class KerievskyDetectors {

    private KerievskyDetectors() {
    }

    /** Register every net-new Kerievsky detector into {@code catalog} (family {@code kerievsky}). */
    public static DetectorCatalog registerInto(DetectorCatalog catalog) {
        return catalog
            .register(new SingletonDetector(), "kerievsky")
            .register(new TypeCodeDetector(), "kerievsky");
    }
}
