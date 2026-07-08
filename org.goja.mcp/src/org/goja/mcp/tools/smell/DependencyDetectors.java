package org.goja.mcp.tools.smell;

import org.goja.mcp.domain.DetectorCatalog;

/**
 * Sprint 22a P1-d — registers the dependency-direction {@link
 * org.goja.mcp.domain.Detector}s into a {@link DetectorCatalog} (family
 * {@code quality}). Like the Fowler/SOLID registrars, adding a detector here
 * surfaces it as a new {@code find_quality_issue} kind with no new tool. This
 * is also the &ge;1 net-new catalog kind Sprint 22a R.1 (Stage 14) requires.
 */
public final class DependencyDetectors {

    private DependencyDetectors() {
    }

    /** Register every dependency-direction detector into {@code catalog}; returns it for chaining. */
    public static DetectorCatalog registerInto(DetectorCatalog catalog) {
        return catalog.register(new ForbiddenEdgeDetector(), "quality");
    }
}
