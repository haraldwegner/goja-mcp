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

    /** Resolve a symbol pointer: {@code TRUE}=resolves, {@code FALSE}=stale, {@code null}=unknown (no project). */
    @FunctionalInterface
    public interface PointerResolver {
        Boolean resolves(String symbolFqn);
    }

    private final ExperienceStore store;
    private final PointerResolver resolver;

    public ExperienceMaintenance(ExperienceStore store, PointerResolver resolver) {
        this.store = store;
        this.resolver = resolver == null ? fqn -> null : resolver;
    }

    // --- load ---------------------------------------------------------------------------

    /**
     * Seed the store from memory files under {@code path} (a directory of {@code *.md} or a
     * single file). Frontmatter {@code type} → entry type, {@code description} → summary,
     * {@code symbol} → JDT-resolved pointer (flagged stale on ingest), {@code [[wikilinks]]}
     * → {@code related} edges. Entries are {@code accepted}/{@code medium}. Idempotent by
     * source: a re-load of the same file replaces its entries.
     */
    public Map<String, Object> load(Path path) {
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> stale = new ArrayList<>();
        if (path == null || !Files.exists(path)) {
            report.put("loaded", 0);
            report.put("error", "path does not exist");
            return report;
        }
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(path)) {
            try (Stream<Path> s = Files.list(path)) {
                s.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().forEach(files::add);
            } catch (IOException e) {
                report.put("loaded", 0);
                report.put("error", "cannot list directory: " + e.getMessage());
                return report;
            }
        } else {
            files.add(path);
        }

        int loaded = 0;
        for (Path f : files) {
            try {
                MemoryDoc doc = parse(Files.readString(f), f.getFileName().toString());
                String sourceRef = "memory:" + f.getFileName();
                store.deleteBySource(sourceRef);   // idempotent re-seed

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
            } catch (IOException e) {
                log.warn("load: cannot read {}: {}", f, e.getMessage());
            }
        }
        report.put("files", files.size());
        report.put("loaded", loaded);
        report.put("stale", stale);
        return report;
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

    // --- frontmatter parsing ------------------------------------------------------------

    private record MemoryDoc(String name, String description, String type, String symbol,
                             String language, String body, List<String> links) {
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
        if (name == null) {
            name = fileName.endsWith(".md") ? fileName.substring(0, fileName.length() - 3) : fileName;
        }
        return new MemoryDoc(name, description, type, symbol, language, body.toString(), links);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
