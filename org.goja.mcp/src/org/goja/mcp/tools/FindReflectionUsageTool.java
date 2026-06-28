package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Find places where types or methods are referenced dynamically via Java reflection API.
 * These usages are invisible to static reference searches like find_references.
 */
public class FindReflectionUsageTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindReflectionUsageTool.class);

    // Type -> method name -> JDT method signature parameter types
    private static final String[][] REFLECTION_METHODS = {
        {"java.lang.Class", "forName", "Ljava/lang/String;"},
        {"java.lang.Class", "getMethod", "Ljava/lang/String;[Ljava/lang/Class;"},
        {"java.lang.Class", "getDeclaredMethod", "Ljava/lang/String;[Ljava/lang/Class;"},
        {"java.lang.Class", "getField", "Ljava/lang/String;"},
        {"java.lang.Class", "getDeclaredField", "Ljava/lang/String;"},
        {"java.lang.Class", "getConstructor", "[Ljava/lang/Class;"},
        {"java.lang.Class", "getDeclaredConstructor", "[Ljava/lang/Class;"},
        {"java.lang.Class", "newInstance", ""},
        {"java.lang.reflect.Method", "invoke", "Ljava/lang/Object;[Ljava/lang/Object;"},
        {"java.lang.reflect.Field", "get", "Ljava/lang/Object;"},
        {"java.lang.reflect.Field", "set", "Ljava/lang/Object;Ljava/lang/Object;"},
        {"java.lang.reflect.Constructor", "newInstance", "[Ljava/lang/Object;"},
    };

    public FindReflectionUsageTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_reflection_usage";
    }

    @Override
    public String getDescription() {
        return """
            Find places where Java reflection API is used.

            USAGE: find_reflection_usage()
            OUTPUT: All reflection calls grouped by method type

            Detects calls to:
            - Class.forName(), Class.newInstance()
            - Class.getMethod/getDeclaredMethod/getField/getDeclaredField
            - Class.getConstructor/getDeclaredConstructor
            - Method.invoke(), Field.get/set(), Constructor.newInstance()

            These usages are invisible to static reference searches
            and can break when types or methods are renamed.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results per reflection method (default 100)");
        properties.put("maxResults", maxResults);

        schema.put("properties", properties);
        schema.put("required", List.of());

        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 100);

        try {
            List<Map<String, Object>> allCalls = new ArrayList<>();
            Map<String, Integer> summary = new LinkedHashMap<>();

            for (String[] entry : REFLECTION_METHODS) {
                String typeName = entry[0];
                String methodName = entry[1];
                String paramSig = entry[2];
                String label = typeName.substring(typeName.lastIndexOf('.') + 1) + "." + methodName;

                try {
                    IType type = service.findType(typeName);
                    if (type == null) continue;

                    String[] paramTypes = paramSig.isEmpty() ? new String[0] : paramSig.split(";(?=[^\\[])");
                    // Fix trailing empty entries
                    List<String> cleanParams = new ArrayList<>();
                    for (String p : paramTypes) {
                        String trimmed = p.trim();
                        if (!trimmed.isEmpty()) {
                            if (!trimmed.endsWith(";")) trimmed += ";";
                            cleanParams.add(trimmed);
                        }
                    }

                    IMethod method = type.getMethod(methodName, cleanParams.toArray(new String[0]));
                    if (method == null || !method.exists()) continue;

                    List<SearchMatch> matches = service.getSearchService().findAllReferences(method, maxResults);
                    List<Map<String, Object>> formatted = formatMatches(matches, service);

                    for (Map<String, Object> match : formatted) {
                        match.put("reflectionMethod", label);
                        allCalls.add(match);
                    }

                    if (!formatted.isEmpty()) {
                        summary.put(label, formatted.size());
                    }
                } catch (Exception e) {
                    log.debug("Could not scan for {}.{}: {}", typeName, methodName, e.getMessage());
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalCalls", allCalls.size());
            data.put("summary", summary);
            data.put("reflectionCalls", allCalls);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(allCalls.size())
                .returnedCount(allCalls.size())
                .suggestedNextTools(List.of(
                    "analyze_change_impact to assess risk of renaming reflected types",
                    "get_symbol_info at a reflection call site for context"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
