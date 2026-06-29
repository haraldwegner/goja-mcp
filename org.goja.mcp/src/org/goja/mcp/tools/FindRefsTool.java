package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A (v1.1.1) — parametric reference/navigation front door, collapsing
 * find_references / find_implementations / find_method_references by {@code kind}.
 * Read-only; delegates self-validate their position/symbol inputs.
 */
public class FindRefsTool extends AbstractTool {

    private static final List<String> KINDS = List.of("references", "implementations", "method_references");

    private final FindReferencesTool references;
    private final FindImplementationsTool implementations;
    private final FindMethodReferencesTool methodReferences;

    public FindRefsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.references = new FindReferencesTool(serviceSupplier);
        this.implementations = new FindImplementationsTool(serviceSupplier);
        this.methodReferences = new FindMethodReferencesTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_references";
    }

    @Override
    public String getDescription() {
        return """
            Find references to a symbol. One front door over the reference searches.

            USAGE: find_references(kind="<kind>", ...)

            - references        — all references to the symbol at a position (default).
            - implementations   — implementations/subtypes of the type/method at a position.
            - method_references — call sites of the method (by FQN/signature).

            Inputs follow the underlying search (filePath + ZERO-BASED line/column, or a
            symbol/method query). Requires load_project to be called first.
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
        kind.put("description", "Which reference search to run.");
        properties.put("kind", kind);
        properties.put("filePath", Map.of("type", "string", "description", "Source file of the symbol."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line of the symbol."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));
        properties.put("query", Map.of("type", "string", "description", "method_references: method FQN/signature query."));
        properties.put("maxResults", Map.of("type", "integer", "description", "Optional result cap."));
        schema.put("properties", properties);
        schema.put("required", List.of("kind"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + KINDS);
        }
        return switch (kind) {
            case "references"        -> references.executeWithService(service, arguments);
            case "implementations"   -> implementations.executeWithService(service, arguments);
            case "method_references" -> methodReferences.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }
}
