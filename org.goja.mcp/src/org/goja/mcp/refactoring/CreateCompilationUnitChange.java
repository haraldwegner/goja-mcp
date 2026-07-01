package org.goja.mcp.refactoring;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.resource.DeleteResourceChange;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Sprint 19 (Kerievsky) — an LTK {@link Change} that CREATES a new source file,
 * the missing piece for "toward pattern" transforms that introduce a new type
 * (enum / State / Command / Visitor classes). On {@link #perform} it writes the
 * file and returns a public {@link DeleteResourceChange} as its undo, so the
 * creation reverts through the normal {@code undo_refactoring} path. Composes into
 * a {@link org.eclipse.ltk.core.refactoring.CompositeChange} alongside
 * {@link org.eclipse.ltk.core.refactoring.TextFileChange}s when a transform both
 * edits existing files and adds new ones.
 *
 * <p>The parent folder must already exist (same-package creation always satisfies
 * this). {@link ChangeEngine#previewDiff} shows no text for a create (it is not a
 * {@code TextChange}); the new file still surfaces in the modified-file list.</p>
 */
public final class CreateCompilationUnitChange extends Change {

    private final IFile file;
    private final String content;

    public CreateCompilationUnitChange(IFile file, String content) {
        this.file = file;
        this.content = content;
    }

    @Override
    public String getName() {
        return "Create " + file.getName();
    }

    @Override
    public void initializeValidationData(IProgressMonitor pm) {
        // no cached state to validate
    }

    @Override
    public RefactoringStatus isValid(IProgressMonitor pm) {
        return file.exists()
            ? RefactoringStatus.createFatalErrorStatus("File already exists: " + file.getFullPath())
            : new RefactoringStatus();
    }

    @Override
    public Object getModifiedElement() {
        return file;
    }

    @Override
    public Change perform(IProgressMonitor pm) throws CoreException {
        file.create(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true, pm);
        return new DeleteResourceChange(file.getFullPath(), true);
    }
}
