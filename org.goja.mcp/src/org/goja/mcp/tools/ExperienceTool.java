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
        List.of("record", "recall", "primer", "list", "load", "reseed", "refresh",
            "wipe", "promote", "export", "import", "prune", "dedup", "compact", "stats");

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private final Supplier<IJdtService> serviceSupplier;
    private final ExperienceStore store;
    private final ExperienceRetrieval retrieval;
    private final ExperienceMaintenance maintenance;

    public ExperienceTool(Supplier<IJdtService> serviceSupplier, ExperienceStore store) {
        this(serviceSupplier, store, List::of);
    }

    /** Sprint 21a (item C): {@code defaultRoots} feed no-path {@code load} / {@code reseed}. */
    public ExperienceTool(Supplier<IJdtService> serviceSupplier, ExperienceStore store,
            Supplier<List<java.nio.file.Path>> defaultRoots) {
        this(serviceSupplier, store, defaultRoots, null);
    }

    /** Sprint 21b (item D): package-private resolver override so tests can simulate staleness. */
    ExperienceTool(Supplier<IJdtService> serviceSupplier, ExperienceStore store,
            Supplier<List<java.nio.file.Path>> defaultRoots,
            ExperienceMaintenance.PointerResolver resolverOverride) {
        this.serviceSupplier = serviceSupplier;
        this.store = store;
        this.retrieval = new ExperienceRetrieval(store, serviceSupplier);
        this.maintenance = new ExperienceMaintenance(store,
            resolverOverride != null ? resolverOverride : this::resolvesViaJdt, defaultRoots,
            serviceSupplier);
    }

    /**
     * Sprint 21b (item D): refresh is MAINTENANCE, not a user decision — run it after
     * project auto-load and after every load/import. Never throws (the startup path must
     * not die on a store/JDT hiccup); failures come back as an {@code error} report.
     */
    public Map<String, Object> autoRefresh() {
        try {
            Map<String, Object> out = new java.util.LinkedHashMap<>(maintenance.refresh());
            // Sprint 21e (item A): refresh first (stale auto-anchors get cleared), then
            // backfill (NULL-anchor entries — incl. freshly cleared ones — re-resolve
            // against the current project set).
            Map<String, Object> backfill = maintenance.backfillAutoAnchors();
            if (backfill.get("checked") instanceof Integer i && i > 0) {
                out.put("anchor_backfill", backfill);
            }
            return out;
        } catch (Exception e) {
            return Map.of("error", "auto-refresh failed: " + e.getMessage());
        }
    }

    /** Attach the automatic post-ingest refresh report to a load/reseed/import response. */
    private Map<String, Object> withRefresh(Map<String, Object> data) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(data);
        out.put("refresh", autoRefresh());
        return out;
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
            - load — seed the store from memory files. Optional: path (a directory of *.md or
              a single file; OMIT to seed from the configured default roots — the layered
              CLAUDE.md set + memory dirs), recursive (default true; false = don't walk
              subdirectories). Ingest FOLLOWS
              the link graph: [[wikilinks]] and relative [x](file.md) links are crawled
              transitively (cycle-safe, bounded, skips reported). Frontmatter
              type/description/symbol/language; entries are accepted/medium; idempotent per
              source (re-load replaces).
            - reseed — the explicit "initial load": wipe EVERYTHING, then load from the
              default roots (or path). REQUIRES confirm:true. Optional: path, recursive.
            - refresh — re-resolve Java symbol pointers through JDT; flag stale (superseded).
              Non-Java anchors are opaque (never staled).
            - wipe — remove everything.
            - promote — set an entry's curation status. Needs: id. Optional: status (default accepted).
            - list — curation view: browse entries by type / status / scope (symbol|package
              prefix) / language, newest first (limit, default 50). Unlike recall this SHOWS
              the set — including rejected/superseded — so candidates can be promoted.
              format="text" for flat lines.
            - export — full-fidelity dump (optionally filtered by status/type). Pass path to
              write a JSON file (backup/sharing), else entries return inline.
            - import — re-ingest exported entries (dedup by id). Pass entries[] inline or
              path to an export file.
            - prune — GC the store: delete rejected/superseded entries older than 'days'
              (default 30; 0 = all of them).
            - dedup — surface near-duplicate active entries (same summary + scope). Reports
              groups; pass confirm:true to MERGE (best survives, rest superseded).
            - compact — reclaim H2 file space after prunes/wipes. Briefly closes the store
              (concurrently attached residents reconnect); run when quiet.
            - stats — store overview: entry counts by status/language + the backing file
              location and size.

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
            "description", "load/reseed: a directory of *.md memory files, or a single file."
                + " Omit to use the configured default roots."));
        props.put("recursive", Map.of("type", "boolean",
            "description", "load/reseed: walk subdirectories of directory roots (default TRUE —"
                + " the crawl finds everything; pass false to stay flat)."));
        props.put("confirm", Map.of("type", "boolean",
            "description", "reseed: REQUIRED true (wipes first). dedup: true = merge the groups."));
        props.put("days", Map.of("type", "integer",
            "description", "prune: age threshold in days for rejected/superseded (default 30)."));
        props.put("id", Map.of("type", "string", "description", "promote: the entry id to re-status."));
        props.put("limit", Map.of("type", "integer",
            "description", "primer: max domain nodes (default 20); list: max rows (default 50)."));
        props.put("format", Map.of("type", "string", "enum", List.of("json", "text"),
            "description", "recall/primer/list: text = flat injection-ready lines; default json."));
        props.put("scope", Map.of("type", "string",
            "description", "list: symbol/package prefix filter."));
        props.put("entries", Map.of("type", "array", "items", Map.of("type", "object"),
            "description", "import: exported entries (inline alternative to path)."));

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
            case "reseed" -> reseed(args);
            case "refresh" -> ToolResponse.success(maintenance.refresh());
            case "wipe" -> wipe();
            case "promote" -> promote(args);
            case "list" -> list(args);
            case "export" -> exportEntries(args);
            case "import" -> importEntries(args);
            case "prune" -> prune(args);
            case "dedup" -> ToolResponse.success(maintenance.dedup(bool(args, "confirm")));
            case "compact" -> ToolResponse.success(store.compact());
            case "stats" -> ToolResponse.success(store.stats());
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }

    private ToolResponse load(JsonNode args) {
        String path = text(args, "path");
        boolean recursive = boolOr(args, "recursive", true);
        if (path == null || path.isBlank()) {
            if (!maintenance.hasDefaultRoots()) {
                return ToolResponse.invalidParameter("path", "load without 'path' needs configured"
                    + " default memory roots (-Dgoja.memory.roots) — none found");
            }
            return ToolResponse.success(withRefresh(maintenance.load(null, recursive)));
        }
        return ToolResponse.success(withRefresh(maintenance.load(Path.of(path), recursive)));
    }

    /**
     * Sprint 21a (item G): the explicit "initial load" — atomic wipe → load-from-defaults
     * (or path). The wipe half is destructive, so {@code confirm:true} is REQUIRED: a
     * prompt-driven "reset my store" can never fire half-armed.
     */
    private ToolResponse reseed(JsonNode args) {
        if (!bool(args, "confirm")) {
            return ToolResponse.invalidParameter("confirm",
                "reseed WIPES the whole store before reloading — pass confirm:true to proceed");
        }
        String path = text(args, "path");
        boolean recursive = boolOr(args, "recursive", true);
        if ((path == null || path.isBlank()) && !maintenance.hasDefaultRoots()) {
            return ToolResponse.invalidParameter("path", "reseed without 'path' needs configured"
                + " default memory roots (-Dgoja.memory.roots) — none found (store NOT wiped)");
        }
        Map<String, Object> wiped = maintenance.wipe();
        Map<String, Object> loaded = maintenance.load(
            path == null || path.isBlank() ? null : Path.of(path), recursive);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("removed", wiped.get("removed"));
        data.putAll(loaded);
        return ToolResponse.success(withRefresh(data));
    }

    /**
     * Sprint 21b: wipe compacts afterwards — H2's MVStore never shrinks on deletes, and
     * "I wiped and the file is still 800k" reads as a bug (Harald, 2026-07-06). Attached
     * peer residents survive the shutdown via the store's self-healing connection.
     */
    private ToolResponse wipe() {
        Map<String, Object> data = new LinkedHashMap<>(maintenance.wipe());
        data.put("compact", store.compact());
        return ToolResponse.success(data);
    }

    private static boolean bool(JsonNode n, String field) {
        return n != null && n.has(field) && n.get(field).asBoolean(false);
    }

    /** Sprint 21b (item C): a boolean with an explicit default when the field is absent. */
    private static boolean boolOr(JsonNode n, String field, boolean absent) {
        return n != null && n.has(field) ? n.get(field).asBoolean(absent) : absent;
    }

    private ToolResponse prune(JsonNode args) {
        int days = args != null && args.has("days") && args.get("days").isInt()
            ? args.get("days").asInt() : 30;
        int removed = store.pruneAged(days);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("removed", removed);
        data.put("days", days);
        return ToolResponse.success(data);
    }

    // --- Sprint 21a (item G): curation verbs -------------------------------------------

    /** Curation browse — recall is terminal-single; promoting needs to SEE the set. */
    private ToolResponse list(JsonNode args) {
        int limit = args != null && args.has("limit") && args.get("limit").isInt()
            ? args.get("limit").asInt() : 50;
        List<org.goja.mcp.knowledge.StoredEntry> rows = store.listEntries(
            text(args, "type"), text(args, "status"), text(args, "scope"),
            text(args, "language"), limit);
        if ("text".equalsIgnoreCase(text(args, "format"))) {
            return ToolResponse.success(ExperienceRetrieval.renderList(rows));
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (org.goja.mcp.knowledge.StoredEntry e : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("type", e.type());
            m.put("status", e.status());
            if (e.language() != null) {
                m.put("language", e.language());
            }
            if (e.symbolFqn() != null) {
                m.put("symbol", e.symbolFqn());
            }
            m.put("summary", e.summary());
            if (e.createdAt() != null) {
                m.put("created_at", e.createdAt().toString());
            }
            entries.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", entries.size());
        data.put("entries", entries);
        return ToolResponse.success(data);
    }

    private ToolResponse exportEntries(JsonNode args) {
        List<Map<String, Object>> entries =
            store.exportEntries(text(args, "status"), text(args, "type"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", entries.size());
        String path = text(args, "path");
        if (path != null && !path.isBlank()) {
            try {
                Map<String, Object> doc = new LinkedHashMap<>();
                doc.put("count", entries.size());
                doc.put("entries", entries);
                java.nio.file.Files.writeString(Path.of(path),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
                data.put("path", path);
                data.put("written", true);
            } catch (Exception e) {
                return ToolResponse.invalidParameter("path",
                    "cannot write export file: " + e.getMessage());
            }
        } else {
            data.put("entries", entries);
        }
        return ToolResponse.success(data);
    }

    @SuppressWarnings("unchecked")
    private ToolResponse importEntries(JsonNode args) {
        List<Map<String, Object>> entries;
        if (args != null && args.has("entries") && args.get("entries").isArray()) {
            entries = (List<Map<String, Object>>) JSON.convertValue(args.get("entries"), List.class);
        } else {
            String path = text(args, "path");
            if (path == null || path.isBlank()) {
                return ToolResponse.invalidParameter("entries",
                    "import needs 'entries' (inline array) or 'path' (an export file)");
            }
            try {
                JsonNode root = JSON.readTree(java.nio.file.Files.readString(Path.of(path)));
                JsonNode arr = root.isArray() ? root : root.path("entries");
                if (!arr.isArray()) {
                    return ToolResponse.invalidParameter("path",
                        "file has no 'entries' array: " + path);
                }
                entries = (List<Map<String, Object>>) JSON.convertValue(arr, List.class);
            } catch (Exception e) {
                return ToolResponse.invalidParameter("path",
                    "cannot read export file: " + e.getMessage());
            }
        }
        return ToolResponse.success(withRefresh(store.importEntries(entries)));
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

    /**
     * Sprint 21e (item B): the classify contract (Recall-Gap §5.5) — a recall MATCH
     * converts the agent's task from GENERATE a cause (fluency wins) to CLASSIFY
     * against the returned closed set (grounding wins). Carried in {@code meta.steering}
     * ONLY: the envelope layer fills steering just when absent, so this per-response
     * line wins over the generic per-tool steering with a single producer; absence
     * responses keep the generic line. The {@code format=text} tail never renders meta
     * — the hooks' peel is byte-identical.
     */
    static final String CLASSIFY_STEERING =
        "Match the observation to ONE of these with evidence, or declare it genuinely new"
        + " — do not generate a novel cause.";

    private ToolResponse recall(JsonNode args) {
        RecallQuery q = new RecallQuery(
            text(args, "symbol"),
            text(args, "package"),
            text(args, "operation"),
            text(args, "symptom"),
            text(args, "external_system"));
        Map<String, Object> result = retrieval.recall(q);
        ToolResponse response = respond(args, result);
        if (ExperienceRetrieval.RESULT_MATCH.equals(result.get("result"))) {
            response.applySteering(CLASSIFY_STEERING);
        }
        return response;
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
