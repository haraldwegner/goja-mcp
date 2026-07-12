package org.jawata.core.project;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Sprint 23 (D7) — EXTERNAL bundle pools: resolve {@code Require-Bundle} /
 * {@code Import-Package} headers of PDE projects against directories of
 * OSGi bundle jars, extending the Sprint-11 WORKSPACE pool (sibling
 * projects) to bundles that live outside the workspace.
 *
 * <p>Pool directories, in preference order:</p>
 * <ol>
 *   <li>{@code jawata.bundle.pools} system property (path-separated list) —
 *       the explicit, materialized-target-platform case;</li>
 *   <li>the shared p2 pool ({@code ~/.p2/pool/plugins}), when present;</li>
 *   <li>the running server's own framework bundles
 *       ({@code jawata.dist.root/bundles}) — the self-hosting fallback.</li>
 * </ol>
 *
 * <p>Indexing reads each jar's manifest ONCE per (directory, mtime) and maps
 * symbolic name → highest-version jar plus exported package → providing jar.
 * Unresolvable requirements stay unresolved and are logged — exactly the
 * pre-Sprint-23 behavior, just with far fewer of them.</p>
 */
public final class ExternalBundlePool {

    private static final Logger log = LoggerFactory.getLogger(ExternalBundlePool.class);

    /** Directory-index cache keyed by absolute path; invalidated by mtime. */
    private static final ConcurrentHashMap<String, CachedDir> DIR_CACHE = new ConcurrentHashMap<>();

    private record CachedDir(long mtime, Map<String, PoolBundle> bySymbolicName,
                             Map<String, Path> byExportedPackage) { }

    private record PoolBundle(String version, Path jar) { }

    private final Map<String, PoolBundle> bySymbolicName = new HashMap<>();
    private final Map<String, Path> byExportedPackage = new HashMap<>();

    private ExternalBundlePool() { }

    /** Index the given pool directories (earlier dirs win on conflicts). */
    public static ExternalBundlePool index(List<Path> poolDirs) {
        ExternalBundlePool pool = new ExternalBundlePool();
        for (Path dir : poolDirs) {
            CachedDir cached = indexDir(dir);
            if (cached == null) continue;
            cached.bySymbolicName.forEach((name, candidate) ->
                pool.bySymbolicName.merge(name, candidate, (a, b) ->
                    compareVersions(a.version, b.version) >= 0 ? a : b));
            cached.byExportedPackage.forEach(pool.byExportedPackage::putIfAbsent);
        }
        return pool;
    }

    /** The production pool-directory chain (see class Javadoc). */
    public static List<Path> defaultPoolDirs() {
        List<Path> dirs = new ArrayList<>();
        String explicit = System.getProperty("jawata.bundle.pools");
        if (explicit != null && !explicit.isBlank()) {
            for (String part : explicit.split(java.io.File.pathSeparator)) {
                if (!part.isBlank()) dirs.add(Path.of(part));
            }
        }
        dirs.add(Path.of(System.getProperty("user.home"), ".p2", "pool", "plugins"));
        String distRoot = System.getProperty("jawata.dist.root");
        if (distRoot != null && !distRoot.isBlank()) {
            dirs.add(Path.of(distRoot, "bundles"));
        }
        return dirs.stream().filter(Files::isDirectory).toList();
    }

    public Optional<Path> bundleJar(String symbolicName) {
        return Optional.ofNullable(bySymbolicName.get(symbolicName)).map(PoolBundle::jar);
    }

    public Optional<Path> packageProvider(String packageName) {
        return Optional.ofNullable(byExportedPackage.get(packageName));
    }

    public boolean isEmpty() {
        return bySymbolicName.isEmpty();
    }

    // ------------------------------------------------------------- indexing

    private static CachedDir indexDir(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        String key = dir.toAbsolutePath().toString();
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(dir).toMillis();
        } catch (IOException e) {
            return null;
        }
        CachedDir cached = DIR_CACHE.get(key);
        if (cached != null && cached.mtime == mtime) {
            return cached;
        }
        Map<String, PoolBundle> byName = new HashMap<>();
        Map<String, Path> byPackage = new HashMap<>();
        try (Stream<Path> jars = Files.list(dir)) {
            jars.filter(p -> p.getFileName().toString().endsWith(".jar"))
                .sorted()
                .forEach(jar -> indexJar(jar, byName, byPackage));
        } catch (IOException e) {
            log.warn("bundle pool: cannot list {}: {}", dir, e.getMessage());
            return null;
        }
        CachedDir fresh = new CachedDir(mtime, Map.copyOf(byName), Map.copyOf(byPackage));
        DIR_CACHE.put(key, fresh);
        log.debug("bundle pool indexed {}: {} bundles, {} exported packages",
            dir, byName.size(), byPackage.size());
        return fresh;
    }

    private static void indexJar(Path jar, Map<String, PoolBundle> byName,
                                 Map<String, Path> byPackage) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest manifest = jf.getManifest();
            if (manifest == null) return;
            String rawName = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
            if (rawName == null) return;
            String name = stripAttributes(rawName);
            String version = manifest.getMainAttributes().getValue("Bundle-Version");
            PoolBundle candidate = new PoolBundle(version == null ? "0" : version.trim(), jar);
            byName.merge(name, candidate,
                (a, b) -> compareVersions(a.version, b.version) >= 0 ? a : b);
            String exports = manifest.getMainAttributes().getValue("Export-Package");
            if (exports != null) {
                for (String entry : splitTopLevel(exports)) {
                    String pkg = stripAttributes(entry);
                    if (!pkg.isEmpty()) byPackage.putIfAbsent(pkg, jar);
                }
            }
        } catch (IOException e) {
            log.debug("bundle pool: unreadable jar {} ({})", jar, e.getMessage());
        }
    }

    /**
     * Split an OSGi header on TOP-LEVEL commas — commas inside quoted
     * attribute values (version ranges, {@code uses:="a,b"}) don't split.
     */
    static List<String> splitTopLevel(String header) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) parts.add(current.toString());
        return parts;
    }

    private static String stripAttributes(String value) {
        int semi = value.indexOf(';');
        return (semi == -1 ? value : value.substring(0, semi)).trim();
    }

    /** OSGi-ish version compare: numeric segments numerically, rest lexically. */
    static int compareVersions(String a, String b) {
        String[] as = a.split("\\.", 4);
        String[] bs = b.split("\\.", 4);
        for (int i = 0; i < Math.max(as.length, bs.length); i++) {
            String sa = i < as.length ? as[i] : "0";
            String sb = i < bs.length ? bs[i] : "0";
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(sa), Integer.parseInt(sb));
            } catch (NumberFormatException e) {
                cmp = sa.compareTo(sb);
            }
            if (cmp != 0) return cmp;
        }
        return 0;
    }
}
