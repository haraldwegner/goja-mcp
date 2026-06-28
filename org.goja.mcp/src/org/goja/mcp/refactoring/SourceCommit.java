package org.goja.mcp.refactoring;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.ReplaceEdit;
import org.goja.core.IJdtService;

import java.util.List;

/**
 * Sprint 14b — undo support for the codegen tools, which rewrite a whole
 * compilation unit via working-copy commit rather than building an LTK
 * Change. The undo handle is a full-source restore {@link TextFileChange}
 * (snapshot taken before the commit); staging wraps the new source the same
 * way so {@code apply_refactoring} can commit it later.
 */
public final class SourceCommit {

    private SourceCommit() {}

    /** Outcome of an applied commit: undo handle + rendered diff. */
    public record Committed(String undoChangeId, String diff, String filePath) {}

    /** Outcome of a staged (auto_apply: false) call: changeId + diff. */
    public record Staged(String changeId, String diff, String filePath) {}

    /**
     * Commit {@code newSource} to the CU (working-copy commit, same
     * mechanism the codegen tools used pre-14b), cache a full-source restore
     * change as the undo handle, and render the unified diff.
     */
    public static Committed commitWithUndo(ICompilationUnit cu, String newSource, String label,
                                           RefactoringChangeCache cache, IJdtService service)
            throws Exception {
        String oldSource = cu.getSource();
        String filePath = formatPath(cu, service);

        cu.becomeWorkingCopy(new NullProgressMonitor());
        try {
            cu.getBuffer().setContents(newSource);
            cu.commitWorkingCopy(true, new NullProgressMonitor());
        } finally {
            cu.discardWorkingCopy();
        }

        // Re-read post-commit: the committed form is authoritative (commit
        // may normalise content), and the undo edit must cover ITS length.
        String finalSource = cu.getSource();

        String undoChangeId = null;
        if (cu.getResource() instanceof IFile file) {
            TextFileChange undo = new TextFileChange("undo: " + label, file);
            undo.setEdit(new ReplaceEdit(0, finalSource.length(), oldSource));
            undoChangeId = cache.put(RefactoringChangeCache.Kind.UNDO, undo,
                "undo: " + label, "", List.of(filePath));
        }

        return new Committed(undoChangeId, DiffRenderer.unifiedDiff(filePath, oldSource, finalSource), filePath);
    }

    /**
     * Stage {@code newSource} as a full-source replace change without
     * touching the file — committed later via {@code apply_refactoring}.
     */
    public static Staged stageFullReplace(ICompilationUnit cu, String newSource, String label,
                                          RefactoringChangeCache cache, IJdtService service)
            throws Exception {
        String oldSource = cu.getSource();
        String filePath = formatPath(cu, service);
        String diff = DiffRenderer.unifiedDiff(filePath, oldSource, newSource);

        IFile file = (IFile) cu.getResource();
        TextFileChange staged = new TextFileChange(label, file);
        staged.setEdit(new ReplaceEdit(0, oldSource.length(), newSource));
        String changeId = cache.put(RefactoringChangeCache.Kind.STAGED, staged,
            label, diff, List.of(filePath));

        return new Staged(changeId, diff, filePath);
    }

    private static String formatPath(ICompilationUnit cu, IJdtService service) {
        try {
            java.nio.file.Path absolute = cu.getResource().getLocation().toFile().toPath();
            if (service != null) {
                return service.getPathUtils().formatPath(absolute);
            }
            return absolute.toString();
        } catch (RuntimeException e) {
            return cu.getElementName();
        }
    }
}
