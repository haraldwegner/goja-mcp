package org.goja.mcp.knowledge;

import org.goja.core.IJdtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Sprint 21 Stage 4 — store maintenance: {@code initial_load} (seed from memory files),
 * {@code refresh} (re-resolve pointers through JDT, flag stale), {@code wipe}. Decoupled
 * from JDT via an injected {@link PointerResolver} so staleness is unit-testable; the JDT
 * coupling is a one-line lambda at the construction site.
 */
public final class ExperienceMaintenance {

    private static final Logger log = LoggerFactory.getLogger(ExperienceMaintenance.class);
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]]+)\\]\\]");
    /** Relative markdown links to .md files — the MEMORY.md index convention. */
    private static final Pattern MD_LINK = Pattern.compile("\\]\\(([^)#?:]+\\.md)\\)");

    // Sprint 21c (item A): the cue-dense body structure the symptom matcher never saw.
    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+(.+)$");
    private static final Pattern BOLD = Pattern.compile("\\*\\*([^*\\n]+)\\*\\*");
    private static final Pattern CODE_SPAN = Pattern.compile("`([^`\\n]+)`");
    /** C4 live-gate finding: quoted error strings ("Lock file recently modified") are
     *  THE classic symptom cue and lived only in prose details — harvest them too. */
    private static final Pattern QUOTED = Pattern.compile("\"([^\"\\n]{3,80})\"");
    /** Keyword-harvest backstop per entry — hitting it is reported, never silent. */
    static final int MAX_KEYWORDS_PER_ENTRY = 30;

    // Sprint 21b (item C): the crawl finds EVERYTHING reachable — these are runaway
    // BACKSTOPS (pathological trees/cycles), not tuning values, and have no UI. The
    // -Dgoja.memory.max* properties remain honored; a fired backstop is still reported.
    static final int DEFAULT_MAX_DEPTH = 32;
    static final int DEFAULT_MAX_FILES = 10_000;
    static final long DEFAULT_MAX_BYTES = 268_435_456L;

    static int maxDepth() {
        return Integer.getInteger("goja.memory.maxDepth", DEFAULT_MAX_DEPTH);
    }

    static int maxFiles() {
        return Integer.getInteger("goja.memory.maxFiles", DEFAULT_MAX_FILES);
    }

    static long maxBytes() {
        return Long.getLong("goja.memory.maxBytes", DEFAULT_MAX_BYTES);
    }

    /** Resolve a symbol pointer: {@code TRUE}=resolves, {@code FALSE}=stale, {@code null}=unknown (no project). */
    @FunctionalInterface
    public interface PointerResolver {
        Boolean resolves(String symbolFqn);
    }

    private final ExperienceStore store;
    private final PointerResolver resolver;
    private final Supplier<List<Path>> defaultRoots;
    /** Sprint 21e (item A): JDT access for ingest-time anchor RESOLUTION; null = no auto-anchoring. */
    private final Supplier<IJdtService> anchorService;

    public ExperienceMaintenance(ExperienceStore store, PointerResolver resolver) {
        this(store, resolver, List::of);
    }

    /** Sprint 21a (item C): {@code defaultRoots} feed the no-path {@code load}/{@code reseed}. */
    public ExperienceMaintenance(ExperienceStore store, PointerResolver resolver,
            Supplier<List<Path>> defaultRoots) {
        this(store, resolver, defaultRoots, null);
    }

    /** Sprint 21e (item A): {@code anchorService} enables ingest-time symbol-anchor resolution. */
    public ExperienceMaintenance(ExperienceStore store, PointerResolver resolver,
            Supplier<List<Path>> defaultRoots, Supplier<IJdtService> anchorService) {
        this.store = store;
        this.resolver = resolver == null ? fqn -> null : resolver;
        this.defaultRoots = defaultRoots == null ? List::of : defaultRoots;
        this.anchorService = anchorService;
    }

    /** True when at least one configured default root exists on disk. */
    public boolean hasDefaultRoots() {
        return defaultRoots.get().stream().anyMatch(Files::exists);
    }

    // --- load ---------------------------------------------------------------------------

    public Map<String, Object> load(Path path) {
        return load(path, false);
    }

    /**
     * Seed the store from memory files. {@code path} = a directory of {@code *.md} or a
     * single file; {@code null} = the configured default roots (layered {@code CLAUDE.md}
     * set + memory dirs). Frontmatter {@code type} → entry type, {@code description} →
     * summary, {@code symbol} → JDT-resolved pointer (Java anchors only, item I),
     * {@code [[wikilinks]]} → {@code related} edges. Entries are {@code accepted}/medium;
     * idempotent by absolute source path.
     *
     * <p>Sprint 21a (item C): ingest follows the LINK GRAPH — {@code [[wikilinks]]} and
     * relative {@code [x](file.md)} links are resolved and crawled transitively (how our
     * memory is actually organized: an index plus cross-linked fact files), cycle-safe and
     * bounded (depth / file-count / byte caps; skips are reported, never silent).
     * {@code recursive} additionally walks subdirectories of directory roots.</p>
     */
    public Map<String, Object> load(Path path, boolean recursive) {
        if (path == null) {
            List<Path> roots = defaultRoots.get().stream().filter(Files::exists).toList();
            if (roots.isEmpty()) {
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("loaded", 0);
                report.put("error", "no path given and no default memory roots configured"
                    + " (set -Dgoja.memory.roots or pass a path)");
                return report;
            }
            return loadSources(roots, recursive, maxDepth(), maxFiles(), maxBytes());
        }
        if (!Files.exists(path)) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("loaded", 0);
            report.put("error", "path does not exist");
            return report;
        }
        return loadSources(List.of(path), recursive, maxDepth(), maxFiles(), maxBytes());
    }

    /** Crawl + ingest; package-private so tests can exercise the caps directly. */
    Map<String, Object> loadSources(List<Path> roots, boolean recursive,
            int maxDepth, int maxFiles, long maxBytes) {
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> stale = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        List<Path> rootDirs = new ArrayList<>();

        record Item(Path file, int depth) {}
        java.util.ArrayDeque<Item> queue = new java.util.ArrayDeque<>();
        java.util.Set<Path> seen = new java.util.HashSet<>();

        for (Path root : roots) {
            if (Files.isDirectory(root)) {
                rootDirs.add(root);
                try (Stream<Path> s = recursive ? Files.walk(root, Math.max(1, maxDepth)) : Files.list(root)) {
                    // Sprint 21b (item C2): .mdc = Cursor project rules; explicit FILE
                    // roots (.cursorrules etc.) ingest regardless of extension.
                    s.filter(Files::isRegularFile)
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return n.endsWith(".md") || n.endsWith(".mdc");
                        })
                        .sorted()
                        .forEach(p -> queue.add(new Item(p, 0)));
                } catch (IOException e) {
                    skipped.add(Map.of("source", root.toString(), "reason", "cannot list: " + e.getMessage()));
                }
            } else if (Files.isRegularFile(root)) {
                queue.add(new Item(root, 0));
                if (root.getParent() != null) {
                    rootDirs.add(root.getParent());
                }
            } else {
                skipped.add(Map.of("source", root.toString(), "reason", "does not exist"));
            }
        }

        int loaded = 0;
        int unchanged = 0;
        int linked = 0;
        int keywordCapped = 0;
        int anchored = 0;
        long bytes = 0;
        // Sprint 21e (item A): one resolver per load run — its token memo spans the run
        // but never outlives project loads/removals.
        SymbolAnchorResolver anchors =
            anchorService == null ? null : new SymbolAnchorResolver(anchorService);
        while (!queue.isEmpty()) {
            Item item = queue.poll();
            Path f = item.file().toAbsolutePath().normalize();
            if (!seen.add(f)) {
                continue;                            // cycle-safe / dedup
            }
            if (loaded + unchanged >= maxFiles) {
                skipped.add(Map.of("source", f.toString(), "reason", "max-files (" + maxFiles + ")"));
                continue;
            }
            String content;
            try {
                long size = Files.size(f);
                if (bytes + size > maxBytes) {
                    skipped.add(Map.of("source", f.toString(), "reason", "max-bytes (" + maxBytes + ")"));
                    continue;
                }
                content = Files.readString(f);
                bytes += size;
            } catch (IOException e) {
                log.warn("load: cannot read {}: {}", f, e.getMessage());
                skipped.add(Map.of("source", f.toString(), "reason", "unreadable: " + e.getMessage()));
                continue;
            }

            MemoryDoc doc = parse(content, f.getFileName().toString());

            // v2.2.3: MEMORY.md-style indexes and empty files are link hubs, not
            // knowledge — follow their links, never ingest a junk row.
            boolean indexFile = "MEMORY.md".equalsIgnoreCase(f.getFileName().toString());
            if (indexFile || !doc.hasContent()) {
                for (Path t : resolveLinks(doc, f.getParent(), rootDirs)) {
                    Path norm = t.toAbsolutePath().normalize();
                    if (!seen.contains(norm) && item.depth() < maxDepth) {
                        queue.add(new Item(norm, item.depth() + 1));
                        linked++;
                    }
                }
                continue;
            }
            String sourceRef = "memory:" + f;

            // Sprint 21b: an unchanged source causes NO write at all (the delete+insert
            // churn grew the MVStore file on every load). Links are still followed —
            // an unchanged index can point at new files.
            String hash = sourceHash(content);
            if (store.sourceUnchanged(sourceRef, hash)) {
                unchanged++;
                List<Path> unchangedTargets = resolveLinks(doc, f.getParent(), rootDirs);
                for (Path t : unchangedTargets) {
                    Path norm = t.toAbsolutePath().normalize();
                    if (!seen.contains(norm) && item.depth() < maxDepth) {
                        queue.add(new Item(norm, item.depth() + 1));
                        linked++;
                    }
                }
                continue;
            }
            store.deleteBySource(sourceRef);         // idempotent re-seed

            boolean split = !doc.sections.isEmpty();
            SymbolFact.Builder fb = SymbolFact.of(
                doc.type == null ? "note" : doc.type,
                doc.summary(), Confidence.MEDIUM);
            if (doc.symbol != null) {
                fb.symbol(doc.symbol);
            }
            // Sprint 21c (item B): with sections, the parent is THIN — its details are
            // the preamble only; the body bulk lives in the section entries.
            String parentDetails = (split ? doc.preamble : doc.body).strip();
            if (!parentDetails.isBlank()) {
                fb.details(parentDetails);
            }
            ExperienceEntry.Builder eb = ExperienceEntry.of(fb.build())
                .status(ExperienceEntry.ACCEPTED)
                .language(doc.language);
            // v2.2.5 (find #13): the NAME is where cue-dense phrasing lives ("…renders
            // blank on aarch64") — index it as a symptom so recall can reach it.
            if (doc.name != null && !doc.name.isBlank()) {
                eb.addSymptom(doc.name);
            }
            // Sprint 21c (item A): the harvested keyword surface — headings, bold
            // phrases, backticked terms, wikilink names — becomes symptom rows.
            if (addKeywords(eb, doc.keywords)) {
                keywordCapped++;
            }
            for (String link : doc.links) {
                eb.addLink("related", link);
            }
            String parentId = store.putWithSource(eb.build(), sourceRef, hash);
            // Sprint 21e (item A): frontmatter symbol wins UNCHANGED (asserted, in the
            // fact map); only anchor-less parents get the resolution-gated AUTO anchor —
            // written COLUMN-ONLY so body_json keeps no `symbol` key (the provenance
            // marker refresh() distinguishes on).
            if (doc.symbol == null) {
                anchored += autoAnchor(anchors, parentId, split ? doc.preamble : doc.body, doc.language);
            }
            // Sprint 21c (item B): one entry per section — the atomic FACT the fit
            // gate answers with. The whole family shares the file-level source_ref +
            // source_hash, so skip-unchanged and deleteBySource stay untouched.
            for (Section s : doc.sections) {
                SymbolFact.Builder sf = SymbolFact.of(
                    doc.type == null ? "note" : doc.type, s.heading(), Confidence.MEDIUM);
                if (!s.body().isBlank()) {
                    sf.details(s.body().strip());
                }
                ExperienceEntry.Builder sb = ExperienceEntry.of(sf.build())
                    .status(ExperienceEntry.ACCEPTED)
                    .language(doc.language)
                    .scopeKind("section");
                if (addKeywords(sb, s.keywords())) {
                    keywordCapped++;
                }
                for (String link : s.links()) {
                    sb.addLink("related", link);
                }
                String sectionId = store.putWithSource(sb.build(), sourceRef, hash);
                // Sections cannot carry frontmatter — the auto-anchor from their OWN
                // text is their only symbol channel (the ORB book-flatten gap).
                anchored += autoAnchor(anchors, sectionId, s.heading() + "\n" + s.body(), doc.language);
            }
            loaded++;

            // Item I: only Java anchors are judged by the JDT resolver on ingest.
            boolean javaAnchor = doc.language == null || doc.language.isBlank()
                || "java".equalsIgnoreCase(doc.language);
            if (doc.symbol != null && javaAnchor
                    && Boolean.FALSE.equals(resolver.resolves(doc.symbol))) {
                stale.add(Map.of("source", f.getFileName().toString(), "symbol", doc.symbol));
            }

            // Item C: follow the link graph.
            List<Path> targets = resolveLinks(doc, f.getParent(), rootDirs);
            if (!targets.isEmpty() && item.depth() >= maxDepth) {
                skipped.add(Map.of("source", f.toString(),
                    "reason", "max-depth (" + maxDepth + ") — " + targets.size() + " link(s) not followed"));
                continue;
            }
            for (Path t : targets) {
                Path norm = t.toAbsolutePath().normalize();
                if (!seen.contains(norm)) {
                    queue.add(new Item(norm, item.depth() + 1));
                    linked++;
                }
            }
        }

        report.put("files", loaded + unchanged);
        report.put("loaded", loaded);
        report.put("unchanged", unchanged);
        report.put("linked", linked);
        report.put("stale", stale);
        report.put("skipped", skipped);
        if (keywordCapped > 0) {
            report.put("keyword_capped", keywordCapped);
        }
        if (anchored > 0) {
            report.put("anchored", anchored);
        }
        if (!skipped.isEmpty()) {
            log.info("load: {} source(s) skipped: {}", skipped.size(), skipped);
        }
        return report;
    }

    /**
     * Sprint 21e (item A): resolution-gated AUTO anchor, written COLUMN-ONLY via
     * {@link ExperienceStore#updateSymbolAnchor} — never through the fact map, so the
     * frozen {@code body_json} carries no {@code symbol} key for auto-anchored entries.
     * Java-language entries only (non-Java anchors are opaque to JDT by contract).
     */
    private int autoAnchor(SymbolAnchorResolver anchors, String entryId, String text, String language) {
        if (anchors == null || entryId == null || text == null || text.isBlank()) {
            return 0;
        }
        boolean java = language == null || language.isBlank() || "java".equalsIgnoreCase(language);
        if (!java) {
            return 0;
        }
        return anchors.resolve(text)
            .map(fqn -> store.updateSymbolAnchor(entryId, fqn) ? 1 : 0)
            .orElse(0);
    }

    /**
     * v2.2.6 (find #14): the ingest-semantics version, baked into the skip-unchanged
     * hash. BUMP THIS whenever what the loader EXTRACTS from a file changes (new facets,
     * symptom derivation, summary rules …) — every stored hash then goes stale by
     * definition and the next load re-ingests the whole corpus once, self-healing
     * retroactively. Within one version, idempotency stays byte-strict.
     * History: 1 = content-only (v2.2.1) · 2 = name-as-symptom (v2.2.6) ·
     * 3 = HTML comments are not content (derived summaries skipped "&lt;!-- … --&gt;"
     * managed-section markers in CLAUDE.md files) · 4 = body keyword harvest
     * (Sprint 21c item A: headings/bold/backticks/wikilinks → symptom rows) ·
     * 5 = ingest-time symbol-anchor resolution (Sprint 21e item A: backticked
     * tokens JDT-resolved to unique project-source types, column-only).
     */
    static final int LOADER_VERSION = 5;

    /** The skip-unchanged key: SHA-256 of the file content + the loader fingerprint. */
    static String sourceHash(String content) {
        return sha256(content + ";loader=" + LOADER_VERSION);
    }

    /** SHA-256 of the raw file content — the skip-unchanged key (Sprint 21b). */
    private static String sha256(String content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** {@code [[name]]} → {@code <dir>/name.md} (containing dir first, then root dirs);
     *  {@code [x](rel/path.md)} → resolved against the containing dir. Existing files only. */
    private static List<Path> resolveLinks(MemoryDoc doc, Path containingDir, List<Path> rootDirs) {
        List<Path> out = new ArrayList<>();
        for (String name : doc.links) {
            String file = name.endsWith(".md") ? name : name + ".md";
            List<Path> candidates = new ArrayList<>();
            if (containingDir != null) {
                candidates.add(containingDir.resolve(file));
            }
            for (Path rd : rootDirs) {
                candidates.add(rd.resolve(file));
            }
            candidates.stream().filter(Files::isRegularFile).findFirst().ifPresent(out::add);
        }
        if (containingDir != null) {
            for (String rel : doc.fileLinks) {
                Path t = containingDir.resolve(rel).normalize();
                if (Files.isRegularFile(t)) {
                    out.add(t);
                }
            }
        }
        return out;
    }

    // --- refresh ------------------------------------------------------------------------

    /**
     * Re-resolve every symbol pointer through JDT. A pointer that no longer resolves is
     * flagged {@code superseded} (dropped from recall) and reported. When no project is
     * loaded, resolution is skipped (nothing is flagged).
     *
     * <p>Sprint 21a (item I): only {@code language=java} anchors are judged — a JDT
     * resolver can never see a Rust/TS anchor, so non-Java entries are opaque here
     * (reported as {@code non_java}, never superseded).</p>
     */
    public Map<String, Object> refresh() {
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> staled = new ArrayList<>();
        List<Map<String, Object>> cleared = new ArrayList<>();
        int checked = 0;
        int resolved = 0;
        int skipped = 0;
        int nonJava = 0;
        int foreign = 0;
        String ownWorkspace = store.provenanceWorkspaceId();
        for (StoredEntry e : store.all()) {
            String fqn = e.symbolFqn();
            if (fqn == null || fqn.isBlank()) {
                continue;
            }
            // Sprint 21b: only ACTIVE entries are judged. Re-superseding an already
            // superseded entry wrote an UPDATE per entry per refresh — with refresh now
            // automatic after every load, that grew the store file on every click.
            if (!ExperienceEntry.ACCEPTED.equals(e.status())
                    && !ExperienceEntry.CANDIDATE.equals(e.status())) {
                continue;
            }
            // v2.5.1: workspace-scoped judgment on the SHARED store — a resident may
            // judge ONLY anchors stamped with its own workspace. Judging a foreign
            // workspace's anchors against the wrong project set superseded 304 live
            // entries (2026-07-08). Unstamped entries count as foreign too when the
            // store has an identity; a store WITHOUT identity (tests, standalone)
            // keeps the judge-everything semantics.
            if (ownWorkspace != null && !ownWorkspace.equals(e.workspaceId())) {
                foreign++;
                continue;
            }
            if (!e.isJavaResolvable()) {
                nonJava++;                         // opaque anchor — never staled by JDT
                continue;
            }
            checked++;
            Boolean ok = resolver.resolves(fqn);
            if (ok == null) {
                skipped++;                         // no project / unknown — do not flag
            } else if (ok) {
                resolved++;
            } else if (isAssertedAnchor(e)) {
                // The author asserted this pointer (frontmatter `symbol:` or agent
                // `record(symbol=…)` — the fact-map key in the frozen body) — an
                // unresolvable assertion supersedes the entry, unchanged semantics.
                store.setStatus(e.id(), ExperienceEntry.SUPERSEDED);
                staled.add(Map.of("id", e.id(), "symbol", fqn));
            } else {
                // Sprint 21e (item A): an AUTO anchor (column set, fact-map key absent
                // — all section entries included) is CLEARED, never used to supersede:
                // the lesson outlives its pointer. Backfill may re-anchor it later.
                store.updateSymbolAnchor(e.id(), null);
                cleared.add(Map.of("id", e.id(), "symbol", fqn));
            }
        }
        report.put("checked", checked);
        report.put("resolved", resolved);
        report.put("skipped", skipped);
        report.put("non_java", nonJava);
        report.put("staled", staled);
        if (foreign > 0) {
            report.put("foreign", foreign);
        }
        if (!cleared.isEmpty()) {
            report.put("cleared", cleared);
        }
        return report;
    }

    /** Sprint 21e (item A): asserted anchor = the fact-map {@code symbol} key survives in
     *  the frozen {@code body_json}; auto-anchors are written column-only and never do. */
    private static boolean isAssertedAnchor(StoredEntry e) {
        return e.body() != null && e.body().containsKey("symbol");
    }

    /**
     * Sprint 21e (item A): anchor BACKFILL — memory usually loads BEFORE projects, so
     * ingest-time resolution had no JDT to ask. Runs in the post-project-load refresh
     * path (startup auto-load AND tool-initiated {@code load_project}/{@code project}
     * mutations): every NULL-anchor active Java entry gets one resolution pass over its
     * frozen text; a hit writes the {@code symbol_fqn} column ONLY ({@code source_hash}
     * untouched — byte-strict skip-unchanged never sees backfill as change). Idempotent:
     * anchored entries are never revisited.
     */
    public Map<String, Object> backfillAutoAnchors() {
        Map<String, Object> report = new LinkedHashMap<>();
        long start = System.nanoTime();
        int checked = 0;
        int anchored = 0;
        if (anchorService != null && anchorService.get() != null) {
            SymbolAnchorResolver anchors = new SymbolAnchorResolver(anchorService);
            String ownWorkspace = store.provenanceWorkspaceId();
            for (StoredEntry e : store.all()) {
                if (e.symbolFqn() != null && !e.symbolFqn().isBlank()) {
                    continue;
                }
                if (!ExperienceEntry.ACCEPTED.equals(e.status())
                        && !ExperienceEntry.CANDIDATE.equals(e.status())) {
                    continue;
                }
                // v2.5.1: backfill is workspace-scoped like refresh — anchoring a
                // foreign workspace's entry against THIS workspace's types could
                // mis-anchor on common simple names; absence beats a wrong pointer.
                if (ownWorkspace != null && !ownWorkspace.equals(e.workspaceId())) {
                    continue;
                }
                if (!e.isJavaResolvable()) {
                    continue;
                }
                String text = anchorText(e);
                if (text.isBlank()) {
                    continue;
                }
                checked++;
                anchored += anchors.resolve(text)
                    .map(fqn -> store.updateSymbolAnchor(e.id(), fqn) ? 1 : 0)
                    .orElse(0);
            }
        }
        report.put("checked", checked);
        report.put("anchored", anchored);
        report.put("duration_ms", (System.nanoTime() - start) / 1_000_000);
        return report;
    }

    /** The entry's own text surface for anchor resolution: summary + frozen details. */
    private static String anchorText(StoredEntry e) {
        Object details = e.body() == null ? null : e.body().get("details");
        String summary = e.summary() == null ? "" : e.summary();
        return details == null ? summary : summary + "\n" + details;
    }

    // --- wipe ---------------------------------------------------------------------------

    public Map<String, Object> wipe() {
        long removed = store.wipe();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("removed", removed);
        return report;
    }

    // --- dedup (Sprint 21a, item G) -------------------------------------------------------

    /**
     * Surface near-duplicate ACTIVE entries — same alias-normalized summary + same
     * symbol/package scope. Without {@code merge} this only REPORTS the groups; with it,
     * the best entry survives ({@code accepted} beats {@code candidate}, then newest) and
     * the rest are flagged {@code superseded} (dropped from recall; {@code prune} removes
     * them later — nothing is deleted here).
     */
    public Map<String, Object> dedup(boolean merge) {
        Map<String, List<StoredEntry>> groups = new LinkedHashMap<>();
        for (StoredEntry e : store.all()) {
            if (ExperienceEntry.REJECTED.equals(e.status())
                    || ExperienceEntry.SUPERSEDED.equals(e.status())) {
                continue;
            }
            // Sprint 21c: file-backed entries are family-idempotent (the next load
            // resets them) and generic section headings repeat across files — merging
            // them is hazardous AND pointless. Files are the source of truth; fix
            // duplicates in the files. Dedup's domain is RECORDED entries.
            if (e.sourceRef() != null) {
                continue;
            }
            String key = H2ExperienceStore.normalize(e.summary() == null ? "" : e.summary())
                + "|" + (e.symbolFqn() == null ? "" : e.symbolFqn())
                + "|" + (e.packageName() == null ? "" : e.packageName());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        int merged = 0;
        for (List<StoredEntry> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            StoredEntry keep = group.stream()
                .max(java.util.Comparator
                    .<StoredEntry>comparingInt(e -> ExperienceEntry.ACCEPTED.equals(e.status()) ? 1 : 0)
                    .thenComparing(e -> e.createdAt() == null ? java.time.Instant.EPOCH : e.createdAt()))
                .orElseThrow();
            List<String> duplicates = new ArrayList<>();
            for (StoredEntry e : group) {
                if (!e.id().equals(keep.id())) {
                    duplicates.add(e.id());
                    if (merge) {
                        store.setStatus(e.id(), ExperienceEntry.SUPERSEDED);
                        merged++;
                    }
                }
            }
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("summary", keep.summary());
            g.put("keep", keep.id());
            g.put("duplicates", duplicates);
            out.add(g);
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("groups", out);
        report.put("group_count", out.size());
        report.put("merged", merged);
        return report;
    }

    // --- frontmatter parsing ------------------------------------------------------------

    private record MemoryDoc(String name, String description, String type, String symbol,
                             String language, String body, List<String> links,
                             List<String> fileLinks, List<String> keywords,
                             String preamble, List<Section> sections) {
        /** Summary = description, else the frontmatter name, else "(untitled)". */
        String summary() {
            if (description != null && !description.isBlank()) {
                return description;
            }
            // v2.2.3: CLAUDE.md-style files have no frontmatter but the body IS the
            // knowledge — derive the summary from the first content line instead of
            // ingesting the filename as a junk summary.
            String derived = firstContentLine(body);
            if (derived != null) {
                return derived;
            }
            return name != null && !name.isBlank() ? name : "(untitled)";
        }

        /** True when there is anything worth ingesting (description or body). */
        boolean hasContent() {
            return (description != null && !description.isBlank()) || !body.isBlank();
        }
    }

    /** First non-empty body line, stripped of md heading/list markup, capped at 160 chars. */
    private static String firstContentLine(String body) {
        if (body == null) {
            return null;
        }
        for (String line : body.split("\n")) {
            String s = line.strip().replaceFirst("^[#>*\\-\\s]+", "").strip();
            if (s.isEmpty() || s.startsWith("<!--")) {
                continue;                 // markup / managed-section markers are not content
            }
            return s.length() > 160 ? s.substring(0, 157) + "…" : s;
        }
        return null;
    }

    private static MemoryDoc parse(String content, String fileName) {
        String name = null;
        String description = null;
        String type = null;
        String symbol = null;
        String language = null;
        StringBuilder body = new StringBuilder();

        String[] lines = content.split("\n", -1);
        int i = 0;
        if (lines.length > 0 && lines[0].strip().equals("---")) {
            i = 1;
            for (; i < lines.length; i++) {
                if (lines[i].strip().equals("---")) {
                    i++;
                    break;
                }
                String t = lines[i].strip();
                int c = t.indexOf(':');
                if (c <= 0) {
                    continue;
                }
                String k = t.substring(0, c).strip();
                String v = stripMatchingQuotes(t.substring(c + 1).strip());
                switch (k) {
                    case "name" -> name = emptyToNull(v);
                    case "description" -> description = emptyToNull(v);
                    case "type" -> { if (type == null) { type = emptyToNull(v); } }  // top-level or metadata.type
                    case "symbol" -> symbol = emptyToNull(v);
                    case "language" -> language = emptyToNull(v);
                    default -> { }
                }
            }
        }
        for (; i < lines.length; i++) {
            body.append(lines[i]).append('\n');
        }

        String bodyStr = body.toString();
        List<String> links = wikilinks(bodyStr);
        // Item C: relative markdown links (the MEMORY.md "- [Title](file.md)" index style).
        List<String> fileLinks = new ArrayList<>();
        Matcher fm = MD_LINK.matcher(bodyStr);
        while (fm.find()) {
            String link = fm.group(1).strip();
            if (!link.isEmpty() && !link.startsWith("/") && !fileLinks.contains(link)) {
                fileLinks.add(link);
            }
        }
        if (name == null) {
            name = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        }
        Split split = splitSections(bodyStr);
        // Keywords for the PARENT entry: with sections, only the preamble — each
        // section harvests its own body (Sprint 21c item B).
        List<String> keywords =
            harvestKeywords(split.sections().isEmpty() ? bodyStr : split.preamble());
        return new MemoryDoc(name, description, type, symbol, language, bodyStr,
            links, fileLinks, keywords, split.preamble(), split.sections());
    }

    /** Sprint 21c (item B): a heading-bounded body slice — the atomic fact. */
    private record Section(String heading, String body, List<String> links, List<String> keywords) {}

    private record Split(String preamble, List<Section> sections) {}

    /** Split the body at heading boundaries (any level, fence-aware); text before the
     *  first heading is the preamble and stays with the file-level parent. */
    private static Split splitSections(String body) {
        StringBuilder preamble = new StringBuilder();
        List<Section> sections = new ArrayList<>();
        String heading = null;
        StringBuilder cur = new StringBuilder();
        boolean fenced = false;
        for (String line : body.split("\n", -1)) {
            String s = line.strip();
            if (s.startsWith("```")) {
                fenced = !fenced;
            }
            Matcher h = HEADING.matcher(s);
            if (!fenced && h.matches()) {
                if (heading != null) {
                    sections.add(section(heading, cur.toString()));
                }
                heading = h.group(1).replace("**", "").replace("`", "").strip();
                cur = new StringBuilder();
                continue;
            }
            (heading == null ? preamble : cur).append(line).append('\n');
        }
        if (heading != null) {
            sections.add(section(heading, cur.toString()));
        }
        return new Split(preamble.toString(), sections);
    }

    private static Section section(String heading, String body) {
        return new Section(heading, body, wikilinks(body),
            harvestKeywords("## " + heading + "\n" + body));
    }

    private static List<String> wikilinks(String text) {
        List<String> links = new ArrayList<>();
        Matcher m = WIKILINK.matcher(text);
        while (m.find()) {
            String link = m.group(1).strip();
            if (!link.isEmpty() && !links.contains(link)) {
                links.add(link);
            }
        }
        return links;
    }

    /** Add harvested keywords as symptoms up to the backstop; true when capped. */
    private static boolean addKeywords(ExperienceEntry.Builder eb, List<String> keywords) {
        int kw = 0;
        for (String keyword : keywords) {
            if (kw == MAX_KEYWORDS_PER_ENTRY) {
                return true;
            }
            eb.addSymptom(keyword);
            kw++;
        }
        return false;
    }

    /**
     * Sprint 21c (item A): harvest the body's cue-dense structure — headings (all
     * levels), {@code **bold**} phrases, {@code `backticked`} terms and
     * {@code [[wikilink]]} names — as keyword phrases for the symptom index. Fenced
     * {@code ```} blocks are code, not cues. Deduplicated after normalization; phrases
     * capped to the VARCHAR(512) symptom column.
     */
    static List<String> harvestKeywords(String body) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        StringBuilder prose = new StringBuilder();
        boolean fenced = false;
        for (String line : body.split("\n", -1)) {
            String s = line.strip();
            if (s.startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (fenced) {
                continue;
            }
            Matcher h = HEADING.matcher(s);
            if (h.matches()) {
                addKeyword(out, seen, h.group(1).replace("**", "").replace("`", ""));
            }
            prose.append(line).append('\n');
        }
        for (Pattern p : List.of(BOLD, CODE_SPAN, WIKILINK, QUOTED)) {
            Matcher m = p.matcher(prose);
            while (m.find()) {
                addKeyword(out, seen, m.group(1));
            }
        }
        return out;
    }

    private static void addKeyword(List<String> out, Set<String> seen, String phrase) {
        String p = phrase.strip();
        if (p.length() < 3) {
            return;                                    // single chars are noise, not cues
        }
        if (p.length() > 512) {
            p = p.substring(0, 512);                   // experience_symptom column limit
        }
        if (seen.add(H2ExperienceStore.normalize(p))) {
            out.add(p);
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    /**
     * Strip a single matched pair of surrounding quotes from a YAML-style
     * scalar (e.g. {@code symbol: "com.example.Foo#bar"} → {@code
     * com.example.Foo#bar}) so a quoted frontmatter value ingests identically
     * to its bare form. Critical for {@code symbol}, whose stored FQN must
     * resolve through JDT and be recallable by the anchor (Sprint 22a P0-b).
     * Only a matched leading+trailing pair of the same quote char is removed;
     * inner quotes and unbalanced quotes are left untouched.
     */
    private static String stripMatchingQuotes(String v) {
        if (v.length() >= 2) {
            char first = v.charAt(0);
            if ((first == '"' || first == '\'') && first == v.charAt(v.length() - 1)) {
                return v.substring(1, v.length() - 1);
            }
        }
        return v;
    }
}
