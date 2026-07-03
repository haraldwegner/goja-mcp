package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 22 (MCP injector) — workflow-collapse. Folds the three-call
 * "understand this symbol" flow (definition + symbol info + references) into a
 * single {@code analyze(kind=symbol)} result, so the agent never hops back to
 * the loop mid-flow — every hop is a chance to fall back to grep.
 *
 * <p>Reuses the narrow delegates verbatim ({@link GoToDefinitionTool},
 * {@link GetSymbolInfoTool}, {@link FindReferencesTool}) rather than re-querying
 * JDT. Best-effort merge: each section is the exact payload of its delegate
 * (parity), omitted when that delegate errors; references are capped at
 * {@link #REFERENCE_CAP} to bound the payload. The whole call fails ONLY when
 * the position resolves to nothing (all three empty) — then the definition
 * lookup's error is returned (it carries the {@code search_symbols} hint).</p>
 *
 * <p>Surfaced only as a kind on the {@code analyze} front door; NOT registered
 * as a standalone MCP tool, so the tool surface stays unchanged.</p>
 */
public class AnalyzeSymbolTool extends AbstractTool {

    /** Reference cap — keeps the composed payload bounded. */
    static final int REFERENCE_CAP = 20;

    private final GoToDefinitionTool definition;
    private final GetSymbolInfoTool symbol;
    private final FindReferencesTool references;

    public AnalyzeSymbolTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.definition = new GoToDefinitionTool(serviceSupplier);
        this.symbol = new GetSymbolInfoTool(serviceSupplier);
        this.references = new FindReferencesTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_symbol";
    }

    @Override
    public String getDescription() {
        return """
            Understand the symbol at a position in one call: definition + symbol info + references.
            Composes go_to_definition, get_at_position(kind=symbol) and find_references.
            Needs: filePath, line, column (ZERO-BASED). Surfaced as analyze(kind=symbol).
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string", "description", "Path to source file."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line number."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column number."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse def = definition.executeWithService(service, arguments);
        ToolResponse sym = symbol.executeWithService(service, arguments);
        ToolResponse refs = references.executeWithService(service, withReferenceCap(arguments));

        // Fail only if the position resolves to nothing at all — then surface the
        // definition lookup's error (it carries the search_symbols recovery hint).
        if (!def.isSuccess() && !sym.isSuccess() && !refs.isSuccess()) {
            return def;
        }

        Map<String, Object> composed = new LinkedHashMap<>();
        if (def.isSuccess()) {
            composed.put("definition", def.getData());
        }
        if (sym.isSuccess()) {
            composed.put("symbol", sym.getData());
        }
        if (refs.isSuccess()) {
            composed.put("references", refs.getData());
        }

        return ToolResponse.success(composed, ResponseMeta.builder()
            .suggestedNextTools(List.of(
                "rename_symbol to rename across all references",
                "get_call_hierarchy to trace callers/callees",
                "refactoring(action=plan) for a structural change"))
            .build());
    }

    /** A copy of the args with the reference search capped, bounding the payload. */
    private JsonNode withReferenceCap(JsonNode arguments) {
        ObjectNode copy = (ObjectNode) arguments.deepCopy();
        copy.put("maxResults", REFERENCE_CAP);
        return copy;
    }
}
