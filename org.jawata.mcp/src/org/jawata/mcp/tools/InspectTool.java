package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A (v1.1.1) — parametric front door over the nine read-only
 * structural-inspection {@code get_*} tools. Flat schema + per-kind docs; the
 * narrow delegates self-validate. No discriminator collision in this family.
 *
 * <p>Replaces {@code get_type_hierarchy} / {@code get_document_symbols} /
 * {@code get_type_members} / {@code get_classpath_info} /
 * {@code get_project_structure} / {@code get_type_usage_summary} /
 * {@code get_complexity_metrics} / {@code get_dependency_graph} /
 * {@code get_di_registrations}.</p>
 */
public class InspectTool extends AbstractTool {

    private static final List<String> KINDS = List.of(
        "type_hierarchy", "document_symbols", "type_members", "classpath",
        "project_structure", "type_usage", "complexity", "dependency_graph",
        "di_registrations", "source", "landmarks");

    private final GetTypeHierarchyTool typeHierarchy;
    private final GetDocumentSymbolsTool documentSymbols;
    private final GetTypeMembersTool typeMembers;
    private final GetClasspathInfoTool classpath;
    private final GetProjectStructureTool projectStructure;
    private final GetTypeUsageSummaryTool typeUsage;
    private final GetComplexityMetricsTool complexity;
    private final GetDependencyGraphTool dependencyGraph;
    private final GetDiRegistrationsTool diRegistrations;

    public InspectTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.typeHierarchy = new GetTypeHierarchyTool(serviceSupplier);
        this.documentSymbols = new GetDocumentSymbolsTool(serviceSupplier);
        this.typeMembers = new GetTypeMembersTool(serviceSupplier);
        this.classpath = new GetClasspathInfoTool(serviceSupplier);
        this.projectStructure = new GetProjectStructureTool(serviceSupplier);
        this.typeUsage = new GetTypeUsageSummaryTool(serviceSupplier);
        this.complexity = new GetComplexityMetricsTool(serviceSupplier);
        this.dependencyGraph = new GetDependencyGraphTool(serviceSupplier);
        this.diRegistrations = new GetDiRegistrationsTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "inspect";
    }

    @Override
    public String getDescription() {
        return """
            Read-only structural inspection. One front door over the get_* family.

            USAGE: inspect(kind="<kind>", ...)

            Kinds and their params:
            - type_hierarchy   — super/sub types of a type. Needs: typeName.
            - document_symbols — the symbol outline of a file. Needs: filePath.
            - type_members     — methods/fields/nested types of a type. Needs: typeName.
            - classpath        — classpath info for the project. (no params)
            - project_structure— modules/source roots/packages. (no params)
            - type_usage       — usage summary of a type. Needs: typeName.
            - complexity       — complexity metrics for a file. Needs: filePath.
            - dependency_graph — dependency edges. Needs: scope, name.
            - di_registrations — DI bean/registration sites. (no params)
            - landmarks        — the workspace's load-bearing types, most-referenced
                                 first: what a human knows from having worked here.
                                 Start a session with it instead of searching your
                                 way in. Optional: limit (default 20).
            - source           — readable source for ANY type by FQN (JDK,
                                 dependency jars, workspace). Needs: typeName.
                                 Origin is declared: workspace-source |
                                 attached-source | sources-jar (the sibling
                                 …-sources.jar in the local repo, existing
                                 only — no silent fetch) | disassembled-stub
                                 (honest header, signatures only).

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
        kind.put("description", "Which inspection to run. See the tool description for per-kind params.");
        properties.put("kind", kind);

        properties.put("typeName", Map.of("type", "string", "description", "type_hierarchy/type_members/type_usage: type name."));
        properties.put("filePath", Map.of("type", "string", "description", "document_symbols/complexity: source file."));
        properties.put("scope", Map.of("type", "string", "description", "dependency_graph: scope (e.g. package/class)."));
        properties.put("name", Map.of("type", "string", "description", "dependency_graph: the entity name."));
        properties.put("limit", Map.of("type", "integer", "description", "landmarks: how many to name (default 20)."));

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
            case "type_hierarchy"    -> typeHierarchy.executeWithService(service, arguments);
            case "document_symbols"  -> documentSymbols.executeWithService(service, arguments);
            case "type_members"      -> typeMembers.executeWithService(service, arguments);
            case "classpath"         -> classpath.executeWithService(service, arguments);
            case "project_structure" -> projectStructure.executeWithService(service, arguments);
            case "type_usage"        -> typeUsage.executeWithService(service, arguments);
            case "complexity"        -> complexity.executeWithService(service, arguments);
            case "dependency_graph"  -> dependencyGraph.executeWithService(service, arguments);
            case "di_registrations"  -> diRegistrations.executeWithService(service, arguments);
            case "source"            -> libSource(service, arguments);
            case "landmarks"         -> landmarks(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }

    /**
     * Sprint 24 (D4) — the workspace's landmarks: the types everything else
     * leans on, most-referenced first. What a human knows from having worked
     * here; a fresh agent otherwise searches its way to them every session.
     */
    private ToolResponse landmarks(IJdtService service, JsonNode arguments) {
        int limit = getIntParam(arguments, "limit", 20);
        limit = Math.min(Math.max(limit, 1), 200);
        try {
            List<Map<String, Object>> found =
                org.jawata.mcp.tools.shared.Landmarks.of(service, limit);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("landmarks", found);
            data.put("count", found.size());
            return ToolResponse.success(data, org.jawata.mcp.models.ResponseMeta.builder()
                .returnedCount(found.size())
                .steering("These are the workspace's load-bearing types. Address them by name "
                    + "(symbol=/typeName=) — no search needed.")
                .build());
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    /** Sprint 23 (D8) — source by FQN, origin always declared. */
    private ToolResponse libSource(IJdtService service, JsonNode arguments) {
        String typeName = getStringParam(arguments, "typeName");
        if (typeName == null || typeName.isBlank()) {
            return ToolResponse.invalidParameter("typeName",
                "kind=source needs typeName (a fully-qualified type name).");
        }
        try {
            Map<String, Object> result =
                org.jawata.mcp.tools.shared.LibrarySource.sourceOf(service, typeName);
            if (result == null) {
                return ToolResponse.symbolNotFound(
                    "Type '" + typeName + "' resolves in no loaded project.");
            }
            return ToolResponse.success(result,
                org.jawata.mcp.models.ResponseMeta.builder().build());
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
