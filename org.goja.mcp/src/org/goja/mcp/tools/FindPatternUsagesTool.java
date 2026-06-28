package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 11 Phase D — parametric tool that replaces five narrow
 * {@code find_*_usages} tools with a single dispatching call.
 *
 * <p>Replaces the per-service tool registrations:</p>
 * <ul>
 *   <li>{@code find_annotation_usages}</li>
 *   <li>{@code find_type_instantiations}</li>
 *   <li>{@code find_type_arguments}</li>
 *   <li>{@code find_casts}</li>
 *   <li>{@code find_instanceof_checks}</li>
 * </ul>
 *
 * <p>All five dispatch to {@link org.goja.core.search.SearchService}
 * methods that take a single {@link IType} plus a {@code maxResults} cap;
 * consolidating them into one parametric tool drops the per-service
 * tool count from 66 toward the Sprint 11 budget target of ~55 (free
 * slots for Phase E refactoring tools).</p>
 */
public class FindPatternUsagesTool extends AbstractTool {

    private static final List<String> KINDS =
        List.of("annotation", "instantiation", "type_argument", "cast", "instanceof");

    public FindPatternUsagesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_pattern_usages";
    }

    @Override
    public String getDescription() {
        return """
            Find usages of a Java type by pattern kind.

            JDT-UNIQUE: each kind below maps to a fine-grained search the
            JDT search engine supports natively but LSP cannot express.

            USAGE: find_pattern_usages(kind="<kind>", query="<fqn>")

            Kinds:
            - annotation     — usage sites of @<query> as an annotation
            - instantiation  — 'new <query>(...)' constructor calls
            - type_argument  — '<query>' used as a generic type argument
            - cast           — '(<query>) ...' cast expressions
            - instanceof     — '... instanceof <query>' checks

            For each kind, query is the fully qualified name of the type or
            annotation. Optional maxResults caps the response (default 100).

            Examples:
            - find_pattern_usages(kind="annotation", query="org.springframework.beans.factory.annotation.Autowired")
            - find_pattern_usages(kind="instantiation", query="java.util.ArrayList")
            - find_pattern_usages(kind="cast", query="java.lang.String")

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
        kind.put("description",
            "Pattern to find. annotation: usage sites of @X. instantiation: 'new T(...)' calls. "
            + "type_argument: 'List<T>' style usage. cast: '(T)' casts. instanceof: 'instanceof T' checks.");
        properties.put("kind", kind);

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description", "Fully qualified type or annotation name (e.g., 'java.util.ArrayList').");
        properties.put("query", query);

        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results to return (default 100).");
        properties.put("maxResults", maxResults);

        schema.put("properties", properties);
        schema.put("required", List.of("kind", "query"));

        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        String query = getStringParam(arguments, "query");
        int maxResults = getIntParam(arguments, "maxResults", 100);

        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind",
                "kind is required; one of " + KINDS);
        }
        if (!KINDS.contains(kind)) {
            return ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        }
        if (query == null || query.isBlank()) {
            return ToolResponse.invalidParameter("query",
                "query (fully-qualified type or annotation name) is required");
        }

        try {
            IType type = service.findType(query);
            if (type == null) {
                return ToolResponse.symbolNotFound("Type not found: " + query);
            }

            List<SearchMatch> matches = switch (kind) {
                case "annotation"     -> service.getSearchService().findAnnotationUsages(type, maxResults);
                case "instantiation"  -> service.getSearchService().findTypeInstantiations(type, maxResults);
                case "type_argument"  -> service.getSearchService().findTypeArguments(type, maxResults);
                case "cast"           -> service.getSearchService().findCasts(type, maxResults);
                case "instanceof"     -> service.getSearchService().findInstanceofChecks(type, maxResults);
                default -> throw new IllegalStateException("validated above; unreachable");
            };

            List<Map<String, Object>> usages = formatMatches(matches, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("kind", kind);
            data.put("query", query);
            data.put("totalUsages", usages.size());
            data.put("usages", usages);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(usages.size())
                .returnedCount(usages.size())
                .truncated(matches.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "get_symbol_info at a usage location for details",
                    "find_references for all references (not just this kind)"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
