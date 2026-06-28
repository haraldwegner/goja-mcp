package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.ltk.core.refactoring.Change;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 14b — base class implementing the refactoring-tool contract
 * (see {@code docs/sprints/sprint-14b-refactoring-full-apply.md}):
 *
 * <ol>
 *   <li>Subclass builds a JDT {@link Change} in {@link #prepareChange}.</li>
 *   <li>Default ({@code auto_apply: true}): the change is performed, its
 *       undo-Change cached, and the response carries
 *       {@code { filesModified, diff, undoChangeId, summary }}.</li>
 *   <li>{@code auto_apply: false}: the un-performed change is cached and the
 *       response carries {@code { changeId, diff, summary }} for a later
 *       {@code apply_refactoring(changeId)}.</li>
 * </ol>
 *
 * <p>New refactoring tools MUST extend this class (or implement the same
 * contract) — returning raw text edits for hand-application is the pattern
 * this sprint eliminates. PR-review gate.</p>
 */
public abstract class AbstractApplyingRefactoringTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AbstractApplyingRefactoringTool.class);

    protected final RefactoringChangeCache changeCache;

    protected AbstractApplyingRefactoringTool(Supplier<IJdtService> serviceSupplier,
                                              RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    /**
     * Either a built change ready for the apply pipeline, or an error
     * response the tool returns verbatim (invalid params, symbol not found…).
     */
    protected static final class Preparation {
        final Change change;
        final String summary;
        final ToolResponse error;
        final Map<String, Object> extras;

        private Preparation(Change change, String summary, ToolResponse error,
                            Map<String, Object> extras) {
            this.change = change;
            this.summary = summary;
            this.error = error;
            this.extras = extras;
        }

        public static Preparation of(Change change, String summary) {
            return new Preparation(change, summary, null, Map.of());
        }

        /**
         * Variant with tool-specific context fields (e.g. oldName/newName/
         * symbolKind) merged into the response alongside the uniform
         * contract keys — extras never overwrite contract keys.
         */
        public static Preparation of(Change change, String summary, Map<String, Object> extras) {
            return new Preparation(change, summary, null,
                extras == null ? Map.of() : extras);
        }

        public static Preparation fail(ToolResponse error) {
            return new Preparation(null, null, error, Map.of());
        }
    }

    /**
     * Build the change for this tool's arguments. Implementations validate
     * inputs and return {@link Preparation#fail} for anything that should
     * short-circuit; they never perform the change themselves.
     */
    protected abstract Preparation prepareChange(IJdtService service, JsonNode arguments)
        throws Exception;

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        boolean autoApply = getBooleanParam(arguments, "auto_apply", true);
        Preparation preparation;
        try {
            preparation = prepareChange(service, arguments);
        } catch (Exception e) {
            log.warn("{} prepareChange threw: {}", getName(), e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
        if (preparation.error != null) {
            return preparation.error;
        }

        Change change = preparation.change;
        String summary = preparation.summary;
        String diff = ChangeEngine.previewDiff(change, service);

        if (!autoApply) {
            List<String> files = ChangeEngine.affectedFilePaths(change, service);
            String changeId = changeCache.put(
                RefactoringChangeCache.Kind.STAGED, change, summary, diff, files);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", getName());
            data.put("applied", false);
            data.put("changeId", changeId);
            data.put("diff", diff);
            data.put("summary", summary);
            preparation.extras.forEach(data::putIfAbsent);
            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "apply_refactoring with this changeId to commit the staged change",
                    "inspect_refactoring with this changeId to re-examine the diff"))
                .build());
        }

        ChangeEngine.ApplyOutcome outcome = ChangeEngine.perform(change, service);
        if (outcome.validationError() != null) {
            return ToolResponse.error(
                "REFACTORING_FAILED",
                getName() + " failed: " + outcome.validationError(),
                "No files were modified. Adjust the input or fix the workspace state and retry.");
        }

        String undoChangeId = null;
        if (outcome.undoChange() != null) {
            undoChangeId = changeCache.put(
                RefactoringChangeCache.Kind.UNDO, outcome.undoChange(),
                "undo: " + summary, "", outcome.modifiedFilePaths());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", getName());
        data.put("applied", true);
        data.put("filesModified", outcome.modifiedFilePaths());
        data.put("diff", diff);
        data.put("undoChangeId", undoChangeId);
        data.put("summary", summary);
        preparation.extras.forEach(data::putIfAbsent);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(outcome.modifiedFilePaths().size())
            .returnedCount(outcome.modifiedFilePaths().size())
            .suggestedNextTools(List.of(
                "compile_workspace to verify the refactoring",
                "undo_refactoring with the undoChangeId if verification fails"))
            .build());
    }

}
