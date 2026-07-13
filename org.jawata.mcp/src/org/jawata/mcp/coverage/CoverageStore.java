package org.jawata.mcp.coverage;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Sprint 23 (D3) — coverage artifacts on disk: one directory per artifact
 * ({@code jacoco.exec} + {@code manifest.json}) under the workspace state
 * area, with an EXPLICIT delete action (never silent eviction of evidence).
 *
 * <p>Root resolution: {@code jawata.coverage.dir} property (tests, embedders)
 * → {@code jawata.workspace.root}/.jawata/coverage (production, the stable
 * root the boot publishes) → a tmpdir fallback.</p>
 */
public final class CoverageStore {

    private static final Logger log = LoggerFactory.getLogger(CoverageStore.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final String EXEC_FILE = "jacoco.exec";
    public static final String MANIFEST_FILE = "manifest.json";

    private final Path root;

    public CoverageStore() {
        this(resolveRoot());
    }

    public CoverageStore(Path root) {
        this.root = root;
    }

    static Path resolveRoot() {
        String explicit = System.getProperty("jawata.coverage.dir");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String wsRoot = System.getProperty("jawata.workspace.root");
        if (wsRoot != null && !wsRoot.isBlank()) {
            return Path.of(wsRoot, ".jawata", "coverage");
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "jawata-coverage");
    }

    /** Create a fresh artifact directory; the id doubles as its dir name. */
    public Path createArtifactDir(String artifactId) throws IOException {
        Path dir = root.resolve(artifactId);
        Files.createDirectories(dir);
        return dir;
    }

    public String newArtifactId() {
        return "cov-" + System.currentTimeMillis() + "-"
            + UUID.randomUUID().toString().substring(0, 8);
    }

    public Path execFile(String artifactId) {
        return root.resolve(artifactId).resolve(EXEC_FILE);
    }

    public void writeManifest(String artifactId, CoverageManifest manifest) throws IOException {
        Path file = root.resolve(artifactId).resolve(MANIFEST_FILE);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    }

    public Optional<CoverageManifest> readManifest(String artifactId) {
        Path file = root.resolve(artifactId).resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(file)) return Optional.empty();
        try {
            return Optional.of(JSON.readValue(file.toFile(), CoverageManifest.class));
        } catch (IOException e) {
            log.warn("unreadable coverage manifest {}: {}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /** All artifact ids, newest first. */
    public List<String> list() {
        if (!Files.isDirectory(root)) return List.of();
        try (Stream<Path> dirs = Files.list(root)) {
            return dirs.filter(Files::isDirectory)
                .filter(d -> Files.isRegularFile(d.resolve(MANIFEST_FILE)))
                .sorted(Comparator.comparing((Path d) -> d.getFileName().toString()).reversed())
                .map(d -> d.getFileName().toString())
                .toList();
        } catch (IOException e) {
            log.warn("cannot list coverage store {}: {}", root, e.getMessage());
            return List.of();
        }
    }

    public Optional<String> latest() {
        List<String> all = list();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    public boolean exists(String artifactId) {
        return Files.isRegularFile(root.resolve(artifactId).resolve(MANIFEST_FILE));
    }

    /** Explicit delete — returns false when the artifact does not exist. */
    public boolean delete(String artifactId) {
        Path dir = root.resolve(artifactId);
        if (!Files.isDirectory(dir)) return false;
        List<Path> paths = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(paths::add);
        } catch (IOException e) {
            log.warn("cannot walk coverage artifact {}: {}", dir, e.getMessage());
            return false;
        }
        boolean ok = true;
        for (Path p : paths) {
            try {
                Files.delete(p);
            } catch (IOException e) {
                ok = false;
                log.warn("cannot delete {}: {}", p, e.getMessage());
            }
        }
        return ok;
    }

    public Path root() {
        return root;
    }

    // ---------------- Stage 8: baselines · threshold policy · unstable lines

    @SuppressWarnings("unchecked")
    private Map<String, Object> readJsonMap(String fileName) {
        Path file = root.resolve(fileName);
        if (!Files.isRegularFile(file)) return new java.util.LinkedHashMap<>();
        try {
            return JSON.readValue(file.toFile(), Map.class);
        } catch (IOException e) {
            log.warn("unreadable {}: {}", file, e.getMessage());
            return new java.util.LinkedHashMap<>();
        }
    }

    private void writeJsonMap(String fileName, Map<String, Object> value) throws IOException {
        Files.createDirectories(root);
        JSON.writerWithDefaultPrettyPrinter().writeValue(root.resolve(fileName).toFile(), value);
    }

    /** Name a stored artifact as a baseline. */
    public void setBaseline(String name, String artifactId) throws IOException {
        Map<String, Object> baselines = readJsonMap("baselines.json");
        baselines.put(name, artifactId);
        writeJsonMap("baselines.json", baselines);
    }

    public Optional<String> baseline(String name) {
        Object id = readJsonMap("baselines.json").get(name);
        return id == null ? Optional.empty() : Optional.of(String.valueOf(id));
    }

    /**
     * The threshold policy: explicit AND VERSIONED — every change bumps the
     * version so a response can prove WHICH policy judged it. Waivers are
     * part of the policy and always surface in responses.
     */
    public Map<String, Object> readPolicy() {
        return readJsonMap("threshold-policy.json");
    }

    public Map<String, Object> setPolicy(double lineThresholdPercent,
            List<Map<String, String>> waivers) throws IOException {
        Map<String, Object> policy = readPolicy();
        int version = policy.get("version") instanceof Number n ? n.intValue() : 0;
        policy.put("version", version + 1);
        policy.put("lineThresholdPercent", lineThresholdPercent);
        policy.put("waivers", waivers == null ? List.of() : waivers);
        writeJsonMap("threshold-policy.json", policy);
        return policy;
    }

    /** Unstable-line registry: fqn → lines proven non-deterministic. */
    @SuppressWarnings("unchecked")
    public Map<String, List<Integer>> readUnstable() {
        Map<String, Object> raw = readJsonMap("unstable.json");
        Map<String, List<Integer>> out = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (v instanceof List<?> l) {
                List<Integer> lines = new ArrayList<>();
                l.forEach(x -> lines.add(((Number) x).intValue()));
                out.put(k, lines);
            }
        });
        return out;
    }

    public void addUnstable(Map<String, List<Integer>> more) throws IOException {
        Map<String, List<Integer>> current = readUnstable();
        more.forEach((fqn, lines) -> {
            List<Integer> merged = new ArrayList<>(current.getOrDefault(fqn, List.of()));
            for (Integer l : lines) {
                if (!merged.contains(l)) merged.add(l);
            }
            current.put(fqn, merged);
        });
        Map<String, Object> raw = new java.util.LinkedHashMap<>();
        current.forEach(raw::put);
        writeJsonMap("unstable.json", raw);
    }
}
