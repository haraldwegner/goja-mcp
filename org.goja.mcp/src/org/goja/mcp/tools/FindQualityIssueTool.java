package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 11 Phase D — parametric tool that replaces eight narrow
 * code-quality tools with a single dispatching call.
 *
 * <p>Replaces the per-service tool registrations:</p>
 * <ul>
 *   <li>{@code find_naming_violations}</li>
 *   <li>{@code find_possible_bugs}</li>
 *   <li>{@code find_unused_code}</li>
 *   <li>{@code find_large_classes}</li>
 *   <li>{@code find_circular_dependencies}</li>
 *   <li>{@code find_reflection_usage}</li>
 *   <li>{@code find_throws_declarations}</li>
 *   <li>{@code find_catch_blocks}</li>
 * </ul>
 *
 * <p>The narrow tool classes stay in the same package as concrete
 * implementations of the AST / search analyses; this tool delegates to
 * them through their package-private {@link AbstractTool#executeWithService}
 * entry point. They are no longer registered as user-facing MCP tools so
 * agents see only {@code find_quality_issue} in {@code tools/list}.</p>
 */
public class FindQualityIssueTool extends AbstractTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<String> KINDS = List.of(
        "naming",
        "bugs",
        "unused",
        "large_classes",
        "circular_deps",
        "reflection",
        "throws",
        "catches"
    );

    private final FindNamingViolationsTool naming;
    private final FindPossibleBugsTool bugs;
    private final FindUnusedCodeTool unused;
    private final FindLargeClassesTool largeClasses;
    private final FindCircularDependenciesTool circularDeps;
    private final FindReflectionUsageTool reflection;
    private final FindThrowsDeclarationsTool throwsDecls;
    private final FindCatchBlocksTool catches;

    public FindQualityIssueTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.naming = new FindNamingViolationsTool(serviceSupplier);
        this.bugs = new FindPossibleBugsTool(serviceSupplier);
        this.unused = new FindUnusedCodeTool(serviceSupplier);
        this.largeClasses = new FindLargeClassesTool(serviceSupplier);
        this.circularDeps = new FindCircularDependenciesTool(serviceSupplier);
        this.reflection = new FindReflectionUsageTool(serviceSupplier);
        this.throwsDecls = new FindThrowsDeclarationsTool(serviceSupplier);
        this.catches = new FindCatchBlocksTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_quality_issue";
    }

    @Override
    public String getDescription() {
        return """
            Run a code-quality analysis or search across the project.

            USAGE: find_quality_issue(kind="<kind>", ...)

            Kinds (most accept an optional filePath; otherwise scan all files):
            - naming         — Java naming-convention violations (PascalCase classes,
                               camelCase methods/fields, UPPER_SNAKE_CASE constants).
            - bugs           — common bug patterns (null deref, ==, mutation in lambda…).
            - unused         — unused private methods and fields.
            - large_classes  — classes exceeding maxMethods/maxFields/maxLines thresholds.
            - circular_deps  — cyclic package or class dependencies.
            - reflection     — Class.forName / Method.invoke / Field.get usage sites.
            - throws         — methods declaring 'throws <query>' (query = exception FQN).
            - catches        — 'catch (<query> ...)' blocks (query = exception FQN).

            Examples:
            - find_quality_issue(kind="naming", filePath="path/to/File.java")
            - find_quality_issue(kind="large_classes", maxLines=500)
            - find_quality_issue(kind="throws", query="java.io.IOException")

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
            "Which quality analysis to run. See the tool description for what each kind reports.");
        properties.put("kind", kind);

        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description",
            "Optional. Limit naming/bugs/unused/reflection scans to a single file. Omit for whole-project.");
        properties.put("filePath", filePath);

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description",
            "Required for kind=throws or kind=catches: fully-qualified exception type name (e.g. 'java.io.IOException').");
        properties.put("query", query);

        Map<String, Object> maxMethods = new LinkedHashMap<>();
        maxMethods.put("type", "integer");
        maxMethods.put("description", "kind=large_classes only — class is 'large' above this method count (default 20).");
        properties.put("maxMethods", maxMethods);

        Map<String, Object> maxFields = new LinkedHashMap<>();
        maxFields.put("type", "integer");
        maxFields.put("description", "kind=large_classes only — class is 'large' above this field count (default 10).");
        properties.put("maxFields", maxFields);

        Map<String, Object> maxLines = new LinkedHashMap<>();
        maxLines.put("type", "integer");
        maxLines.put("description", "kind=large_classes only — class is 'large' above this line count (default 300).");
        properties.put("maxLines", maxLines);

        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "kind=throws or kind=catches — caps result count (default 100).");
        properties.put("maxResults", maxResults);

        schema.put("properties", properties);
        schema.put("required", List.of("kind"));

        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind",
                "kind is required; one of " + KINDS);
        }
        if (!KINDS.contains(kind)) {
            return ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        }

        return switch (kind) {
            case "naming"        -> naming.executeWithService(service, arguments);
            case "bugs"          -> bugs.executeWithService(service, arguments);
            case "unused"        -> unused.executeWithService(service, arguments);
            case "large_classes" -> largeClasses.executeWithService(service, arguments);
            case "circular_deps" -> circularDeps.executeWithService(service, arguments);
            case "reflection"    -> reflection.executeWithService(service, arguments);
            case "throws"        -> throwsDecls.executeWithService(service, withExceptionTypeAlias(arguments));
            case "catches"       -> catches.executeWithService(service, withExceptionTypeAlias(arguments));
            default -> throw new IllegalStateException("validated above; unreachable");
        };
    }

    /**
     * The narrow throws/catches tools read {@code exceptionType}; the
     * unified surface uses {@code query}. Build a copy of arguments with
     * {@code exceptionType} populated from {@code query} so we don't
     * change the underlying tool implementations.
     */
    private static JsonNode withExceptionTypeAlias(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return arguments;
        }
        ObjectNode copy;
        if (arguments instanceof ObjectNode existing) {
            copy = existing.deepCopy();
        } else {
            copy = MAPPER.createObjectNode();
            arguments.fields().forEachRemaining(e -> copy.set(e.getKey(), e.getValue()));
        }
        if (!copy.has("exceptionType") && copy.has("query")) {
            copy.set("exceptionType", copy.get("query"));
        }
        return copy;
    }
}
