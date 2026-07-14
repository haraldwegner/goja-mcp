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

    /**
     * The search is bounded so ranking a large workspace stays cheap — but the
     * bound must be high enough to DISCRIMINATE the types it exists to rank.
     * Dogfood (v2.11.0, on jawata's own 506-source workspace) found a cap of 200
     * saturating the top SIX types at once: the ordering among exactly the most
     * load-bearing types was arbitrary, and "200" read as a count when it was a
     * floor. A saturated count is now reported as such ({@code atLeast}).
     */
    private static final int REFERENCE_CAP = 2000;

    /**
     * Keyed by each project's ROOT, its source-file count and the moment it was loaded — so a
     * reload, an added file or a removed file all recompute, and two different workspaces can
     * never be served each other's landmarks.
     *
     * <p>It was keyed on the Eclipse project's element NAME alone, and {@link #invalidate}
     * had no callers anywhere. On a long-lived resident that made the first ranking permanent:
     * types renamed or deleted afterwards were still handed out as orientation, and a landmark
     * whose name no longer resolves is worse than no landmark — it is the exact invariant this
     * class exists to protect, and the one its own test asserts. Worse, an Eclipse project name
     * is derived from the directory basename, so loading a DIFFERENT tree with the same
     * basename was served the first tree's landmarks verbatim. Sprint-24 audit (T1.12).</p>
     */
    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();

    /**
     * A backstop for the changes a key cannot see (an in-place rename leaves the file count
     * and the load time untouched). Bounded staleness beats unbounded staleness; an explicit
     * {@link #invalidate} is still the immediate path.
     */
    private static final long MAX_AGE_MILLIS = 5L * 60 * 1000;

    /** Keep the map from accumulating one dead entry per reload on a long-lived server. */
    private static final int MAX_ENTRIES = 16;

    private record Entry(List<Map<String, Object>> ranked, long computedAtMillis) {
        boolean isStale() {
            return System.currentTimeMillis() - computedAtMillis > MAX_AGE_MILLIS;
        }
    }

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
        String key = cacheKey(service);
        Entry entry = CACHE.get(key);
        if (entry == null || entry.isStale() || !stillResolves(service, entry.ranked(), limit)) {
            if (CACHE.size() >= MAX_ENTRIES) {
                CACHE.clear();
            }
            entry = new Entry(rank(service), System.currentTimeMillis());
            CACHE.put(key, entry);
        }
        List<Map<String, Object>> ranked = entry.ranked();
        return ranked.size() > limit ? new ArrayList<>(ranked.subList(0, limit)) : ranked;
    }

    /**
     * Would every landmark we are about to hand out still resolve by name?
     *
     * <p>The cache key sees a reload, an added file, a removed file — it cannot see a type
     * RENAMED in place, which is precisely what a refactoring does, and which leaves the
     * cached ranking advertising a name that no longer exists. Rather than try to hook every
     * mutation in the system, the invariant is simply enforced where it matters: a landmark
     * is offered as an address, so it is checked as an address, every time. Cheap — we only
     * ever check the handful actually being served.</p>
     */
    private static boolean stillResolves(IJdtService service, List<Map<String, Object>> ranked, int limit) {
        for (Map<String, Object> landmark : ranked.subList(0, Math.min(limit, ranked.size()))) {
            try {
                if (service.findType(String.valueOf(landmark.get("qualifiedName"))) == null) {
                    log.debug("landmark {} no longer resolves — reranking",
                        landmark.get("qualifiedName"));
                    return false;
                }
            } catch (Exception e) {
                return false;
            }
        }
        return true;
    }

    private static String cacheKey(IJdtService service) {
        StringBuilder key = new StringBuilder();
        for (LoadedProject lp : service.allProjects()) {
            key.append(lp.projectRoot())
                .append('@').append(lp.sourceFileCount())
                .append('@').append(lp.loadedAt().toEpochMilli())
                .append(';');
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
                if (references >= REFERENCE_CAP) {
                    // Honest: this is a floor, not a count — the search stopped here.
                    landmark.put("atLeast", true);
                }
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
