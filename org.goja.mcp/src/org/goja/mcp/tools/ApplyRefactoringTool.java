package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.RefactoringChangeCache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 14b — commits a Change previously staged with
 * {@code auto_apply: false}. One-shot: the staged entry is consumed.
 */
public class ApplyRefactoringTool extends AbstractTool {

    private final RefactoringChangeCache changeCache;

    public ApplyRefactoringTool(Supplier<IJdtService> serviceSupplier,
                                RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "apply_refactoring";
    }

    @Override
    public String getDescription() {
        return "Commit a refactoring Change that was staged with auto_apply: false. "
            + "USAGE: apply_refactoring(changeId=\"<uuid from the staging call>\") "
            + "OUTPUT: { filesModified, undoChangeId, summary } — files are written to disk. "
            + "WORKFLOW: 1. Call a refactor tool with auto_apply: false to stage and get a changeId "
            + "2. Optionally inspect_refactoring(changeId) to review the diff "
            + "3. apply_refactoring(changeId) to commit "
            + "4. compile_workspace to verify; undo_refactoring(undoChangeId) to revert if broken. "
            + "Staged changes expire after 1 hour and are consumed by this call (one-shot).";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "changeId", Map.of(
                    "type", "string",
                    "description", "Id returned by a refactor tool called with auto_apply: false."
                )
            ),
            "required", List.of("changeId")
        );
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse missing = requireParam(arguments, "changeId");
        if (missing != null) {
            return missing;
        }
        String changeId = getStringParam(arguments, "changeId");

        Optional<RefactoringChangeCache.Entry> entry =
            changeCache.take(changeId, RefactoringChangeCache.Kind.STAGED);
        if (entry.isEmpty()) {
            return ToolResponse.invalidParameter("changeId",
                "No staged change with id '" + changeId + "'. It may have expired (1 h TTL), "
                    + "already been applied, or the id belongs to an undo handle. "
                    + "Re-run the originating refactor tool with auto_apply: false to re-stage.");
        }

        ChangeEngine.ApplyOutcome outcome = ChangeEngine.perform(entry.get().change(), service);
        if (outcome.validationError() != null) {
            return ToolResponse.error(
                "REFACTORING_FAILED",
                "apply_refactoring failed: " + outcome.validationError(),
                "No files were modified. The staged change has been consumed — re-run the "
                    + "originating refactor tool to rebuild it.");
        }

        String undoChangeId = null;
        if (outcome.undoChange() != null) {
            undoChangeId = changeCache.put(
                RefactoringChangeCache.Kind.UNDO, outcome.undoChange(),
                "undo: " + entry.get().summary(), "", outcome.modifiedFilePaths());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "apply_refactoring");
        data.put("filesModified", outcome.modifiedFilePaths());
        data.put("undoChangeId", undoChangeId);
        data.put("summary", entry.get().summary());
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(outcome.modifiedFilePaths().size())
            .returnedCount(outcome.modifiedFilePaths().size())
            .suggestedNextTools(List.of(
                "compile_workspace to verify the refactoring",
                "undo_refactoring with the undoChangeId if verification fails"))
            .build());
    }
}
