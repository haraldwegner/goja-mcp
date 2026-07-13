package org.jawata.mcp.refactoring;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.jawata.core.IJdtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Sprint 19 (Kerievsky) — runs a <b>dependent</b> refactoring recipe: a sequence
 * of steps where each step's edit shifts the ground under the next, so the steps
 * cannot be pre-computed as one {@link org.eclipse.ltk.core.refactoring.Change}.
 * Each {@link Step} is evaluated lazily against the <em>current</em> workspace
 * (apply-reparse): the engine performs it, captures its undo-{@link Change}, and
 * moves on. All the per-step undos are wrapped, reverse-order, into one
 * {@link CompositeChange} so the whole recipe reverts with a single undo handle.
 *
 * <p>Atomic: if any step fails to build or validate, every already-applied step is
 * rolled back before returning an error — the workspace is left as it was found.
 * (Single-shot recipes — independent edits computable against the original AST —
 * do NOT need this; they build one {@link ChangeEngine#fromFileEdits} change.)</p>
 */
public final class RecipeEngine {

    private static final Logger log = LoggerFactory.getLogger(RecipeEngine.class);

    private RecipeEngine() {
    }

    /** One recipe step, built against the current (post-previous-step) workspace state. */
    @FunctionalInterface
    public interface Step {
        /** @return the change to perform, or {@code null} to abort the recipe. */
        Change build() throws Exception;
    }

    /** Outcome of a recipe run. On failure {@code compositeUndo} is null and the workspace is restored. */
    public record Result(boolean ok, List<String> modifiedFilePaths, Change compositeUndo, String error) {
    }

    public static Result run(String name, List<Step> steps, IJdtService service) {
        List<Change> undos = new ArrayList<>();
        LinkedHashSet<String> modified = new LinkedHashSet<>();
        for (int i = 0; i < steps.size(); i++) {
            Change change;
            try {
                change = steps.get(i).build();
            } catch (Exception e) {
                rollback(undos, service);
                return new Result(false, List.of(), null, "step " + (i + 1) + ": " + e.getMessage());
            }
            if (change == null) {
                rollback(undos, service);
                return new Result(false, List.of(), null, "step " + (i + 1) + ": could not build change");
            }
            ChangeEngine.ApplyOutcome outcome = ChangeEngine.perform(change, service);
            if (outcome.validationError() != null) {
                rollback(undos, service);
                return new Result(false, List.of(), null, "step " + (i + 1) + ": " + outcome.validationError());
            }
            if (outcome.undoChange() != null) {
                undos.add(outcome.undoChange());
            }
            modified.addAll(outcome.modifiedFilePaths());
            // Stale-buffer race (Sprint 23 Stage 14): perform() writes through the
            // LTK file buffer, but the JDT Openable buffer of the modified unit is
            // only invalidated when the workspace delta is processed — which can
            // lose the race against the NEXT step's prepare. A stale parse then
            // computes insertion offsets against the pre-step document and lands
            // mid-token in the new one (~1-in-3 on compose_method). Close the
            // modified units so the next step re-reads current content.
            closeModifiedUnits(change);
        }
        CompositeChange compositeUndo = new CompositeChange(name + " (undo)");
        for (int i = undos.size() - 1; i >= 0; i--) {
            compositeUndo.add(undos.get(i));
        }
        return new Result(true, new ArrayList<>(modified), compositeUndo, null);
    }

    /** Discard the JDT buffers of every compilation unit the change touched. */
    private static void closeModifiedUnits(Change change) {
        IFile file = null;
        if (change instanceof TextFileChange tfc) {
            file = tfc.getFile();
        } else if (change.getModifiedElement() instanceof IFile f) {
            file = f;
        } else if (change.getModifiedElement() instanceof ICompilationUnit cu
                && cu.getResource() instanceof IFile f) {
            file = f;
        }
        if (file != null) {
            try {
                ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
                if (cu != null && cu.isOpen() && !cu.isWorkingCopy()) {
                    cu.close();
                }
            } catch (Exception e) {
                log.debug("Buffer close after recipe step failed for {}: {}", file, e.getMessage());
            }
        }
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                closeModifiedUnits(child);
            }
        }
    }

    /** Best-effort restore: perform the captured undos in reverse application order. */
    private static void rollback(List<Change> undos, IJdtService service) {
        for (int i = undos.size() - 1; i >= 0; i--) {
            try {
                ChangeEngine.perform(undos.get(i), service);
            } catch (RuntimeException e) {
                log.warn("Recipe rollback step failed: {}", e.getMessage());
            }
        }
    }
}
