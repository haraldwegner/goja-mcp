package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
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
 * Find all write accesses (mutations) to a field.
 * Uses JDT SearchEngine with WRITE_ACCESSES filter for data flow analysis.
 *
 * AI-centric: Helps understand "what modifies this state?" - critical for
 * refactoring, debugging, and impact analysis.
 */
public class FindFieldWritesTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindFieldWritesTool.class);

    public FindFieldWritesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_field_writes";
    }

    @Override
    public String getDescription() {
        return """
            Find all write accesses (mutations) to a field.

            INPUT CONTRACT: TWO alternative invocation forms (Sprint 14 /
            bugs.md #12 — both halves shipped in v1.8.0):

            (a) Position-based — pass (filePath, line, column) on a field
                declaration or reference. ZERO-BASED coordinates.

            (b) FQN-based (v1.8.0) — pass `symbol` = "com.foo.Bar#fieldName".
                Optional `scope` = "workspace" (default) or "project"
                (requires projectKey).

            USAGE: Position cursor on a field declaration or reference.
            OUTPUT: List of locations where the field is modified.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Sprint 14 graceful degradation: when the (filePath, line, column)
            position does NOT resolve to a field — either no symbol at all, or
            a non-field symbol (method, type, local variable, …) — the
            response is a SUCCESS with empty writeLocations PLUS a
            `nearbyFieldCandidates` list (up to 3 fields declared within ±1
            line of the requested position, each with name + line + column).
            Re-call with one of those coordinates if the position was off.

            Unlike find_references which returns all usages, this returns only
            locations where the field value is changed (assignments, increments, etc).
            Useful for data flow analysis and understanding state mutations.

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
            "description", "Sprint 14 FQN form (bugs.md #12 capability half): 'com.foo.Bar#fieldName'."));
        properties.put("scope", Map.of(
            "type", "string",
            "enum", List.of("workspace", "project"),
            "description", "FQN-form scope: 'workspace' (default) or 'project' (requires projectKey)."));
        properties.put("maxResults", Map.of(
            "type", "integer",
            "description", "Max write locations to return (default 100)."));
        schema.put("properties", properties);
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 100);
        maxResults = Math.min(Math.max(maxResults, 1), 1000);

        try {
            // Sprint 14 Phase B.2 (bugs.md #12 capability half): FQN form
            // wins when `symbol` is provided. The graceful-degradation
            // nearbyFieldCandidates path stays bound to the position-based
            // path — the FQN path uses a strict "not found" / "not a field"
            // dispatch since the caller knew exactly what they were asking
            // for.
            String symbol = getStringParam(arguments, "symbol");
            IField field;
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
                IJavaElement el = resolved.get();
                if (!(el instanceof IField f)) {
                    return ToolResponse.invalidParameter("symbol",
                        "FQN '" + symbol + "' resolves to " + getElementKind(el) + ", not a field");
                }
                field = f;
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
                IJavaElement element = service.getElementAtPosition(path, line, column);
                if (element == null || !(element instanceof IField)) {
                    return buildNearbyCandidatesResponse(service, path, line, element);
                }
                field = (IField) element;
            }

            // Use SearchService for indexed write access search
            List<SearchMatch> matches = service.getSearchService()
                .findWriteAccesses(field, maxResults);

            // Convert matches to write location info
            List<Map<String, Object>> writeLocations = new ArrayList<>();
            for (SearchMatch match : matches) {
                Map<String, Object> writeInfo = createWriteInfo(match, service);
                if (writeInfo != null) {
                    writeLocations.add(writeInfo);
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("field", field.getElementName());

            if (field.getDeclaringType() != null) {
                data.put("declaringType", field.getDeclaringType().getElementName());
            }

            try {
                data.put("fieldType", field.getTypeSignature());
            } catch (Exception e) {
                // Ignore if can't get type
            }

            data.put("totalWriteLocations", writeLocations.size());
            data.put("writeLocations", writeLocations);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(writeLocations.size())
                .returnedCount(writeLocations.size())
                .truncated(writeLocations.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "find_references to see all usages (reads and writes)",
                    "get_call_hierarchy_incoming to find callers of methods that modify this field"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error finding field writes: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private Map<String, Object> createWriteInfo(SearchMatch match, IJdtService service) {
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
                    int writeLine = service.getLineNumber(cu, match.getOffset());
                    int writeColumn = service.getColumnNumber(cu, match.getOffset());
                    info.put("line", writeLine);
                    info.put("column", writeColumn);

                    // Get context line (shows the actual write statement)
                    String context = service.getContextLine(cu, match.getOffset());
                    if (!context.isEmpty()) {
                        info.put("context", context);
                    }
                }
            }

            info.put("accessType", "WRITE");

            return info;

        } catch (Exception e) {
            log.debug("Error creating write info: {}", e.getMessage());
            return null;
        }
    }

    private String getElementKind(IJavaElement element) {
        return switch (element.getElementType()) {
            case IJavaElement.TYPE -> "Type";
            case IJavaElement.METHOD -> "Method";
            case IJavaElement.FIELD -> "Field";
            case IJavaElement.LOCAL_VARIABLE -> "Variable";
            default -> "Unknown";
        };
    }

    /**
     * Sprint 14 (bugs.md #12): build a success response with empty
     * writeLocations and a {@code nearbyFieldCandidates} list (up to 3
     * fields declared within ±1 line of the requested position). The agent
     * can pick one and re-call with its coordinates.
     */
    private ToolResponse buildNearbyCandidatesResponse(IJdtService service, Path filePath,
                                                       int requestedLine, IJavaElement foundElement) {
        List<Map<String, Object>> candidates = findNearbyFieldCandidates(service, filePath, requestedLine);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("field", null);
        data.put("totalWriteLocations", 0);
        data.put("writeLocations", List.of());
        String note;
        if (foundElement == null) {
            note = "No symbol at the requested position. See nearbyFieldCandidates for fields nearby.";
        } else {
            note = "Symbol at position is " + getElementKind(foundElement)
                + " (not a field). See nearbyFieldCandidates for fields nearby.";
        }
        data.put("note", note);
        data.put("nearbyFieldCandidates", candidates);

        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(0)
            .returnedCount(0)
            .build());
    }

    private List<Map<String, Object>> findNearbyFieldCandidates(IJdtService service,
                                                                 Path filePath,
                                                                 int requestedLine) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        try {
            ICompilationUnit cu = service.getCompilationUnit(filePath);
            if (cu == null) return candidates;
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(false);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);

            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(FieldDeclaration node) {
                    if (candidates.size() >= 3) return false;
                    for (Object f : node.fragments()) {
                        if (candidates.size() >= 3) break;
                        if (f instanceof VariableDeclarationFragment frag) {
                            int startPos = frag.getName().getStartPosition();
                            int fragLine = ast.getLineNumber(startPos) - 1;
                            if (Math.abs(fragLine - requestedLine) <= 1) {
                                Map<String, Object> entry = new LinkedHashMap<>();
                                entry.put("name", frag.getName().getIdentifier());
                                entry.put("line", fragLine);
                                entry.put("column", ast.getColumnNumber(startPos));
                                candidates.add(entry);
                            }
                        }
                    }
                    // Don't descend into the field's initializer expressions —
                    // we only want declared field names at top-of-class.
                    return false;
                }
            });
        } catch (Exception e) {
            log.debug("Failed to compute nearby field candidates: {}", e.getMessage());
        }
        return candidates;
    }
}
