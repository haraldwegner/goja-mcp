package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IType;
import org.goja.core.IJdtService;
import org.goja.mcp.knowledge.Confidence;
import org.goja.mcp.knowledge.ExperienceEntry;
import org.goja.mcp.knowledge.ExperienceMaintenance;
import org.goja.mcp.knowledge.ExperienceRetrieval;
import org.goja.mcp.knowledge.ExperienceStore;
import org.goja.mcp.knowledge.RecallQuery;
import org.goja.mcp.knowledge.SymbolFact;
import org.goja.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 21 (v2.0): the parametric front door over the local experience/knowledge store —
 * {@code experience(kind=...)}. Stage 1 ships {@code kind=record} (write an observation);
 * later stages add {@code recall} (Stage 2 — needs the JDT service, hence the supplier)
 * and {@code load}/{@code wipe}/{@code refresh} (Stage 4). One tool keeps the surface
 * small while the store's verbs grow. Implements {@link Tool} directly (not
 * {@code AbstractTool}) because a store write does not require a loaded project.
 */
public final class ExperienceTool implements Tool {

    private static final List<String> KINDS =
        List.of("record", "recall", "primer", "load", "refresh", "wipe", "promote");

    private final Supplier<IJdtService> serviceSupplier;
    private final ExperienceStore store;
    private final ExperienceRetrieval retrieval;
    private final ExperienceMaintenance maintenance;

    public ExperienceTool(Supplier<IJdtService> serviceSupplier, ExperienceStore store) {
        this.serviceSupplier = serviceSupplier;
        this.store = store;
        this.retrieval = new ExperienceRetrieval(store, serviceSupplier);
        this.maintenance = new ExperienceMaintenance(store, this::resolvesViaJdt);
    }

    /** Bridge the JDT service to the maintenance resolver (TRUE=resolves, FALSE=stale, null=no project). */
    private Boolean resolvesViaJdt(String symbolFqn) {
        IJdtService s = serviceSupplier.get();
        if (s == null) {
            return null;
        }
        String typeName = symbolFqn.contains("#") ? symbolFqn.substring(0, symbolFqn.indexOf('#')) : symbolFqn;
        try {
            IType t = s.findType(typeName);
            return t != null && t.exists();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getName() {
        return "experience";
    }

    @Override
    public String getDescription() {
        return """
            Local experience/knowledge store — record and recall grounded lessons, domain
            facts, failure modes and hazards for THIS codebase.

            USAGE: experience(kind="record", type, summary, ...)
                   experience(kind="recall", symbol?/package?/operation?/symptom?/external_system?)

            Kinds:
            - record — store an observation as a candidate entry. Needs: type, summary.
              Optional: confidence (low|medium|high); anchor with symbol (FQN) OR
              packages[]/symbols[]; language (anchor language, default java — non-Java
              anchors are never staled by JDT maintenance); details, operation, scope_kind,
              symptoms[], links[{rel,target}], fault_owner, external_system, status,
              exceptions[].
            - recall — TERMINAL retrieval for a cue. Give any of symbol / package / operation
              / symptom / external_system. Returns exactly the fitting node(s) with pointers
              resolved to current code, OR an authoritative absence — never a similarity pile.
              Pass format="text" for flat, injection-ready lines (else structured JSON).
            - primer — the always-on DOMAIN layer: accepted domain nodes for a SessionStart
              orientation. Optional: limit (default 20), format="text".
            - load — seed the store from memory files. Needs: path (a directory of *.md or a
              single file). Frontmatter type/description/symbol + [[wikilinks]]; entries are
              accepted/medium; idempotent per source (re-load replaces).
            - refresh — re-resolve every symbol pointer through JDT; flag stale (superseded).
            - wipe — remove everything.
            - promote — set an entry's curation status. Needs: id. Optional: status (default accepted).

            The store is local + workspace-scoped. Record after a surprising failure, a
            discovered invariant, or a hazard the compiler cannot tell you; recall before a
            refactor or before asserting a root cause.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", KINDS);
        kind.put("description", "Which store operation: record (write) or recall (terminal retrieval).");
        props.put("kind", kind);

        // recall cues (singular; record uses packages[]/symbols[]/symptoms[]).
        props.put("package", Map.of("type", "string",
            "description", "recall: a package you are working in (cue)."));
        props.put("symptom", Map.of("type", "string",
            "description", "recall: an observed symptom / cue phrase (alias-normalized)."));

        // maintenance
        props.put("path", Map.of("type", "string",
            "description", "load: a directory of *.md memory files, or a single file."));
        props.put("id", Map.of("type", "string", "description", "promote: the entry id to re-status."));
        props.put("limit", Map.of("type", "integer", "description", "primer: max domain nodes (default 20)."));
        props.put("format", Map.of("type", "string", "enum", List.of("json", "text"),
            "description", "recall/primer: text = flat injection-ready lines; default json."));

        props.put("type", Map.of("type", "string",
            "description", "record: entry type (domain_fact / lesson / failure_mode / api_contract / naming_convention / ...)."));
        props.put("summary", Map.of("type", "string", "description", "record: one-line summary (required)."));
        props.put("confidence", Map.of("type", "string", "enum", List.of("low", "medium", "high"),
            "description", "record: default medium."));
        props.put("symbol", Map.of("type", "string",
            "description", "record: anchor FQN (mutually exclusive with packages/symbols)."));
        props.put("language", Map.of("type", "string",
            "description", "record: anchor language (default java). Non-Java anchors (rust, ts, ...)"
                + " are opaque to JDT maintenance — stored + recalled, never staled."));
        props.put("packages", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "record: scope packages."));
        props.put("symbols", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "record: scope symbols."));
        props.put("details", Map.of("type", "string", "description", "record: longer detail."));
        props.put("operation", Map.of("type", "string", "description", "record: operation this entry relates to."));
        props.put("scope_kind", Map.of("type", "string",
            "description", "record: symbol|package|operation|symptom|external_system|..."));
        props.put("symptoms", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "record: observed symptoms (alias-normalized)."));
        props.put("links", Map.of("type", "array",
            "items", Map.of("type", "object"),
            "description", "record: typed edges [{rel: handled_by|fixed_by|detected_by|supersedes, target}]."));
        props.put("fault_owner", Map.of("type", "string", "enum", List.of("internal", "external", "shared"),
            "description", "record: who owns the fault."));
        props.put("external_system", Map.of("type", "string",
            "description", "record: the external dependency, when the fault is external."));
        props.put("status", Map.of("type", "string", "description", "record: default candidate."));
        props.put("exceptions", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "record: exceptions / caveats."));

        schema.put("properties", props);
        schema.put("required", List.of("kind"));
        return schema;
    }

    @Override
    public ToolResponse execute(JsonNode args) {
        String kind = text(args, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + KINDS);
        }
        return switch (kind) {
            case "record" -> record(args);
            case "recall" -> recall(args);
            case "primer" -> primer(args);
            case "load" -> load(args);
            case "refresh" -> ToolResponse.success(maintenance.refresh());
            case "wipe" -> ToolResponse.success(maintenance.wipe());
            case "promote" -> promote(args);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }

    private ToolResponse load(JsonNode args) {
        String path = text(args, "path");
        if (path == null || path.isBlank()) {
            return ToolResponse.invalidParameter("path", "load requires a 'path'");
        }
        return ToolResponse.success(maintenance.load(Path.of(path)));
    }

    private ToolResponse promote(JsonNode args) {
        String id = text(args, "id");
        if (id == null || id.isBlank()) {
            return ToolResponse.invalidParameter("id", "promote requires an entry 'id'");
        }
        String status = text(args, "status");
        String target = status == null || status.isBlank() ? ExperienceEntry.ACCEPTED : status;
        boolean changed = store.setStatus(id, target);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("status", target);
        data.put("changed", changed);
        return ToolResponse.success(data);
    }

    private ToolResponse recall(JsonNode args) {
        RecallQuery q = new RecallQuery(
            text(args, "symbol"),
            text(args, "package"),
            text(args, "operation"),
            text(args, "symptom"),
            text(args, "external_system"));
        return respond(args, retrieval.recall(q));
    }

    private ToolResponse primer(JsonNode args) {
        int limit = args != null && args.has("limit") && args.get("limit").isInt()
            ? args.get("limit").asInt() : 20;
        return respond(args, retrieval.primer(limit));
    }

    /** Structured JSON by default; {@code format=text} returns flat injection-ready lines. */
    private ToolResponse respond(JsonNode args, Map<String, Object> result) {
        if ("text".equalsIgnoreCase(text(args, "format"))) {
            return ToolResponse.success(ExperienceRetrieval.renderText(result));
        }
        return ToolResponse.success(result);
    }

    private ToolResponse record(JsonNode args) {
        String type = text(args, "type");
        String summary = text(args, "summary");
        if (type == null || type.isBlank()) {
            return ToolResponse.invalidParameter("type", "record requires a 'type'");
        }
        if (summary == null || summary.isBlank()) {
            return ToolResponse.invalidParameter("summary", "record requires a 'summary'");
        }

        SymbolFact.Builder fb = SymbolFact.of(type, summary, confidence(text(args, "confidence")));
        String symbol = text(args, "symbol");
        List<String> packages = strings(args, "packages");
        List<String> symbols = strings(args, "symbols");
        if (symbol != null && !symbol.isBlank()) {
            fb.symbol(symbol);
        } else if (!packages.isEmpty() || !symbols.isEmpty()) {
            fb.scope(packages, symbols);
        }
        String details = text(args, "details");
        if (details != null && !details.isBlank()) {
            fb.details(details);
        }
        List<String> exceptions = strings(args, "exceptions");
        if (!exceptions.isEmpty()) {
            fb.exceptions(exceptions);
        }

        ExperienceEntry.Builder eb = ExperienceEntry.of(fb.build())
            .status(text(args, "status"))
            .scopeKind(text(args, "scope_kind"))
            .operation(text(args, "operation"))
            .symptoms(strings(args, "symptoms"))
            .faultOwner(text(args, "fault_owner"))
            .externalSystem(text(args, "external_system"))
            .language(text(args, "language"));
        if (args != null && args.has("links") && args.get("links").isArray()) {
            for (JsonNode l : args.get("links")) {
                String rel = l.path("rel").asText(null);
                String target = l.path("target").asText(null);
                if (rel != null && target != null) {
                    eb.addLink(rel, target);
                }
            }
        }

        ExperienceEntry entry = eb.build();
        String id = store.put(entry);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("status", entry.status());
        data.put("stored", true);
        return ToolResponse.success(data);
    }

    private static Confidence confidence(String s) {
        if (s == null || s.isBlank()) {
            return Confidence.MEDIUM;
        }
        try {
            return Confidence.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Confidence.MEDIUM;
        }
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        return n.get(field).asText();
    }

    private static List<String> strings(JsonNode n, String field) {
        List<String> out = new ArrayList<>();
        if (n != null && n.has(field) && n.get(field).isArray()) {
            for (JsonNode item : n.get(field)) {
                if (!item.isNull()) {
                    out.add(item.asText());
                }
            }
        }
        return out;
    }
}
