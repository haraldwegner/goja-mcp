package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sprint 24 (D4) — <b>a session starts oriented</b>. A human who has worked in a
 * codebase knows its landmarks: the handful of types everything else leans on.
 * A fresh agent starts with nothing and searches its way to them, every session.
 *
 * <p>This names them up front: the project's own types, ranked by how much of
 * the code depends on each. That is the head start — memory before the first
 * search.</p>
 *
 * <p>Computed from the JDT index (incoming references per source type) and
 * cached per workspace load: a landmark set changes only when the code does,
 * and the ranking is the same for every session that starts against it.</p>
 */
public final class Landmarks {

    private static final Logger log = LoggerFactory.getLogger(Landmarks.class);

    /** Enough to rank by; a landmark's exact reference count is not the point. */
    private static final int REFERENCE_CAP = 200;

    /** Keyed by project + its source-file count: a changed workspace recomputes. */
    private static final Map<String, List<Map<String, Object>>> CACHE = new ConcurrentHashMap<>();

    private Landmarks() {
    }

    /** Drop the cached ranking (the workspace changed under us). */
    public static void invalidate() {
        CACHE.clear();
    }

    /**
     * The workspace's most-referenced project types, most-referenced first.
     *
     * @param limit how many to name (the orientation set, not an inventory).
     */
    public static List<Map<String, Object>> of(IJdtService service, int limit) {
        List<Map<String, Object>> ranked = CACHE.computeIfAbsent(
            cacheKey(service), key -> rank(service));
        return ranked.size() > limit ? new ArrayList<>(ranked.subList(0, limit)) : ranked;
    }

    private static String cacheKey(IJdtService service) {
        StringBuilder key = new StringBuilder();
        for (LoadedProject lp : service.allProjects()) {
            key.append(lp.javaProject().getElementName()).append(':');
        }
        return key.toString();
    }

    private static List<Map<String, Object>> rank(IJdtService service) {
        List<Map<String, Object>> landmarks = new ArrayList<>();
        for (IType type : sourceTypes(service)) {
            try {
                String fqn = type.getFullyQualifiedName();
                // A landmark that cannot be ADDRESSED by its name is no landmark: the
                // whole point is to feed straight into name-based navigation. Secondary
                // types (declared beside a file's primary type) do not resolve by FQN,
                // so they are not offered as orientation.
                if (service.findType(fqn) == null) {
                    continue;
                }
                int references = service.getSearchService()
                    .findAllReferences(type, REFERENCE_CAP).size();
                if (references == 0) {
                    continue;
                }
                Map<String, Object> landmark = new LinkedHashMap<>();
                landmark.put("name", type.getElementName());
                landmark.put("qualifiedName", fqn);
                if (type.getResource() != null && type.getResource().getLocation() != null) {
                    landmark.put("filePath", service.getPathUtils().formatPath(
                        type.getResource().getLocation().toOSString()));
                }
                landmark.put("references", references);
                landmarks.add(landmark);
            } catch (Exception e) {
                log.debug("Ranking {} failed: {}", type.getElementName(), e.getMessage());
            }
        }
        landmarks.sort(Comparator.comparingInt(
            (Map<String, Object> l) -> (Integer) l.get("references")).reversed());
        return landmarks;
    }

    /** Every type declared in the workspace's own SOURCE (never a dependency's). */
    private static List<IType> sourceTypes(IJdtService service) {
        List<IType> types = new ArrayList<>();
        for (LoadedProject lp : service.allProjects()) {
            try {
                for (IPackageFragmentRoot root : lp.javaProject().getPackageFragmentRoots()) {
                    if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                        continue;
                    }
                    for (IJavaElement child : root.getChildren()) {
                        if (!(child instanceof IPackageFragment pkg)) {
                            continue;
                        }
                        for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                            types.addAll(List.of(cu.getTypes()));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Walking source types of {} failed: {}",
                    lp.javaProject().getElementName(), e.getMessage());
            }
        }
        return types;
    }
}
