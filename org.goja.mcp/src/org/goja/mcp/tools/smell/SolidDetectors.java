package org.goja.mcp.tools.smell;

import org.goja.mcp.domain.DetectorCatalog;

/**
 * Sprint 20 — registers the net-new SOLID {@link org.goja.mcp.domain.Detector}s
 * into a {@link DetectorCatalog} under family {@code solid}. SRP's §7
 * "unfinished encapsulation" signal and the OCP/LSP traces are NOT re-detected
 * here — they are existing Fowler kinds tagged {@code solid} by
 * {@link FowlerDetectors} (incomplete_delegation, refused_bequest,
 * divergent_change, shotgun_surgery). This contributor holds only the kinds with
 * no Fowler equivalent.
 */
public final class SolidDetectors {

    private SolidDetectors() {
    }

    /** Register every net-new SOLID detector into {@code catalog} (family {@code solid}). */
    public static DetectorCatalog registerInto(DetectorCatalog catalog) {
        return catalog
            .register(new DipDetector(), "solid")
            .register(new IspDetector(), "solid")
            .register(new SrpCohesionDetector(), "solid")
            .register(new LspDetector(), "solid");
    }
}
