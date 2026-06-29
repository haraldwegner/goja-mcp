package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A (v1.1.1) — quick-fix front door, collapsing suggest_imports /
 * get_quick_fixes / apply_quick_fix by {@code action}. Not read-only (apply
 * mutates; suggest/list read).
 */
public class QuickFixTool extends AbstractTool {

    private static final List<String> ACTIONS = List.of("suggest_imports", "list", "apply");

    private final SuggestImportsTool suggestImports;
    private final GetQuickFixesTool list;
    private final ApplyQuickFixTool apply;

    public QuickFixTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.suggestImports = new SuggestImportsTool(serviceSupplier);
        this.list = new GetQuickFixesTool(serviceSupplier);
        this.apply = new ApplyQuickFixTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "quick_fix";
    }

    @Override
    public String getDescription() {
        return """
            Quick-fix discovery and application.

            USAGE: quick_fix(action="<action>", ...)

            - suggest_imports — candidate imports for an unresolved type. Needs: typeName.
            - list            — available quick-fixes for the diagnostics in a file. Needs: filePath.
            - apply           — apply a specific quick-fix. Needs: filePath + the fix selector.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", ACTIONS);
        action.put("description", "Which quick-fix action to run.");
        properties.put("action", action);
        properties.put("typeName", Map.of("type", "string", "description", "suggest_imports: the unresolved type."));
        properties.put("filePath", Map.of("type", "string", "description", "list/apply: the source file."));
        properties.put("line", Map.of("type", "integer", "description", "list/apply: zero-based line of the diagnostic."));
        properties.put("column", Map.of("type", "integer", "description", "list/apply: zero-based column."));
        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String action = getStringParam(arguments, "action");
        if (action == null || action.isBlank()) {
            return ToolResponse.invalidParameter("action", "action is required; one of " + ACTIONS);
        }
        return switch (action) {
            case "suggest_imports" -> suggestImports.executeWithService(service, arguments);
            case "list"            -> list.executeWithService(service, arguments);
            case "apply"           -> apply.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("action",
                "Unknown action '" + action + "'. Allowed: " + ACTIONS);
        };
    }
}
