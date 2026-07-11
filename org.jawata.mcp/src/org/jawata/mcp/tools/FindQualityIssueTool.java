package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.DetectorCatalog;
import org.jawata.mcp.domain.JawataService;
import org.jawata.mcp.domain.IJawataService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.smell.DependencyDetectors;
import org.jawata.mcp.tools.smell.FowlerDetectors;
import org.jawata.mcp.tools.smell.KerievskyDetectors;
import org.jawata.mcp.tools.smell.SolidDetectors;

import java.util.ArrayList;
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

    /** Sprint 22a 2.6.1 (#1) — default cap on a family sweep's findings; the full total stays in `count`. */
    private static final int DEFAULT_FINDINGS_LIMIT = 100;

    private final DetectorCatalog catalog;

    /**
     * Build the default catalog: the eight built-in quality detectors plus the
     * Sprint 17 Fowler smell detectors. The live registration
     * ({@code JawataApplication}) uses this ctor, so registering a Fowler kind in
     * {@link FowlerDetectors} surfaces it here automatically — no edit to this
     * class, no new tool.
     */
    public FindQualityIssueTool(Supplier<IJdtService> serviceSupplier) {
        this(new JawataService(serviceSupplier,
            DependencyDetectors.registerInto(
                KerievskyDetectors.registerInto(
                    SolidDetectors.registerInto(
                        FowlerDetectors.registerInto(QualityDetectors.builtins(serviceSupplier)))))));
    }

    /** The seam: project from the supplied service's detector catalog. */
    public FindQualityIssueTool(IJawataService jawata) {
        super(jawata::jdt);
        this.catalog = jawata.detectors();
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
            switch_statements, refused_bequest, temporary_field, lazy_class,
            speculative_generality, parallel_inheritance, incomplete_delegation,
            divergent_change, shotgun_surgery; most accept an optional `threshold`.
            See the kind enum for the full list.

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
            "Which single analysis to run. See the tool description for what each kind reports. "
                + "Provide `kind` OR `family` (family runs the whole set).");
        properties.put("kind", kind);

        Map<String, Object> family = new LinkedHashMap<>();
        family.put("type", "string");
        family.put("enum", List.of("quality", "fowler", "solid", "kerievsky"));
        family.put("description",
            "Optional family lens (Sprint 20). With no `kind`, runs every detector in the family and "
                + "merges the findings (e.g. family=\"solid\" returns the SOLID set as a unit: dip, isp, "
                + "srp_cohesion, lsp + the tagged incomplete_delegation/refused_bequest/divergent_change/"
                + "shotgun_surgery). With a `kind`, validates the kind belongs to the family.");
        properties.put("family", family);

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

        Map<String, Object> includeTests = new LinkedHashMap<>();
        includeTests.put("type", "boolean");
        includeTests.put("description",
            "Fowler smell kinds only — include test sources (src/test, *.tests) in the scan. "
                + "Default false: test code legitimately has long methods and subject-under-test "
                + "access, so it is excluded to keep findings actionable.");
        properties.put("includeTests", includeTests);

        properties.put("from", Map.of("type", "string",
            "description", "forbidden_edge: the source package prefix (the layer that must not depend outward)."));
        properties.put("forbidden", Map.of("type", "string",
            "description", "forbidden_edge: the package prefix the `from` layer must not depend on."));
        properties.put("baseline", Map.of("type", "string",
            "enum", List.of("save", "diff"),
            "description", "family sweeps only: 'save' snapshots the current findings; 'diff' "
                + "returns {new, fixed, unchanged} vs the saved snapshot (trend over time)."));
        properties.put("summary", Map.of("type", "boolean",
            "description", "family sweeps only: return counts-by-kind + the conflicts list only "
                + "(NO full findings array) — the consumable shape for a broad sweep on a large project."));
        properties.put("excludePaths", Map.of("type", "array",
            "items", Map.of("type", "string"),
            "description", "Optional path substrings; findings (and conflicts) whose filePath contains "
                + "any entry are dropped BEFORE counts/summary/baseline/pagination — e.g. a vendored "
                + "module like a copied upstream source file (v2.8.1)."));
        properties.put("offset", Map.of("type", "integer",
            "description", "family sweeps only: skip the first N findings (pagination; default 0)."));
        properties.put("limit", Map.of("type", "integer",
            "description", "family sweeps only: cap the findings returned (default " + DEFAULT_FINDINGS_LIMIT
                + "); the full total is always in `count`. Prefer `summary` for a broad sweep."));

        schema.put("properties", properties);
        // kind OR family — validated in executeWithService (a static `required` can't express "one of").
        schema.put("required", List.of());

        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        String family = getStringParam(arguments, "family");
        boolean hasKind = kind != null && !kind.isBlank();
        boolean hasFamily = family != null && !family.isBlank();

        // family without kind: run every detector in the family and merge findings.
        if (!hasKind && hasFamily) {
            ToolResponse r = runFamily(service, family, arguments);
            String baseline = getStringParam(arguments, "baseline");
            if (r.isSuccess() && baseline != null && !baseline.isBlank()) {
                return applyBaseline(service, family, baseline, r);
            }
            // Sprint 22a 2.6.1 (#1): bound the sweep for MCP consumers (summary / cap / page).
            return r.isSuccess() ? paginateFamily(r, arguments) : r;
        }
        if (!hasKind) {
            return ToolResponse.invalidParameter("kind",
                "Provide `kind` (one of " + catalog.kinds() + ") or `family` (quality/fowler/solid/kerievsky).");
        }
        if (hasFamily && !catalog.kinds(family).contains(kind)) {
            return ToolResponse.invalidParameter("kind",
                "kind '" + kind + "' is not in family '" + family + "'. That family: " + catalog.kinds(family));
        }
        return catalog.get(kind)
            .map(detector -> filterExcludedPaths(detector.detect(service, arguments), arguments))
            .orElseGet(() -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + catalog.kinds()));
    }

    /** v2.8.1 (dogfood 2026-07-11): parse the optional excludePaths array. */
    private static List<String> excludePathsOf(JsonNode arguments) {
        List<String> excludes = new ArrayList<>();
        if (arguments != null && arguments.get("excludePaths") != null
                && arguments.get("excludePaths").isArray()) {
            arguments.get("excludePaths").forEach(n -> {
                String s = n.asText("");
                if (!s.isBlank()) excludes.add(s);
            });
        }
        return excludes;
    }

    /**
     * v2.8.1 (dogfood 2026-07-11): drop findings whose filePath contains any
     * excludePaths entry. The trigger: a fowler sweep on jawata-mcp itself was
     * flooded by the vendored 12k-line ProblemReporter copy in
     * org.jawata.jdtpatch — correct findings, useless signal. Applied to
     * single-kind responses here; the family path filters the merged list
     * before conflicts/counts so every derived number stays consistent.
     */
    @SuppressWarnings("unchecked")
    private static ToolResponse filterExcludedPaths(ToolResponse r, JsonNode arguments) {
        List<String> excludes = excludePathsOf(arguments);
        if (excludes.isEmpty() || r == null || !r.isSuccess()
                || !(r.getData() instanceof Map<?, ?> raw)
                || !(raw.get("findings") instanceof List<?> fs)) {
            return r;
        }
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) raw);
        List<Object> kept = keepUnexcluded((List<Object>) fs, excludes);
        data.put("findings", kept);
        if (data.get("count") instanceof Number) {
            data.put("count", kept.size());
        }
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(kept.size()).returnedCount(kept.size()).build());
    }

    private static List<Object> keepUnexcluded(List<Object> findings, List<String> excludes) {
        List<Object> kept = new ArrayList<>(findings.size());
        for (Object o : findings) {
            if (o instanceof Map<?, ?> f && f.get("filePath") instanceof String fp
                    && excludes.stream().anyMatch(fp::contains)) {
                continue;
            }
            kept.add(o);
        }
        return kept;
    }

    /** Run every detector in {@code family} and merge their {@code findings} into one response. */
    @SuppressWarnings("unchecked")
    private ToolResponse runFamily(IJdtService service, String family, JsonNode arguments) {
        List<String> kinds = catalog.kinds(family);
        if (kinds.isEmpty()) {
            return ToolResponse.invalidParameter("family",
                "Unknown family '" + family + "'. One of: quality, fowler, solid, kerievsky.");
        }
        List<Object> merged = new ArrayList<>();
        for (String k : kinds) {
            ToolResponse r = catalog.get(k).map(d -> d.detect(service, arguments)).orElse(null);
            if (r != null && r.isSuccess() && r.getData() instanceof Map<?, ?> data
                && data.get("findings") instanceof List<?> fs) {
                merged.addAll((List<Object>) fs);
            }
        }
        // v2.8.1: excludePaths filters BEFORE conflicts/counts, so prevalence,
        // conflict list, summary, baseline and pagination all see one set.
        merged = keepUnexcluded(merged, excludePathsOf(arguments));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("family", family);
        out.put("kinds", kinds);
        out.put("count", merged.size());
        out.put("findings", merged);
        out.put("conflicts", conflicts(merged));
        return ToolResponse.success(out, ResponseMeta.builder()
            .totalCount(merged.size()).returnedCount(merged.size()).build());
    }

    /**
     * Sprint 22a R.1 — the multi-catalog conflict seam. Where &ge;2 distinct detectors
     * flag ONE location ({@code filePath|line}), surface the arbitration INPUTS — the
     * detector kinds, their families, and a neutral prevalence signal — and decide
     * NOTHING. Which cure wins is design intent (the caller's), not a code property jawata
     * can read; jawata only makes the collision legible.
     */
    private List<Map<String, Object>> conflicts(List<Object> merged) {
        Map<String, java.util.LinkedHashSet<String>> kindsAt = new LinkedHashMap<>();
        Map<String, Object[]> locOf = new LinkedHashMap<>();
        Map<String, Integer> prevalence = new LinkedHashMap<>();
        for (Object o : merged) {
            if (!(o instanceof Map<?, ?> f)) {
                continue;
            }
            Object kind = f.get("kind");
            Object file = f.get("filePath");
            Object line = f.get("line");
            if (kind == null || file == null || line == null) {
                continue;
            }
            prevalence.merge(String.valueOf(kind), 1, Integer::sum);
            String loc = file + "|" + line;
            kindsAt.computeIfAbsent(loc, k -> new java.util.LinkedHashSet<>()).add(String.valueOf(kind));
            locOf.putIfAbsent(loc, new Object[]{file, line});
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, java.util.LinkedHashSet<String>> e : kindsAt.entrySet()) {
            if (e.getValue().size() < 2) {
                continue; // a conflict needs >=2 distinct detectors at one location
            }
            List<String> detectors = new ArrayList<>(e.getValue());
            java.util.LinkedHashSet<String> families = new java.util.LinkedHashSet<>();
            Map<String, Integer> byPrevalence = new LinkedHashMap<>();
            for (String k : detectors) {
                families.addAll(catalog.familiesOf(k));
                byPrevalence.put(k, prevalence.getOrDefault(k, 0));
            }
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("filePath", locOf.get(e.getKey())[0]);
            conflict.put("line", locOf.get(e.getKey())[1]);
            conflict.put("detectors", detectors);
            conflict.put("families", new ArrayList<>(families));
            Map<String, Object> signal = new LinkedHashMap<>();
            signal.put("prevalence", byPrevalence);
            signal.put("note", "project-wide prevalence of each conflicting kind — a weak INPUT, "
                + "not a decision; jawata arbitrates nothing");
            conflict.put("conventionSignal", signal);
            conflict.put("decision", null);
            out.add(conflict);
        }
        return out;
    }

    /**
     * Sprint 22a 2.6.1 (#1) — bound a whole-family sweep for MCP consumers. A sweep on a
     * large repo produces thousands of findings (222k+ chars) — unconsumable inline.
     * {@code summary=true} returns counts-by-kind + the conflicts (what a broad sweep wants);
     * otherwise the findings are paged (default cap {@link #DEFAULT_FINDINGS_LIMIT}). Applied
     * AFTER the baseline branch, so trend diffing still sees the full finding set.
     */
    @SuppressWarnings("unchecked")
    private ToolResponse paginateFamily(ToolResponse full, JsonNode args) {
        if (!(full.getData() instanceof Map<?, ?> raw)) {
            return full;
        }
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) raw);
        List<Object> findings = data.get("findings") instanceof List<?> l
            ? new ArrayList<>((List<Object>) l) : new ArrayList<>();
        int total = findings.size();

        if (args.path("summary").asBoolean(false)) {
            Map<String, Integer> byKind = new LinkedHashMap<>();
            for (Object o : findings) {
                if (o instanceof Map<?, ?> f && f.get("kind") != null) {
                    byKind.merge(String.valueOf(f.get("kind")), 1, Integer::sum);
                }
            }
            Object conflicts = data.getOrDefault("conflicts", List.of());
            data.remove("findings");
            data.put("summary", true);
            data.put("byKind", byKind);
            data.put("conflictCount", conflicts instanceof List<?> c ? c.size() : 0);
            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total).returnedCount(0).build());
        }

        int offset = Math.max(0, args.path("offset").asInt(0));
        int limit = args.path("limit").asInt(DEFAULT_FINDINGS_LIMIT);
        if (limit <= 0) {
            limit = DEFAULT_FINDINGS_LIMIT;
        }
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        List<Object> page = new ArrayList<>(findings.subList(from, to));
        boolean truncated = to < total || from > 0;
        data.put("count", total);
        data.put("offset", offset);
        data.put("limit", limit);
        data.put("returnedCount", page.size());
        data.put("truncated", truncated);
        data.put("findings", page);
        if (truncated) {
            data.put("hint", "showing findings " + from + "–" + to + " of " + total
                + " — use summary:true for counts+conflicts only, or offset/limit to page");
        }
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(total).returnedCount(page.size()).build());
    }

    /**
     * Sprint 22a P2-c — baseline / trend diffing. Each finding is keyed by
     * {@code kind|filePath|line}. {@code baseline=save} writes the current keys
     * to {@code <root>/.jawata/quality-baseline-<family>.json}; {@code baseline=diff}
     * returns {@code {new, fixed, unchanged}} vs the saved snapshot.
     */
    @SuppressWarnings("unchecked")
    private ToolResponse applyBaseline(IJdtService service, String family, String action,
                                       ToolResponse familyResponse) {
        Map<String, Object> data = (Map<String, Object>) familyResponse.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        java.util.LinkedHashSet<String> current = new java.util.LinkedHashSet<>();
        for (Map<String, Object> f : findings) {
            current.add(f.get("kind") + "|" + f.get("filePath") + "|" + f.get("line"));
        }
        java.nio.file.Path snapshot = service.getProjectRoot()
            .resolve(".jawata").resolve("quality-baseline-" + family + ".json");

        if ("save".equals(action)) {
            try {
                java.nio.file.Files.createDirectories(snapshot.getParent());
                java.nio.file.Files.write(snapshot,
                    (String.join("\n", current) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                return ToolResponse.internalError(e);
            }
            Map<String, Object> out = new LinkedHashMap<>(data);
            out.put("baseline", "saved");
            out.put("baselineSize", current.size());
            return ToolResponse.success(out);
        }

        // diff against the saved snapshot (empty when absent → everything is new).
        java.util.LinkedHashSet<String> previous = new java.util.LinkedHashSet<>();
        try {
            if (java.nio.file.Files.exists(snapshot)) {
                for (String line : java.nio.file.Files.readAllLines(snapshot,
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        previous.add(line.strip());
                    }
                }
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
        List<String> added = new ArrayList<>();
        int unchanged = 0;
        for (String k : current) {
            if (previous.contains(k)) {
                unchanged++;
            } else {
                added.add(k);
            }
        }
        List<String> fixed = new ArrayList<>();
        for (String k : previous) {
            if (!current.contains(k)) {
                fixed.add(k);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("family", family);
        out.put("baseline", "diff");
        out.put("newCount", added.size());
        out.put("fixedCount", fixed.size());
        out.put("unchangedCount", unchanged);
        out.put("new", added);
        out.put("fixed", fixed);
        return ToolResponse.success(out);
    }
}
