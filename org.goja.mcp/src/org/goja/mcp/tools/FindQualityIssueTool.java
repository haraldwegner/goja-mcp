package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.DetectorCatalog;
import org.goja.mcp.domain.GojaService;
import org.goja.mcp.domain.IGojaService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.smell.FowlerDetectors;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Parametric Smell front door. Its {@code kind} enum and dispatch are a
 * <em>projection</em> of a {@link DetectorCatalog} (Sprint 16b/D): the loaded
 * tool surface is constant while the capability behind it grows by registration.
 * Sprint 17 (Fowler) / 20 (SOLID) add a {@code Detector} (a kind) to the catalog
 * and it appears here automatically — no edit to this class, no new tool.
 *
 * <p>Originally (Sprint 11 Phase D) this hard-coded a switch over eight narrow
 * analyzers; those analyzers are now the built-in detectors registered by
 * {@link QualityDetectors#builtins}.</p>
 */
public class FindQualityIssueTool extends AbstractTool {

    private final DetectorCatalog catalog;

    /**
     * Build the default catalog: the eight built-in quality detectors plus the
     * Sprint 17 Fowler smell detectors. The live registration
     * ({@code GojaApplication}) uses this ctor, so registering a Fowler kind in
     * {@link FowlerDetectors} surfaces it here automatically — no edit to this
     * class, no new tool.
     */
    public FindQualityIssueTool(Supplier<IJdtService> serviceSupplier) {
        this(new GojaService(serviceSupplier,
            FowlerDetectors.registerInto(QualityDetectors.builtins(serviceSupplier))));
    }

    /** The seam: project from the supplied service's detector catalog. */
    public FindQualityIssueTool(IGojaService goja) {
        super(goja::jdt);
        this.catalog = goja.detectors();
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

            Fowler smell kinds (Sprint 17) are also registered — long_method,
            god_class, long_parameter_list, data_clumps, feature_envy,
            message_chains, inappropriate_intimacy, middle_man, primitive_obsession,
            switch_statements, refused_bequest, temporary_field; most accept an
            optional `threshold`. See the kind enum for the full list.

            The available kinds are the registered detectors (see the kind enum);
            more analyses may be added without introducing new tools.

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
        kind.put("enum", catalog.kinds()); // projected from the catalog
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

        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("type", "integer");
        threshold.put("description",
            "Optional sensitivity threshold for the Fowler smell kinds (Sprint 17), e.g. "
                + "long_method LOC cutoff. Each smell kind documents its threshold meaning; "
                + "omit to use the kind's default.");
        properties.put("threshold", threshold);

        schema.put("properties", properties);
        schema.put("required", List.of("kind"));

        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + catalog.kinds());
        }
        return catalog.get(kind)
            .map(detector -> detector.detect(service, arguments))
            .orElseGet(() -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + catalog.kinds()));
    }
}
