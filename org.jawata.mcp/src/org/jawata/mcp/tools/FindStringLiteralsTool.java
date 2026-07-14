package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.SourceScan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Sprint 22a P2-a — {@code find_string_literals}: locate string-literal
 * occurrences in source by substring (default) or regex.
 *
 * <p>JDT's {@code SearchEngine} indexes declarations + references, not literal
 * content, so "which file emits this log line / message" has no search API.
 * This walks {@code StringLiteral} AST nodes across the project's source and
 * matches their DECODED value (escapes resolved), returning file:line:column +
 * the literal. Net-new front door (the 2nd, with {@code move_method}).</p>
 */
public class FindStringLiteralsTool extends AbstractTool {

    public FindStringLiteralsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_string_literals";
    }

    @Override
    public String getDescription() {
        return """
            Find string-literal occurrences in source by substring (default) or regex.

            JDT's search indexes symbols, not literal content, so this walks the
            StringLiteral AST nodes and matches their decoded value — it locates
            the emitter of a log line / message that a symbol search cannot.

            USAGE: find_string_literals(query="some literal" [, regex=true] [, filePath=...])
            OUTPUT: file:line:column + the matched literal.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("query", Map.of("type", "string",
            "description", "Substring (default) or regex to match against string-literal values."));
        p.put("regex", Map.of("type", "boolean",
            "description", "Treat query as a Java regex (default false = substring)."));
        p.put("filePath", Map.of("type", "string",
            "description", "Optional: limit the search to one file; omit for whole-project."));
        p.put("maxResults", Map.of("type", "integer",
            "description", "Cap on matches (default 200)."));
        schema.put("properties", p);
        schema.put("required", List.of("query"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String query = getStringParam(arguments, "query");
        if (query == null || query.isBlank()) {
            return ToolResponse.invalidParameter("query", "query is required");
        }
        boolean regex = getBooleanParam(arguments, "regex", false);
        int maxResults = Math.min(Math.max(getIntParam(arguments, "maxResults", 200), 1), 5000);
        String filePath = getStringParam(arguments, "filePath");

        Pattern pattern = null;
        if (regex) {
            try {
                pattern = Pattern.compile(query);
            } catch (PatternSyntaxException e) {
                return ToolResponse.invalidParameter("query", "invalid regex: " + e.getMessage());
            }
        }
        final Pattern fp = pattern;

        List<Map<String, Object>> matches = new ArrayList<>();
        SourceScan scan = SourceScan.of((filePath != null && !filePath.isBlank())
            ? List.of(Path.of(filePath))
            : service.getAllJavaFiles());
        try {
            outer:
            for (Path path : scan.files()) {
                ICompilationUnit cu = scan.resolve(service, path);
                if (cu == null) {
                    continue;   // RECORDED, not swallowed — see SourceScan
                }
                CompilationUnit ast = scan.parse(cu, path, false);
                if (ast == null) {
                    continue;
                }
                scan.examined();
                String formatted = service.getPathUtils().formatPath(path);
                List<StringLiteral> literals = new ArrayList<>();
                ast.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(StringLiteral node) {
                        literals.add(node);
                        return true;
                    }
                });
                for (StringLiteral lit : literals) {
                    String value;
                    try {
                        value = lit.getLiteralValue();
                    } catch (Exception e) {
                        continue;   // malformed literal — skip
                    }
                    boolean hit = fp != null ? fp.matcher(value).find() : value.contains(query);
                    if (!hit) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("filePath", formatted);
                    m.put("line", ast.getLineNumber(lit.getStartPosition()));
                    m.put("column", ast.getColumnNumber(lit.getStartPosition()) + 1);
                    m.put("literal", value);
                    matches.add(m);
                    if (matches.size() >= maxResults) {
                        break outer;
                    }
                }
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }

        // "No matching literal" is only sayable if we managed to read the source.
        Optional<ToolResponse> blind = scan.refuseIfBlind("matching string literals");
        if (blind.isPresent()) {
            return blind.get();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("query", query);
        data.put("regex", regex);
        data.put("totalMatches", matches.size());
        data.put("matches", matches);
        data.putAll(scan.describe());
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(matches.size()).returnedCount(matches.size())
            .steering(scan.steering(matches.size(), "matching string literals"))
            .build());
    }
}
