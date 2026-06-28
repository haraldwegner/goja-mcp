package org.goja.mcp.refactoring;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.MultiTextEdit;
import org.goja.core.IJdtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 14b — the shared perform/preview engine behind the refactoring
 * apply contract. Tools build a {@link Change}; this class previews its diff
 * (without applying), performs it (capturing the undo-Change), and reports
 * the touched files.
 *
 * <p>JDT-LTK supplies the heavy machinery; this is orchestration only.</p>
 */
public final class ChangeEngine {

    private static final Logger log = LoggerFactory.getLogger(ChangeEngine.class);

    private ChangeEngine() {}

    /**
     * Result of {@link #perform}. {@code validationError} is non-null when
     * LTK rejected the change — nothing was modified in that case and
     * {@code undoChange} is null.
     */
    public record ApplyOutcome(
        Change undoChange,
        List<String> modifiedFilePaths,
        String validationError
    ) {}

    /**
     * Render a unified diff of what the change WOULD do, without applying.
     * Covers every {@link TextChange} in the tree via
     * {@code getCurrentContent} / {@code getPreviewContent}; non-text changes
     * (file moves, renames) contribute no diff text — they still surface in
     * the modified-file list after apply.
     */
    public static String previewDiff(Change change, IJdtService service) {
        List<DiffRenderer.FileDiff> diffs = new ArrayList<>();
        collectTextDiffs(change, service, diffs);
        return DiffRenderer.unifiedDiff(diffs);
    }

    private static void collectTextDiffs(Change change, IJdtService service,
                                         List<DiffRenderer.FileDiff> sink) {
        if (change == null) {
            return;
        }
        if (change instanceof TextChange textChange) {
            try {
                String oldContent = textChange.getCurrentContent(new NullProgressMonitor());
                String newContent = textChange.getPreviewContent(new NullProgressMonitor());
                sink.add(new DiffRenderer.FileDiff(
                    diffPathOf(textChange, service), oldContent, newContent));
            } catch (CoreException e) {
                log.debug("Preview failed for '{}': {}", change.getName(), e.getMessage());
            }
        }
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collectTextDiffs(child, service, sink);
            }
        }
    }

    /**
     * Wrap per-file JDT text edits into a performable Change: one
     * {@link TextFileChange} per file (edits under a {@link MultiTextEdit}
     * root), composed into a {@link CompositeChange} when more than one file
     * is touched. The standard bridge for tools that compute raw
     * {@link org.eclipse.text.edits.TextEdit}s (Stage A3 migrations).
     */
    public static Change fromFileEdits(String changeName,
                                       Map<IFile, List<org.eclipse.text.edits.TextEdit>> editsByFile) {
        List<TextFileChange> fileChanges = new ArrayList<>();
        for (Map.Entry<IFile, List<org.eclipse.text.edits.TextEdit>> entry : editsByFile.entrySet()) {
            TextFileChange fileChange =
                new TextFileChange(changeName + ": " + entry.getKey().getName(), entry.getKey());
            MultiTextEdit root = new MultiTextEdit();
            for (org.eclipse.text.edits.TextEdit edit : entry.getValue()) {
                root.addChild(edit);
            }
            fileChange.setEdit(root);
            fileChanges.add(fileChange);
        }
        if (fileChanges.size() == 1) {
            return fileChanges.get(0);
        }
        CompositeChange composite = new CompositeChange(changeName);
        for (TextFileChange fileChange : fileChanges) {
            composite.add(fileChange);
        }
        return composite;
    }

    /** Files a change will touch, derived from the change tree (pre-apply). */
    public static List<String> affectedFilePaths(Change change, IJdtService service) {
        Set<String> paths = new LinkedHashSet<>();
        collectAffectedPaths(change, service, paths);
        return new ArrayList<>(paths);
    }

    private static void collectAffectedPaths(Change change, IJdtService service, Set<String> sink) {
        if (change == null) {
            return;
        }
        Object modified = change.getModifiedElement();
        if (change instanceof TextFileChange tfc) {
            sink.add(formatPath(tfc.getFile(), service));
        } else if (modified instanceof IFile file) {
            sink.add(formatPath(file, service));
        } else if (modified instanceof ICompilationUnit cu && cu.getResource() instanceof IFile file) {
            sink.add(formatPath(file, service));
        }
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collectAffectedPaths(child, service, sink);
            }
        }
    }

    /**
     * Perform the change against the workspace and capture its undo-Change.
     *
     * <p>Modified files are observed via a workspace resource listener — some
     * LTK changes (ProcessorChange) expose no children, so walking the tree
     * after perform misses their effects.</p>
     */
    public static ApplyOutcome perform(Change change, IJdtService service) {
        try {
            change.initializeValidationData(new NullProgressMonitor());

            Set<String> modifiedFilePaths = new LinkedHashSet<>();
            IResourceChangeListener listener = event -> {
                IResourceDelta delta = event.getDelta();
                if (delta == null) {
                    return;
                }
                try {
                    delta.accept(d -> {
                        IResource resource = d.getResource();
                        if (resource instanceof IFile file) {
                            modifiedFilePaths.add(formatPath(file, service));
                        }
                        return true;
                    });
                } catch (CoreException ignore) {
                    // Best-effort: a delta-walk failure must not fail the refactor.
                }
            };

            ResourcesPlugin.getWorkspace().addResourceChangeListener(
                listener, IResourceChangeEvent.POST_CHANGE);
            PerformChangeOperation operation = new PerformChangeOperation(change);
            try {
                operation.run(new NullProgressMonitor());
            } finally {
                ResourcesPlugin.getWorkspace().removeResourceChangeListener(listener);
            }

            RefactoringStatus validation = operation.getValidationStatus();
            if (validation != null && (validation.hasFatalError() || validation.hasError())) {
                return new ApplyOutcome(null, List.of(),
                    validation.getMessageMatchingSeverity(validation.getSeverity()));
            }

            Change undoChange = operation.getUndoChange();
            return new ApplyOutcome(undoChange, new ArrayList<>(modifiedFilePaths), null);
        } catch (CoreException e) {
            log.warn("Change.perform failed for '{}': {}", change.getName(), e.getMessage(), e);
            return new ApplyOutcome(null, List.of(), e.getMessage());
        }
    }

    private static String diffPathOf(TextChange textChange, IJdtService service) {
        if (textChange instanceof TextFileChange tfc) {
            return formatPath(tfc.getFile(), service);
        }
        Object modified = textChange.getModifiedElement();
        if (modified instanceof IFile file) {
            return formatPath(file, service);
        }
        if (modified instanceof ICompilationUnit cu && cu.getResource() instanceof IFile file) {
            return formatPath(file, service);
        }
        return textChange.getName();
    }

    private static String formatPath(IFile file, IJdtService service) {
        try {
            java.nio.file.Path absolute = file.getLocation().toFile().toPath();
            if (service != null) {
                return service.getPathUtils().formatPath(absolute);
            }
            return absolute.toString();
        } catch (RuntimeException e) {
            return file.getName();
        }
    }
}
