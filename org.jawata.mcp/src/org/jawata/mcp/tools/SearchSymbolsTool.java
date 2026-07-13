package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Search for types, methods, fields by name pattern.
 * Uses JDT SearchEngine for fast indexed search.
 */
public class SearchSymbolsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(SearchSymbolsTool.class);

    public SearchSymbolsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "search_symbols";
    }

    @Override
    public String getDescription() {
        return """
            Search for types, methods, fields by name pattern.
            Supports glob patterns: * (any chars), ? (single char)

            USAGE: search_symbols(query="*Service", kind="Class")
            OUTPUT: List of matching symbols with locations

            EXAMPLES:
            - search_symbols(query="Order*") - classes starting with Order
            - search_symbols(query="*Repository", kind="Interface")
            - search_symbols(query="get*", kind="Method")

            PAGINATION: Use offset parameter for large result sets
            PROJECTION: fields=["name","filePath","line"] keeps only those keys per row

            IMPORTANT: Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "query", Map.of(
                "type", "string",
                "description", "Search pattern - supports * and ? wildcards"
            ),
            "kind", Map.of(
                "type", "string",
                "description", "Filter by kind: Class, Interface, Enum, Method, Field"
            ),
            "maxResults", Map.of(
                "type", "integer",
                "description", "Max results to return (default 50)"
            ),
            "offset", Map.of(
                "type", "integer",
                "description", "Skip first N results for pagination"
            ),
            "fields", org.jawata.mcp.tools.shared.FieldsProjection.schemaProperty("results")
        ));
        schema.put("required", List.of("query"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String query = getStringParam(arguments, "query");
        if (query == null || query.isBlank()) {
            return ToolResponse.invalidParameter("query", "Required parameter missing");
        }

        String kind = getStringParam(arguments, "kind");
        int maxResults = getIntParam(arguments, "maxResults", 50);
        int offset = getIntParam(arguments, "offset", 0);

        // Sprint 23 (C13 decision B): per-row projection, validated up front.
        List<String> fields;
        try {
            fields = org.jawata.mcp.tools.shared.FieldsProjection.parse(arguments);
        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("fields", e.getMessage());
        }

        // Validate and cap values
        maxResults = Math.min(Math.max(maxResults, 1), 1000);
        offset = Math.max(offset, 0);

        try {
            // Convert kind to search type
            Integer searchFor = getSearchType(kind);

            // v2.8.1 (dogfood 2026-07-11): query the SOURCE scope first, then top
            // up from the full scope. A bounded full-scope window on a real
            // workspace fills with JRE/classpath types ("H*" → 40 binary hits)
            // and the project's own classes never enter it — ranking after the
            // fact can't fix what was never fetched.
            int window = offset + maxResults + 10;
            List<SearchMatch> matches = new ArrayList<>(service.getSearchService()
                .searchSymbolsInSource(query, searchFor, window));
            if (matches.size() < window) {
                matches.addAll(service.getSearchService()
                    .searchSymbols(query, searchFor, window));
            }

            // Sprint 22a P2 rider: a bare (no-wildcard) query that finds nothing
            // retries as a substring match — "Recall" then finds ExperienceRecall,
            // "idget" finds Widget. Explicit wildcards are respected as given.
            if (matches.isEmpty() && query.indexOf('*') < 0 && query.indexOf('?') < 0) {
                matches = new ArrayList<>(service.getSearchService()
                    .searchSymbolsInSource("*" + query + "*", searchFor, window));
                if (matches.size() < window) {
                    matches.addAll(service.getSearchService()
                        .searchSymbols("*" + query + "*", searchFor, window));
                }
            }

            // Convert the whole fetched window (bounded above by
            // offset+maxResults+10) so ranking happens BEFORE pagination.
            List<Map<String, Object>> results = new ArrayList<>();
            for (SearchMatch match : matches) {
                Map<String, Object> symbolInfo = createSymbolInfo(match, service);
                if (symbolInfo != null && (kind == null || matchesKind(symbolInfo, kind))) {
                    results.add(symbolInfo);
                }
            }

            // v1.7.1 (bug #2): drop cache-path duplicates. JDT SearchEngine returns
            // the same FQN from a binary classpath hit (no line/column) AND from
            // source (with line/column) when project B depends on project A's JAR.
            // Collapse each (kind, identity) group to its coordinate-bearing entry
            // when one exists.
            results = dedupeBySymbolIdentity(results);

            // v2.8.1 (dogfood 2026-07-11): project-source results rank before
            // binary-classpath results — a Class search for "*Tool" on jawata-mcp
            // itself listed JavacTool/JShellTool above the project's own classes.
            // List.sort is stable: JDT relevance order survives within partitions.
            results.sort(java.util.Comparator.comparingInt(r ->
                Boolean.TRUE.equals(r.get("binary")) ? 1 : 0));

            int total = results.size();
            List<Map<String, Object>> page = offset >= total
                ? List.of()
                : results.subList(offset, Math.min(offset + maxResults, total));

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("query", query);
            if (kind != null) data.put("kind", kind);
            data.put("results", org.jawata.mcp.tools.shared.FieldsProjection.project(page, fields));
            data.put("pagination", Map.of(
                "offset", offset,
                "returned", page.size(),
                "hasMore", offset + page.size() < total
            ));

            return ToolResponse.success(data, ResponseMeta.builder()
                .returnedCount(page.size())
                .truncated(page.size() == maxResults)
                .steering(teachTheAddress(query, page))
                .suggestedNextTools(List.of(
                    "get_symbol_info at a result location for detailed info",
                    "get_type_members for type results",
                    "find_references to see all usages"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error searching symbols: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Sprint 24 (D3) — <b>a search teaches its own address</b>. When the caller
     * named an exact symbol and we found it, tell them how to go STRAIGHT there
     * next time: the FQN is the stable memory key, and a found symbol should
     * become remembered knowledge instead of a search repeated every session.
     *
     * <p>Only on an EXACT-name hit (no wildcards in the query, a row whose name
     * IS the query) — a wildcard sweep has no single address to teach. Returns
     * null otherwise, which leaves the central steering in place.</p>
     */
    public static String teachTheAddress(String query, List<Map<String, Object>> page) {
        if (query.indexOf('*') >= 0 || query.indexOf('?') >= 0) {
            return null;
        }
        for (Map<String, Object> row : page) {
            if (!query.equals(row.get("name"))) {
                continue;
            }
            Object qualified = row.get("qualifiedName");
            String address = qualified != null
                ? String.valueOf(qualified)
                : row.get("containingType") + "#" + row.get("name");
            return "Address this directly next time: symbol=\"" + address + "\" — the name is "
                + "the stable key (it survives file moves; a position does not), and every "
                + "reading tool and whole-symbol refactoring accepts it. Remember it instead "
                + "of searching again.";
        }
        return null;
    }

    /**
     * v1.7.1 (bug #2): collapse cache-path duplicates. Groups results by
     * {@code (kind, qualifiedName)} (or {@code (kind, containingType#name)}
     * for methods/fields, which have no FQN). Within each group:
     * <ul>
     *   <li>If at least one entry has both {@code line} and {@code column} →
     *       keep only the coordinate-bearing entries; drop binary-classpath
     *       hits that lack source coordinates.</li>
     *   <li>If no entry has coordinates → keep one degraded entry; agents
     *       still get a hit without duplication.</li>
     * </ul>
     */
    static List<Map<String, Object>> dedupeBySymbolIdentity(List<Map<String, Object>> results) {
        Map<String, List<Map<String, Object>>> groups = new java.util.LinkedHashMap<>();
        for (Map<String, Object> r : results) {
            String key = identityKey(r);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(r);
        }
        List<Map<String, Object>> deduped = new ArrayList<>(results.size());
        for (List<Map<String, Object>> group : groups.values()) {
            if (group.size() == 1) {
                deduped.add(group.get(0));
                continue;
            }
            boolean anyWithCoords = false;
            for (Map<String, Object> r : group) {
                if (hasCoordinates(r)) {
                    anyWithCoords = true;
                    break;
                }
            }
            if (anyWithCoords) {
                // v2.8.1: the source-scope-first fetch can deliver the same
                // source hit twice (source pass + full-scope top-up) — keep
                // distinct coordinate-bearing entries only.
                List<Map<String, Object>> kept = new ArrayList<>();
                for (Map<String, Object> r : group) {
                    if (hasCoordinates(r) && !kept.contains(r)) {
                        kept.add(r);
                    }
                }
                deduped.addAll(kept);
            } else {
                // No coordinate-bearing entry — keep one degraded representative.
                deduped.add(group.get(0));
            }
        }
        return deduped;
    }

    static boolean hasCoordinates(Map<String, Object> r) {
        return r.containsKey("line") && r.containsKey("column");
    }

    private static String identityKey(Map<String, Object> r) {
        String kind = String.valueOf(r.get("kind"));
        Object qn = r.get("qualifiedName");
        if (qn != null) return kind + "::" + qn;
        Object containing = r.get("containingType");
        Object name = r.get("name");
        if (containing != null && name != null) return kind + "::" + containing + "#" + name;
        // Last resort — same name, same file is "same symbol". For unidentifiable
        // entries this prevents false-positive merging across different files.
        Object fp = r.get("filePath");
        return kind + "::" + name + "@" + fp;
    }

    private Integer getSearchType(String kind) {
        if (kind == null) return null; // Search all types

        return switch (kind.toLowerCase()) {
            case "class" -> IJavaSearchConstants.CLASS;
            case "interface" -> IJavaSearchConstants.INTERFACE;
            case "enum" -> IJavaSearchConstants.ENUM;
            case "method" -> IJavaSearchConstants.METHOD;
            case "field" -> IJavaSearchConstants.FIELD;
            default -> IJavaSearchConstants.TYPE;
        };
    }

    private boolean matchesKind(Map<String, Object> symbolInfo, String kind) {
        String symbolKind = (String) symbolInfo.get("kind");
        if (symbolKind == null) return true;
        return symbolKind.equalsIgnoreCase(kind);
    }

    private Map<String, Object> createSymbolInfo(SearchMatch match, IJdtService service) {
        Object element = match.getElement();
        if (!(element instanceof IJavaElement javaElement)) {
            return null;
        }

        Map<String, Object> info = new LinkedHashMap<>();

        try {
            info.put("name", javaElement.getElementName());
            info.put("kind", getElementKind(javaElement));

            // Location info
            if (match.getResource() != null) {
                IPath location = match.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            }

            // v2.8.1 (dogfood 2026-07-11): binary-classpath hits used to report
            // an opaque workspace-handle path. Flag them and point at the
            // readable archive (JAR / JMOD / jrt-fs) instead.
            IJavaElement rootEl = javaElement.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
            if (rootEl instanceof org.eclipse.jdt.core.IPackageFragmentRoot root && root.isArchive()) {
                info.put("binary", true);
                IPath archive = root.getPath();
                if (archive != null) {
                    info.put("filePath", archive.toOSString());
                }
            }

            // Add line/column from match offset
            if (javaElement.getOpenable() != null &&
                javaElement.getOpenable() instanceof org.eclipse.jdt.core.ICompilationUnit cu) {
                int line = service.getLineNumber(cu, match.getOffset());
                int column = service.getColumnNumber(cu, match.getOffset());
                info.put("line", line);
                info.put("column", column);
            }

            // Type-specific info
            if (javaElement instanceof IType type) {
                info.put("qualifiedName", type.getFullyQualifiedName());
                if (type.getPackageFragment() != null) {
                    info.put("package", type.getPackageFragment().getElementName());
                }
            } else if (javaElement instanceof IMethod method) {
                info.put("signature", getMethodSignature(method));
                if (method.getDeclaringType() != null) {
                    info.put("containingType", method.getDeclaringType().getElementName());
                }
            } else if (javaElement instanceof IField field) {
                info.put("type", field.getTypeSignature());
                if (field.getDeclaringType() != null) {
                    info.put("containingType", field.getDeclaringType().getElementName());
                }
            }

        } catch (JavaModelException e) {
            log.debug("Error getting symbol info: {}", e.getMessage());
        }

        return info;
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

    private String getMethodSignature(IMethod method) throws JavaModelException {
        StringBuilder sig = new StringBuilder();
        sig.append(method.getElementName()).append("(");

        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();

        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(org.eclipse.jdt.core.Signature.toString(paramTypes[i]));
            if (i < paramNames.length) {
                sig.append(" ").append(paramNames[i]);
            }
        }

        sig.append(")");
        return sig.toString();
    }
}
