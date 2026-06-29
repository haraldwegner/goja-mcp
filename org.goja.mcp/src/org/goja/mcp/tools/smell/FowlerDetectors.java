package org.goja.mcp.tools.smell;

import org.goja.mcp.domain.DetectorCatalog;

/**
 * Sprint 17 — registers the Fowler smell {@link org.goja.mcp.domain.Detector}s
 * into a {@link DetectorCatalog}. SRP-separate from {@code QualityDetectors}
 * (which adapts the eight legacy lexical/structural analyzers); the
 * {@code find_quality_issue} front door projects the combined catalog, so each
 * smell registered here becomes a new {@code kind} with no new tool.
 *
 * <p>Detectors here implement {@link AbstractAstDetector}, so they receive the
 * project-scoped {@code IJdtService} at {@code detect(...)} time and need no
 * service supplier at construction.</p>
 */
public final class FowlerDetectors {

    private FowlerDetectors() {
    }

    /** Register every Fowler detector into {@code catalog}; returns it for chaining. */
    public static DetectorCatalog registerInto(DetectorCatalog catalog) {
        return catalog
            .register(new LongMethodDetector())
            .register(new GodClassDetector())
            .register(new LongParameterListDetector())
            .register(new DataClumpsDetector())
            .register(new FeatureEnvyDetector())
            .register(new MessageChainsDetector())
            .register(new InappropriateIntimacyDetector())
            .register(new MiddleManDetector())
            .register(new PrimitiveObsessionDetector())
            .register(new SwitchStatementsDetector())
            .register(new RefusedBequestDetector())
            .register(new TemporaryFieldDetector());
        // Stage 4+ append: lazy_class, speculative_generality, parallel_inheritance, incomplete_delegation, …
    }
}
