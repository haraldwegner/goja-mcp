package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A (v1.1.1) — parametric front door over the nine read-only
 * {@code analyze_*} tools. Flat schema + per-kind docs; the narrow delegates
 * self-validate their own required params, so this only dispatches.
 *
 * <p>Collision: {@code analyze_javadocs}/{@code analyze_naming}/
 * {@code analyze_nullness} each read a param literally named {@code kind} (their
 * analysis variant). Our discriminator already owns {@code kind}, so those
 * variants are passed as {@code subkind} and remapped on dispatch.</p>
 */
public class AnalyzeTool extends AbstractTool {

    private static final List<String> KINDS = List.of(
        "file", "type", "method", "javadocs", "naming", "nullness",
        "change_impact", "control_flow", "data_flow", "symbol");

    /** Kinds whose delegate reads its own {@code kind} param (aliased to subkind). */
    private static final List<String> SUBKIND_KINDS = List.of("javadocs", "naming", "nullness");

    private final AnalyzeFileTool file;
    private final AnalyzeTypeTool type;
    private final AnalyzeMethodTool method;
    private final AnalyzeJavadocsTool javadocs;
    private final AnalyzeNamingTool naming;
    private final AnalyzeNullnessTool nullness;
    private final AnalyzeChangeImpactTool changeImpact;
    private final AnalyzeControlFlowTool controlFlow;
    private final AnalyzeDataFlowTool dataFlow;
    private final AnalyzeSymbolTool symbol;

    public AnalyzeTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.file = new AnalyzeFileTool(serviceSupplier);
        this.type = new AnalyzeTypeTool(serviceSupplier);
        this.method = new AnalyzeMethodTool(serviceSupplier);
        this.javadocs = new AnalyzeJavadocsTool(serviceSupplier);
        this.naming = new AnalyzeNamingTool(serviceSupplier);
        this.nullness = new AnalyzeNullnessTool(serviceSupplier);
        this.changeImpact = new AnalyzeChangeImpactTool(serviceSupplier);
        this.controlFlow = new AnalyzeControlFlowTool(serviceSupplier);
        this.dataFlow = new AnalyzeDataFlowTool(serviceSupplier);
        this.symbol = new AnalyzeSymbolTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze";
    }

    @Override
    public String getDescription() {
        return """
            Comprehensive read-only analysis. One front door over the analyze family.

            USAGE: analyze(kind="<kind>", ...)

            Kinds and their params:
            - file          — analyze a source file. Needs: filePath.
            - type          — full type analysis (members, hierarchy, usages). Needs: typeName.
            - method        — method analysis. Needs: filePath, line, column.
            - javadocs      — Javadoc analysis. Needs: subkind (the variant), + scope/filePath.
            - naming        — naming analysis. Needs: subkind.
            - nullness      — null-safety analysis. Needs: subkind.
            - change_impact — blast radius of editing the symbol. Needs: filePath, line, column.
            - control_flow  — control-flow of the method. Needs: filePath, line, column.
            - data_flow     — data-flow of the method. Needs: filePath, line, column.
            - symbol        — understand a symbol in one call: definition + type + references. Needs: filePath, line, column.

            For javadocs/naming/nullness, pass the analyzer's own variant as `subkind`.
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
        kind.put("description", "Which analysis to run. See the tool description for per-kind params.");
        properties.put("kind", kind);

        properties.put("filePath", Map.of("type", "string", "description", "file/method/change_impact/control_flow/data_flow: source file."));
        properties.put("typeName", Map.of("type", "string", "description", "type: fully-qualified or simple type name."));
        properties.put("line", Map.of("type", "integer", "description", "method/change_impact/control_flow/data_flow: zero-based line."));
        properties.put("column", Map.of("type", "integer", "description", "method/change_impact/control_flow/data_flow: zero-based column."));
        properties.put("subkind", Map.of("type", "string", "description", "javadocs/naming/nullness: the analyzer's own variant (its `kind`)."));
        properties.put("scope", Map.of("type", "string", "description", "javadocs/naming/nullness: optional scope (package/type)."));

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
        JsonNode args = SUBKIND_KINDS.contains(kind) ? remapSubkind(arguments) : arguments;
        return switch (kind) {
            case "file"          -> file.executeWithService(service, args);
            case "type"          -> type.executeWithService(service, args);
            case "method"        -> method.executeWithService(service, args);
            case "javadocs"      -> javadocs.executeWithService(service, args);
            case "naming"        -> naming.executeWithService(service, args);
            case "nullness"      -> nullness.executeWithService(service, args);
            case "change_impact" -> changeImpact.executeWithService(service, args);
            case "control_flow"  -> controlFlow.executeWithService(service, args);
            case "data_flow"     -> dataFlow.executeWithService(service, args);
            case "symbol"        -> symbol.executeWithService(service, args);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }

    /** Map our `subkind` onto the delegate's `kind` (which our discriminator occupies). */
    private JsonNode remapSubkind(JsonNode arguments) {
        ObjectNode copy = (ObjectNode) arguments.deepCopy();
        copy.remove("kind");
        String subkind = getStringParam(arguments, "subkind");
        if (subkind != null && !subkind.isBlank()) {
            copy.put("kind", subkind);
        }
        copy.remove("subkind");
        return copy;
    }
}
