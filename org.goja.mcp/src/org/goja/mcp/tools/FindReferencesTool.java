package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.SearchMatch;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.fqn.FqnResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Find all references to a symbol across the project.
 * Uses JDT SearchEngine for fast indexed search.
 */
public class FindReferencesTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindReferencesTool.class);

    public FindReferencesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_references";
    }

    @Override
    public String getDescription() {
        return """
            Find all references to a symbol across the project.

            INPUT CONTRACT: TWO alternative invocation forms (Sprint 14 /
            bugs.md #12 — both halves shipped in v1.8.0):

            (a) Position-based — pass (filePath, line, column). The triple
                is required and must point at a declared symbol (type,
                method, field, etc.). ZERO-BASED coordinates.

            (b) FQN-based (v1.8.0) — pass `symbol` instead of position. The
                resolver handles three forms:
                  - Type:                   "com.foo.Bar"
                  - Method (any overload):  "com.foo.Bar#methodName"
                  - Method (one overload):  "com.foo.Bar#methodName(int,java.lang.String)"
                  - Field:                  "com.foo.Bar#fieldName"
                Plus `scope`: "workspace" (default) searches every loaded
                project; "project" requires `projectKey` and scopes the FQN
                lookup to that one project.

            OUTPUT: List of reference locations with context.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of(
            "type", "string",
            "description", "Path to source file (position-based form)."));
        properties.put("line", Map.of(
            "type", "integer",
            "description", "Zero-based line number (position-based form)."));
        properties.put("column", Map.of(
            "type", "integer",
            "description", "Zero-based column number (position-based form)."));
        properties.put("symbol", Map.of(
            "type", "string",
            "description", "Sprint 14 FQN form (bugs.md #12 capability half): 'com.foo.Bar' (type) or 'com.foo.Bar#method' or 'com.foo.Bar#method(int,java.lang.String)' or 'com.foo.Bar#field'."));
        properties.put("scope", Map.of(
            "type", "string",
            "enum", List.of("workspace", "project"),
            "description", "FQN-form scope: 'workspace' (default) or 'project' (requires projectKey)."));
        properties.put("maxResults", Map.of(
            "type", "integer",
            "description", "Max references to return (default 100)."));
        schema.put("properties", properties);
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 100);
        maxResults = Math.min(Math.max(maxResults, 1), 1000);

        try {
            // Sprint 14 Phase B.2 (bugs.md #12 capability half): FQN form
            // wins when `symbol` is provided. Otherwise fall back to the
            // position-based form (kept verbatim — additive change).
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
                if (filePath == null || filePath.isBlank()) {
                    return ToolResponse.invalidParameter("filePath",
                        "Required parameter missing — pass either (filePath, line, column) or symbol");
                }
                int line = getIntParam(arguments, "line", -1);
                int column = getIntParam(arguments, "column", -1);
                if (line < 0) {
                    return ToolResponse.invalidParameter("line", "Must be >= 0 (zero-based)");
                }
                if (column < 0) {
                    return ToolResponse.invalidParameter("column", "Must be >= 0 (zero-based)");
                }
                Path path = Path.of(filePath);
                element = service.getElementAtPosition(path, line, column);
                if (element == null) {
                    return ToolResponse.symbolNotFound("No symbol found at position");
                }
            }

            // Use SearchService for indexed reference search
            List<SearchMatch> matches = service.getSearchService()
                .findAllReferences(element, maxResults);

            // Convert matches to reference info
            List<Map<String, Object>> references = new ArrayList<>();
            for (SearchMatch match : matches) {
                Map<String, Object> refInfo = createReferenceInfo(match, service);
                if (refInfo != null) {
                    references.add(refInfo);
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", element.getElementName());
            data.put("symbolKind", getElementKind(element));

            // Add containing type info
            if (element instanceof IMethod method && method.getDeclaringType() != null) {
                data.put("containingType", method.getDeclaringType().getElementName());
            } else if (element instanceof IField field && field.getDeclaringType() != null) {
                data.put("containingType", field.getDeclaringType().getElementName());
            }

            data.put("totalReferences", references.size());
            data.put("references", references);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(references.size())
                .returnedCount(references.size())
                .truncated(references.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "go_to_definition to see the symbol definition",
                    "get_type_hierarchy for type symbols"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error finding references: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createReferenceInfo(SearchMatch match, IJdtService service) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();

            // File path
            if (match.getResource() != null) {
                IPath location = match.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            }

            // Get ICompilationUnit for line/column calculation
            Object element = match.getElement();
            if (element instanceof IJavaElement javaElement) {
                ICompilationUnit cu = (ICompilationUnit) javaElement.getAncestor(IJavaElement.COMPILATION_UNIT);
                if (cu != null) {
                    int refLine = service.getLineNumber(cu, match.getOffset());
                    int refColumn = service.getColumnNumber(cu, match.getOffset());
                    info.put("line", refLine);
                    info.put("column", refColumn);

                    // Get context line
                    String context = service.getContextLine(cu, match.getOffset());
                    if (!context.isEmpty()) {
                        info.put("context", context);
                    }
                }
            }

            // Reference kind based on match accuracy
            String refKind = getReferenceKind(match);
            info.put("referenceKind", refKind);

            return info;

        } catch (Exception e) {
            log.debug("Error creating reference info: {}", e.getMessage());
            return null;
        }
    }

    private String getReferenceKind(SearchMatch match) {
        // Use match accuracy and flags to determine reference kind
        if (match.isInsideDocComment()) {
            return "JAVADOC";
        }

        // Try to infer from the element
        Object element = match.getElement();
        if (element instanceof IMethod) {
            return "METHOD_INVOCATION";
        } else if (element instanceof IField) {
            return "FIELD_ACCESS";
        } else if (element instanceof IType) {
            return "TYPE_REFERENCE";
        }

        return "REFERENCE";
    }

    private String getElementKind(IJavaElement element) {
        return switch (element.getElementType()) {
            case IJavaElement.TYPE -> {
                if (element instanceof IType type) {
                    try {
                        if (type.isInterface()) yield "Interface";
                        if (type.isEnum()) yield "Enum";
                        if (type.isAnnotation()) yield "Annotation";
                    } catch (JavaModelException e) {
                        // Fall through
                    }
                }
                yield "Class";
            }
            case IJavaElement.METHOD -> "Method";
            case IJavaElement.FIELD -> "Field";
            case IJavaElement.LOCAL_VARIABLE -> "Variable";
            default -> "Unknown";
        };
    }
}
