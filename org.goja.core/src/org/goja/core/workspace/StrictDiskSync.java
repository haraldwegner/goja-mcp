package org.goja.core.workspace;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.goja.core.IJdtService;
import org.goja.core.LoadedProject;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 21d (item B) — strict disk sync: run ONCE per tool call at the dispatch seam,
 * BEFORE the tool computes anything, so every answer — search, analysis, refactor
 * change computation, store pointer judgment — reflects the CURRENT working tree.
 *
 * <p>{@link DiskSyncGuard} detects what changed by evidence; this class reconciles it:
 * {@code refreshLocal} on ONLY the changed files (added/deleted files reconcile their
 * nearest existing ancestor container so brand-new package chains materialize), then
 * one build per affected project. A root seen for the first time gets one whole-project
 * reconcile — closing the blind window between model load and first guard pass.
 *
 * <p><b>No opt-out.</b> Correctness is not configurable; the unchanged-tree fast path
 * (scan finds nothing → zero reconcile work) is the only skip, earned per call.
 *
 * <p>Non-final so tests can inject failure (the dispatch layer's WARN-and-proceed
 * crash policy is itself under test).
 */
public class StrictDiskSync {

    /** Per-call outcome, for the dispatch log + the workspace-scope benchmark. */
    public record SyncReport(boolean skipped, int newProjects, int refreshedFiles,
                             int builtProjects, long scanNanos, long totalNanos) {
        public boolean reconciled() {
            return newProjects > 0 || refreshedFiles > 0;
        }
    }

    /**
     * Build kind for absorbing targeted refreshes — an EMPIRICAL decision, settled
     * (Sprint 21d Stage 1, 2026-07-07): with {@code INCREMENTAL_BUILD} the SEARCH
     * index followed the refreshLocal resource deltas (the new-file repro passed) but
     * the incremental BUILDER did not — an externally added broken file produced no
     * PROBLEM markers, reproducing {@code CompileWorkspaceTool}'s documented finding
     * ("INCREMENTAL_BUILD's delta tracking lags fresh refreshLocal additions in
     * Tycho-test-style headless Equinox runtimes") on the marker path. FULL_BUILD is
     * therefore the kind here too; it runs ONLY when changes were detected — the
     * unchanged-tree fast path builds nothing.
     */
    private static final int BUILD_KIND = IncrementalProjectBuilder.FULL_BUILD;

    private final Supplier<IJdtService> service;
    private final DiskSyncGuard guard = new DiskSyncGuard();

    public StrictDiskSync(Supplier<IJdtService> service) {
        this.service = service;
    }

    /**
     * Scan all loaded projects' roots and reconcile whatever external edits are found.
     * Synchronized: the MCP HTTP transport dispatches on a cached thread pool —
     * concurrent tool calls must not overlap refreshLocal/build or race the snapshot.
     */
    public synchronized SyncReport syncBeforeCall() throws CoreException {
        long t0 = System.nanoTime();
        IJdtService s = service.get();
        if (s == null) {
            return new SyncReport(true, 0, 0, 0, 0, System.nanoTime() - t0);
        }
        Collection<LoadedProject> projects = s.allProjects();
        if (projects.isEmpty()) {
            return new SyncReport(true, 0, 0, 0, 0, System.nanoTime() - t0);
        }

        Map<Path, LoadedProject> byRoot = new LinkedHashMap<>();
        for (LoadedProject lp : projects) {
            byRoot.put(lp.projectRoot().toAbsolutePath().normalize(), lp);
        }
        DiskSyncGuard.ScanResult scan = guard.scan(byRoot.keySet());

        NullProgressMonitor monitor = new NullProgressMonitor();
        Set<IProject> toBuild = new LinkedHashSet<>();
        int refreshed = 0;

        for (Path root : scan.newRoots()) {
            LoadedProject lp = byRoot.get(root);
            if (lp != null) {
                IProject prj = lp.javaProject().getProject();
                prj.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                toBuild.add(prj);
            }
        }
        for (Path p : scan.changed()) {
            refreshed += refreshExisting(byRoot, p, toBuild, monitor);
        }
        for (Path p : scan.deleted()) {
            refreshed += refreshExisting(byRoot, p, toBuild, monitor);
        }
        for (Path p : scan.added()) {
            refreshed += refreshAdded(byRoot, p, toBuild, monitor);
        }
        // Structure changes (added/deleted files): the JAVA MODEL caches each source
        // root's package-fragment children, and this headless runtime does not
        // repopulate that cache from the resource delta alone (observed live: the
        // diagnostics model walk stayed at N files until an element lookup opened the
        // chain). Closing the source roots drops the stale cache; the model
        // repopulates lazily from the just-refreshed resources.
        if (!scan.added().isEmpty() || !scan.deleted().isEmpty()) {
            Set<LoadedProject> structureChanged = new LinkedHashSet<>();
            for (Path p : scan.added()) {
                LoadedProject lp = owner(byRoot, p);
                if (lp != null) {
                    structureChanged.add(lp);
                }
            }
            for (Path p : scan.deleted()) {
                LoadedProject lp = owner(byRoot, p);
                if (lp != null) {
                    structureChanged.add(lp);
                }
            }
            for (LoadedProject lp : structureChanged) {
                for (IPackageFragmentRoot root : lp.javaProject().getPackageFragmentRoots()) {
                    if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                        root.close();
                    }
                }
            }
        }
        for (IProject prj : toBuild) {
            prj.build(BUILD_KIND, monitor);
        }
        return new SyncReport(false, scan.newRoots().size(), refreshed, toBuild.size(),
            scan.durationNanos(), System.nanoTime() - t0);
    }

    /** Changed or deleted: refresh exactly the file handle (deletion drops it from the model). */
    private static int refreshExisting(Map<Path, LoadedProject> byRoot, Path file,
            Set<IProject> toBuild, NullProgressMonitor monitor) throws CoreException {
        LoadedProject lp = owner(byRoot, file);
        if (lp == null) {
            return 0;
        }
        IProject prj = lp.javaProject().getProject();
        IFile handle = fileHandle(prj, file);
        if (handle == null) {
            return 0;
        }
        handle.refreshLocal(IResource.DEPTH_ZERO, monitor);
        toBuild.add(prj);
        return 1;
    }

    /** Added: a brand-new package chain has no existing handle chain — reconcile the
     *  nearest ancestor container that EXISTS in the model (subtree-bounded). */
    private static int refreshAdded(Map<Path, LoadedProject> byRoot, Path file,
            Set<IProject> toBuild, NullProgressMonitor monitor) throws CoreException {
        LoadedProject lp = owner(byRoot, file);
        if (lp == null) {
            return 0;
        }
        IProject prj = lp.javaProject().getProject();
        IFile handle = fileHandle(prj, file);
        if (handle == null) {
            return 0;
        }
        IContainer c = handle.getParent();
        while (c != null && c.getType() != IResource.PROJECT && !c.exists()) {
            c = c.getParent();
        }
        (c != null ? c : prj).refreshLocal(IResource.DEPTH_INFINITE, monitor);
        toBuild.add(prj);
        return 1;
    }

    private static LoadedProject owner(Map<Path, LoadedProject> byRoot, Path file) {
        LoadedProject best = null;
        int bestLen = -1;
        for (Map.Entry<Path, LoadedProject> e : byRoot.entrySet()) {
            if (file.startsWith(e.getKey()) && e.getKey().getNameCount() > bestLen) {
                best = e.getValue();
                bestLen = e.getKey().getNameCount();
            }
        }
        return best;
    }

    private static IFile fileHandle(IProject prj, Path abs) {
        // Resolve by LOCATION through the workspace root — goja projects mount their
        // source dirs as LINKED folders (the project lives in the -data area, the
        // sources stay in the real checkout), so project-relative path math yields
        // garbage; findFilesForLocationURI searches linked resources too. Observed
        // live: without this, the targeted reconcile silently refreshed NOTHING.
        IFile[] mapped = prj.getWorkspace().getRoot().findFilesForLocationURI(abs.toUri());
        for (IFile f : mapped) {
            if (prj.equals(f.getProject())) {
                return f;
            }
        }
        return mapped.length > 0 ? mapped[0] : null;
    }
}
