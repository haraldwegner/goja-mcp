package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;

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

            - references        — all references to the symbol at a position or FQN.
            - implementations   — implementations/subtypes of the type/method.
            - method_references — Foo::bar method-reference EXPRESSIONS of the method
                                  (the `::` syntax only — call sites live in
                                  get_call_hierarchy(direction=incoming)).

            Two invocation forms, every kind:
            (a) position — filePath + ZERO-BASED line/column on the symbol.
            (b) FQN      — symbol = "com.foo.Bar" | "com.foo.Bar#member" |
                           "com.foo.Bar#method(int,java.lang.String)";
                           optional scope = workspace (default) | project.
            `query` is accepted as an alias for `symbol` (v2.8.1 back-compat).

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
        kind.put("description", "Which reference search to run.");
        properties.put("kind", kind);
        properties.put("filePath", Map.of("type", "string", "description", "Source file of the symbol."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line of the symbol."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));
        properties.put("symbol", Map.of("type", "string", "description",
            "FQN form: 'com.foo.Bar' (type), 'com.foo.Bar#member' (method any overload / field), or 'com.foo.Bar#method(int,java.lang.String)' (specific overload)."));
        properties.put("scope", Map.of("type", "string", "enum", List.of("workspace", "project"),
            "description", "FQN-form scope: 'workspace' (default) or 'project' (requires projectKey)."));
        properties.put("query", Map.of("type", "string", "description",
            "Alias for 'symbol' (kept for back-compat with the pre-v2.8.1 description)."));
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
        // v2.8.1 (dogfood 2026-07-11): the pre-2.8.1 description advertised
        // `query`, but no delegate consumed it — honor it as a symbol alias.
        // Explicit `symbol` wins on conflict.
        String symbol = getStringParam(arguments, "symbol");
        String query = getStringParam(arguments, "query");
        if ((symbol == null || symbol.isBlank()) && query != null && !query.isBlank()
                && arguments instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
            obj.put("symbol", query);
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
