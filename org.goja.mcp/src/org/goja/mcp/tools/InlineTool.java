package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A — parametric front door for inline-method / inline-variable.
 * Both delegates take the identical {@code {filePath, line, column}} input, so
 * this is a clean uniform collapse selected by {@code kind}.
 *
 * <p>Replaces {@code inline_method} / {@code inline_variable}; apply/undo
 * contract unchanged.</p>
 */
public class InlineTool extends AbstractTool {

    private static final List<String> KINDS = List.of("method", "variable");

    private final InlineMethodTool method;
    private final InlineVariableTool variable;

    public InlineTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.method = new InlineMethodTool(serviceSupplier, cache);
        this.variable = new InlineVariableTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "inline";
    }

    @Override
    public String getDescription() {
        return """
            Inline a method or a local variable at a caret (behaviour-preserving, reversible).

            USAGE: inline(kind="<method|variable>", filePath=..., line=..., column=...)

            - method   — inline all call sites of the method at the position.
            - variable — replace uses of the local variable at the position with its initializer.

            IMPORTANT: ZERO-BASED coordinates. Applies by default; returns
            filesModified/diff/undoChangeId/summary. Pass auto_apply=false to stage only.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", KINDS);
        kind.put("description", "Inline a method or a local variable.");
        properties.put("kind", kind);
        properties.put("filePath", Map.of("type", "string", "description", "Path to source file."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line of the symbol to inline."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));

        schema.put("properties", properties);
        schema.put("required", List.of("kind", "filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + KINDS);
        }
        return switch (kind) {
            case "method"   -> method.executeWithService(service, arguments);
            case "variable" -> variable.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }
}
