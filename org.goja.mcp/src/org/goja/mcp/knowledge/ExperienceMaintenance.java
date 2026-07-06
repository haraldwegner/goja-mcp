package org.goja.mcp.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public ExperienceMaintenance(ExperienceStore store, PointerResolver resolver) {
        this(store, resolver, List::of);
    }

    /** Sprint 21a (item C): {@code defaultRoots} feed the no-path {@code load}/{@code reseed}. */
    public ExperienceMaintenance(ExperienceStore store, PointerResolver resolver,
            Supplier<List<Path>> defaultRoots) {
        this.store = store;
        this.resolver = resolver == null ? fqn -> null : resolver;
        this.defaultRoots = defaultRoots == null ? List::of : defaultRoots;
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
        long bytes = 0;
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

            SymbolFact.Builder fb = SymbolFact.of(
                doc.type == null ? "note" : doc.type,
                doc.summary(), Confidence.MEDIUM);
            if (doc.symbol != null) {
                fb.symbol(doc.symbol);
            }
            if (!doc.body.isBlank()) {
                fb.details(doc.body.strip());
            }
            ExperienceEntry.Builder eb = ExperienceEntry.of(fb.build())
                .status(ExperienceEntry.ACCEPTED)
                .language(doc.language);
            // v2.2.5 (find #13): the NAME is where cue-dense phrasing lives ("…renders
            // blank on aarch64") — index it as a symptom so recall can reach it.
            if (doc.name != null && !doc.name.isBlank()) {
                eb.addSymptom(doc.name);
            }
            for (String link : doc.links) {
                eb.addLink("related", link);
            }
            store.putWithSource(eb.build(), sourceRef, hash);
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
        if (!skipped.isEmpty()) {
            log.info("load: {} source(s) skipped: {}", skipped.size(), skipped);
        }
        return report;
    }

    /**
     * v2.2.6 (find #14): the ingest-semantics version, baked into the skip-unchanged
     * hash. BUMP THIS whenever what the loader EXTRACTS from a file changes (new facets,
     * symptom derivation, summary rules …) — every stored hash then goes stale by
     * definition and the next load re-ingests the whole corpus once, self-healing
     * retroactively. Within one version, idempotency stays byte-strict.
     * History: 1 = content-only (v2.2.1) · 2 = name-as-symptom (v2.2.6).
     */
    static final int LOADER_VERSION = 2;

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
        int checked = 0;
        int resolved = 0;
        int skipped = 0;
        int nonJava = 0;
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
            } else {
                store.setStatus(e.id(), ExperienceEntry.SUPERSEDED);
                staled.add(Map.of("id", e.id(), "symbol", fqn));
            }
        }
        report.put("checked", checked);
        report.put("resolved", resolved);
        report.put("skipped", skipped);
        report.put("non_java", nonJava);
        report.put("staled", staled);
        return report;
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
                             List<String> fileLinks) {
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
            if (!s.isEmpty()) {
                return s.length() > 160 ? s.substring(0, 157) + "…" : s;
            }
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
                String v = t.substring(c + 1).strip();
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

        List<String> links = new ArrayList<>();
        Matcher m = WIKILINK.matcher(body);
        while (m.find()) {
            String link = m.group(1).strip();
            if (!link.isEmpty() && !links.contains(link)) {
                links.add(link);
            }
        }
        // Item C: relative markdown links (the MEMORY.md "- [Title](file.md)" index style).
        List<String> fileLinks = new ArrayList<>();
        Matcher fm = MD_LINK.matcher(body);
        while (fm.find()) {
            String link = fm.group(1).strip();
            if (!link.isEmpty() && !link.startsWith("/") && !fileLinks.contains(link)) {
                fileLinks.add(link);
            }
        }
        if (name == null) {
            name = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        }
        return new MemoryDoc(name, description, type, symbol, language, body.toString(), links, fileLinks);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
