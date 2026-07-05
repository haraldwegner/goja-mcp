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

    // Sprint 21a (item C): crawl bounds — a link closure must terminate and stay honest.
    static final int DEFAULT_MAX_DEPTH = 5;
    static final int DEFAULT_MAX_FILES = 200;
    static final long DEFAULT_MAX_BYTES = 2_000_000L;

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
            return loadSources(roots, recursive, DEFAULT_MAX_DEPTH, DEFAULT_MAX_FILES, DEFAULT_MAX_BYTES);
        }
        if (!Files.exists(path)) {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("loaded", 0);
            report.put("error", "path does not exist");
            return report;
        }
        return loadSources(List.of(path), recursive, DEFAULT_MAX_DEPTH, DEFAULT_MAX_FILES, DEFAULT_MAX_BYTES);
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
                    s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".md"))
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
        int linked = 0;
        long bytes = 0;
        while (!queue.isEmpty()) {
            Item item = queue.poll();
            Path f = item.file().toAbsolutePath().normalize();
            if (!seen.add(f)) {
                continue;                            // cycle-safe / dedup
            }
            if (loaded >= maxFiles) {
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
            String sourceRef = "memory:" + f;
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
            for (String link : doc.links) {
                eb.addLink("related", link);
            }
            store.putWithSource(eb.build(), sourceRef);
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

        report.put("files", loaded);
        report.put("loaded", loaded);
        report.put("linked", linked);
        report.put("stale", stale);
        report.put("skipped", skipped);
        if (!skipped.isEmpty()) {
            log.info("load: {} source(s) skipped: {}", skipped.size(), skipped);
        }
        return report;
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
            return name != null && !name.isBlank() ? name : "(untitled)";
        }
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
