package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 14b — reverses a previously applied refactoring via its cached
 * undo-Change. One-shot: the undo handle is consumed.
 */
public class UndoRefactoringTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(UndoRefactoringTool.class);

    private final RefactoringChangeCache changeCache;

    public UndoRefactoringTool(Supplier<IJdtService> serviceSupplier,
                               RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "undo_refactoring";
    }

    @Override
    public String getDescription() {
        return "Reverse a previously applied refactoring. "
            + "USAGE: undo_refactoring(undoChangeId=\"<uuid from the refactor tool's response>\") "
            + "OUTPUT: { filesModified } — the restored files. "
            + "WORKFLOW: refactor → compile_workspace / run tests → if broken, "
            + "undo_refactoring(undoChangeId) restores the pre-refactor state. "
            + "Undo handles expire after 1 hour and are consumed by this call (one-shot). "
            + "Undo in reverse order when reverting a sequence of refactorings.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "undoChangeId", Map.of(
                    "type", "string",
                    "description", "Undo handle returned by an applied refactor tool or apply_refactoring."
                )
            ),
            "required", List.of("undoChangeId")
        );
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse missing = requireParam(arguments, "undoChangeId");
        if (missing != null) {
            return missing;
        }
        String undoChangeId = getStringParam(arguments, "undoChangeId");

        Optional<RefactoringChangeCache.Entry> entry =
            changeCache.take(undoChangeId, RefactoringChangeCache.Kind.UNDO);
        if (entry.isEmpty()) {
            return ToolResponse.invalidParameter("undoChangeId",
                "No undo handle with id '" + undoChangeId + "'. It may have expired (1 h TTL), "
                    + "already been used, or the id belongs to a staged change.");
        }

        ChangeEngine.ApplyOutcome outcome = ChangeEngine.perform(entry.get().change(), service);
        if (outcome.validationError() != null) {
            return ToolResponse.error(
                "REFACTORING_FAILED",
                "undo_refactoring failed: " + outcome.validationError(),
                "The workspace may have changed since the refactoring was applied "
                    + "(undo is only valid while the touched files are otherwise unmodified). "
                    + "Restore manually from the diff in the original response.");
        }

        // The undo of an undo (a redo handle) is intentionally not exposed —
        // dispose it rather than leak the LTK buffers it holds.
        if (outcome.undoChange() != null) {
            try {
                outcome.undoChange().dispose();
            } catch (RuntimeException e) {
                log.debug("Redo-change dispose failed: {}", e.getMessage());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "undo_refactoring");
        data.put("filesModified", outcome.modifiedFilePaths());
        data.put("summary", entry.get().summary());
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(outcome.modifiedFilePaths().size())
            .returnedCount(outcome.modifiedFilePaths().size())
            .suggestedNextTools(List.of("compile_workspace to confirm the restored state"))
            .build());
    }
}
