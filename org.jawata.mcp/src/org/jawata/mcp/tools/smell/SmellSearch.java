package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;
import org.jawata.mcp.tools.smell.AbstractAstDetector.ScanDegradation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprint 20 — shared JDT-search helpers for the smell / SOLID detectors,
 * extracted from the fan-in logic that was copy-pasted in {@code GodClassDetector},
 * {@code LazyClassDetector}, and {@code ShotgunSurgeryDetector}.
 *
 * <p>A failed search is NOT a low count and NOT a high count — it is an unknown,
 * and it is {@link ScanDegradation#report reported} so the scan says a finding may
 * have been suppressed. It used to be swallowed into {@code -1} with the exception
 * discarded, which turned every search failure into "this type has no interesting
 * fan-in" — an empty result from a lookup that never ran.</p>
 */
public final class SmellSearch {

    private static final int MAX_REFS = 1000;

    private SmellSearch() {
    }

    /**
     * Count of distinct <em>other</em> types that reference {@code type} (its fan-in).
     *
     * @return the distinct referencing-type count (&ge; 0), or {@code -1} if the
     *         search could not be performed — in which case the failure has been
     *         {@link ScanDegradation#report reported} to {@code degraded}. Callers
     *         must treat {@code -1} as "unknown — do not flag", never as a count.
     */
    public static int referencingTypeCount(IType type, IJdtService service,
                                           ScanDegradation degraded) {
        if (type == null) {
            degraded.report("fan-in unknown: no IType to search for");
            return -1;
        }
        try {
            String selfFqn = type.getFullyQualifiedName();
            List<SearchMatch> refs = service.getSearchService().findAllReferences(type, MAX_REFS);
            Set<String> referencingTypes = new HashSet<>();
            for (SearchMatch match : refs) {
                if (match.getElement() instanceof IJavaElement el) {
                    IType enclosing = (IType) el.getAncestor(IJavaElement.TYPE);
                    if (enclosing != null && !enclosing.getFullyQualifiedName().equals(selfFqn)) {
                        referencingTypes.add(enclosing.getFullyQualifiedName());
                    }
                }
            }
            return referencingTypes.size();
        } catch (Exception e) {
            degraded.report("fan-in search FAILED for " + type.getElementName() + ": "
                + e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : ""));
            return -1;
        }
    }

    /** Convenience: resolve {@code binding} to its IType, then count referencing types. */
    public static int referencingTypeCount(ITypeBinding binding, IJdtService service,
                                           ScanDegradation degraded) {
        if (binding == null) {
            degraded.report("fan-in unknown: type binding did not resolve");
            return -1;
        }
        if (!(binding.getJavaElement() instanceof IType type)) {
            degraded.report("fan-in unknown: binding " + binding.getQualifiedName()
                + " has no IType in the Java model");
            return -1;
        }
        return referencingTypeCount(type, service, degraded);
    }
}
