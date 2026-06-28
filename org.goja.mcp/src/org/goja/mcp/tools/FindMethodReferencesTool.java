package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.search.SearchMatch;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.fqn.FqnResolver;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Find all method reference expressions (Foo::bar lambdas).
 *
 * JDT-unique capability: Uses METHOD_REFERENCE_EXPRESSION to find only method references,
 * not regular method calls. LSP cannot distinguish these.
 */
public class FindMethodReferencesTool extends AbstractTool {

    public FindMethodReferencesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_method_references";
    }

    @Override
    public String getDescription() {
        return """
            Find all method reference expressions (Foo::bar lambda syntax).

            JDT-UNIQUE: This fine-grained search is not available in LSP.

            INPUT CONTRACT: TWO alternative invocation forms (Sprint 14 /
            bugs.md #12 — both halves shipped in v1.8.0):

            (a) Position-based — pass (filePath, line, column) on a method
                declaration or reference. ZERO-BASED coordinates.

            (b) FQN-based (v1.8.0) — pass `symbol` = "com.foo.Bar#methodName"
                (any overload) or "com.foo.Bar#methodName(int,java.lang.String)"
                (specific overload). Optional `scope` = "workspace" (default)
                or "project" (requires projectKey).

            USAGE: Position on a method, or provide method details.
            OUTPUT: All locations where the method is used as a method reference.

            Useful for:
            - Understanding functional programming patterns
            - Finding lambda-style usages of methods
            - Refactoring analysis

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "Path to source file containing the method");
        properties.put("filePath", filePath);

        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "integer");
        line.put("description", "Zero-based line number of the method");
        properties.put("line", line);

        Map<String, Object> column = new LinkedHashMap<>();
        column.put("type", "integer");
        column.put("description", "Zero-based column number");
        properties.put("column", column);

        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results to return (default 100)");
        properties.put("maxResults", maxResults);

        properties.put("symbol", Map.of(
            "type", "string",
            "description", "Sprint 14 FQN form (bugs.md #12 capability half): 'com.foo.Bar#method' (any overload) or 'com.foo.Bar#method(int,java.lang.String)' (specific overload)."));
        properties.put("scope", Map.of(
            "type", "string",
            "enum", List.of("workspace", "project"),
            "description", "FQN-form scope: 'workspace' (default) or 'project' (requires projectKey)."));
        schema.put("properties", properties);
        // Required: nothing — either {filePath,line,column} or {symbol}.
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 100);

        try {
            // Sprint 14 Phase B.2 (bugs.md #12): FQN form via `symbol` wins.
            String symbol = getStringParam(arguments, "symbol");
            IJavaElement element;
            if (symbol != null && !symbol.isBlank()) {
                String scopeRaw = getStringParam(arguments, "scope", "workspace");
                FqnResolver.Scope scope;
                try {
                    scope = FqnResolver.Scope.valueOf(scopeRaw.toUpperCase());
                } catch (IllegalArgumentException e) {
                    return ToolResponse.invalidParameter("scope",
                        "Must be 'workspace' or 'project'; got '" + scopeRaw + "'");
                }
                String projectKey = getStringParam(arguments, "projectKey");
                Optional<IJavaElement> resolved = FqnResolver.resolve(symbol, service, scope, projectKey);
                if (resolved.isEmpty()) {
                    return ToolResponse.symbolNotFound(
                        "FQN '" + symbol + "' not found in " + scopeRaw + " scope");
                }
                element = resolved.get();
            } else {
                String filePath = getStringParam(arguments, "filePath");
                int line = getIntParam(arguments, "line", -1);
                int column = getIntParam(arguments, "column", -1);
                if (filePath == null || filePath.isBlank()) {
                    return ToolResponse.invalidParameter("filePath",
                        "Required — pass either (filePath, line, column) or symbol");
                }
                if (line < 0 || column < 0) {
                    return ToolResponse.invalidParameter("position", "Line and column are required (zero-based)");
                }
                Path path = Path.of(filePath);
                element = service.getElementAtPosition(path, line, column);
                if (element == null) {
                    return ToolResponse.symbolNotFound("No element at position");
                }
            }

            if (!(element instanceof IMethod method)) {
                return ToolResponse.invalidParameter("symbol", "Element is not a method");
            }

            List<SearchMatch> matches = service.getSearchService().findMethodReferences(method, maxResults);
            List<Map<String, Object>> methodRefs = formatMatches(matches, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("methodName", method.getElementName());
            data.put("declaringType", method.getDeclaringType().getFullyQualifiedName());
            data.put("totalMethodReferences", methodRefs.size());
            data.put("methodReferences", methodRefs);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(methodRefs.size())
                .returnedCount(methodRefs.size())
                .truncated(matches.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "find_references for all references including regular calls",
                    "get_call_hierarchy_incoming to see all callers"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
