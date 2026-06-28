package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 14b — returns the cached diff/summary of a staged change (or an
 * undo handle) WITHOUT applying anything. Non-consuming.
 */
public class InspectRefactoringTool extends AbstractTool {

    private final RefactoringChangeCache changeCache;

    public InspectRefactoringTool(Supplier<IJdtService> serviceSupplier,
                                  RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "inspect_refactoring";
    }

    @Override
    public String getDescription() {
        return "Inspect a cached refactoring change without applying it. "
            + "USAGE: inspect_refactoring(changeId=\"<staged changeId or undoChangeId>\") "
            + "OUTPUT: { kind, summary, diff, filePaths } — kind is STAGED or UNDO. "
            + "WORKFLOW: stage a refactor with auto_apply: false, inspect the diff here, "
            + "then apply_refactoring(changeId) to commit. Non-consuming: the cached "
            + "change stays available. No files are modified by this call.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "changeId", Map.of(
                    "type", "string",
                    "description", "A staged changeId or an undoChangeId."
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

        Optional<RefactoringChangeCache.Entry> entry = changeCache.peek(changeId);
        if (entry.isEmpty()) {
            return ToolResponse.invalidParameter("changeId",
                "No cached change with id '" + changeId + "'. It may have expired (1 h TTL) "
                    + "or already been consumed by apply_refactoring / undo_refactoring.");
        }

        RefactoringChangeCache.Entry cached = entry.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "inspect_refactoring");
        data.put("changeId", cached.id());
        data.put("kind", cached.kind().name());
        data.put("summary", cached.summary());
        data.put("diff", cached.diff());
        data.put("filePaths", cached.filePaths());
        return ToolResponse.success(data, ResponseMeta.builder()
            .suggestedNextTools(List.of(
                "apply_refactoring to commit a STAGED change",
                "undo_refactoring to execute an UNDO handle"))
            .build());
    }
}
