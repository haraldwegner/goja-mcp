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
 * Sprint 14b — minimal LTK Change that creates a new file. LTK ships
 * public delete/move/rename resource changes but no public create; this
 * fills the gap for tools that produce new compilation units
 * (extract_interface). Undo is a {@link DeleteResourceChange}.
 */
public final class CreateFileChange extends Change {

    private final IFile file;
    private final String contents;

    public CreateFileChange(IFile file, String contents) {
        this.file = file;
        this.contents = contents;
    }

    @Override
    public String getName() {
        return "create " + file.getName();
    }

    @Override
    public void initializeValidationData(IProgressMonitor pm) {
        // Nothing to capture — validity is the non-existence check below.
    }

    @Override
    public RefactoringStatus isValid(IProgressMonitor pm) {
        if (file.exists()) {
            return RefactoringStatus.createFatalErrorStatus(
                "File already exists: " + file.getFullPath());
        }
        return new RefactoringStatus();
    }

    @Override
    public Change perform(IProgressMonitor pm) throws CoreException {
        file.create(new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8)),
            false, pm);
        return new DeleteResourceChange(file.getFullPath(), true);
    }

    @Override
    public Object getModifiedElement() {
        return file;
    }
}
