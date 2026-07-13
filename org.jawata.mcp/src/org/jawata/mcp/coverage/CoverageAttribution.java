package org.jawata.mcp.coverage;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.tools.ExecFileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 23 (D4) — who-tests-what from per-test coverage segments: each
 * attribution run leaves one .exec per executed test (plus index.tsv);
 * queries analyze segments against the artifact's class roots. Evidence-
 * backed only — an artifact without segments answers "unavailable",
 * never a guess.
 */
public final class CoverageAttribution {

    private static final Logger log = LoggerFactory.getLogger(CoverageAttribution.class);

    /** Rank order for focused-first: smaller = more focused evidence. */
    public static int kindRank(String evidenceKind) {
        return switch (evidenceKind == null ? "" : evidenceKind) {
            case "unit" -> 0;
            case "integration" -> 1;
            case "system" -> 2;
            case "replay" -> 3;
            case "manual" -> 4;
            default -> 5;
        };
    }

    /** testId → segment file, from the artifact's index. Empty = no attribution. */
    public static Map<String, Path> segments(CoverageStore store, String artifactId) {
        Path dir = store.root().resolve(artifactId).resolve("segments");
        Path index = dir.resolve("index.tsv");
        if (!Files.isRegularFile(index)) return Map.of();
        Map<String, Path> out = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(index, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab < 0) continue;
                Path seg = dir.resolve(line.substring(0, tab));
                if (Files.isRegularFile(seg)) {
                    out.put(line.substring(tab + 1), seg);
                }
            }
        } catch (IOException e) {
            log.warn("unreadable attribution index {}: {}", index, e.getMessage());
        }
        return out;
    }

    /** Locate the .class file of an FQN under the manifest's class roots. */
    public static Path classFile(CoverageManifest manifest, String fqn) {
        String rel = fqn.replace('.', '/') + ".class";
        for (String root : manifest.classRoots) {
            Path candidate = Path.of(root).resolve(rel);
            if (Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Does this segment exercise the target? Targeted analysis of ONE class
     * file — cheap enough to run per segment. methodName null = class level.
     */
    public static boolean covers(Path segment, Path classFile, String methodName) {
        try {
            ExecFileLoader loader = new ExecFileLoader();
            loader.load(segment.toFile());
            CoverageBuilder builder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
            try (InputStream in = Files.newInputStream(classFile)) {
                analyzer.analyzeClass(in, classFile.toString());
            }
            for (IClassCoverage cc : builder.getClasses()) {
                if (methodName == null) {
                    if (cc.getLineCounter().getCoveredCount() > 0) return true;
                    continue;
                }
                for (IMethodCoverage mc : cc.getMethods()) {
                    if (mc.getName().equals(methodName)
                            && mc.getLineCounter().getCoveredCount() > 0) {
                        return true;
                    }
                }
            }
            return false;
        } catch (IOException e) {
            log.debug("segment analysis failed for {}: {}", segment, e.getMessage());
            return false;
        }
    }

    /** Every fqn#method a segment covers (bounded), for coverage_of_test. */
    public static List<String> coveredSymbols(Path segment, CoverageManifest manifest, int limit) {
        List<String> out = new ArrayList<>();
        try {
            ExecFileLoader loader = new ExecFileLoader();
            loader.load(segment.toFile());
            for (String root : manifest.classRoots) {
                Path rootPath = Path.of(root);
                if (!Files.isDirectory(rootPath)) continue;
                CoverageBuilder builder = new CoverageBuilder();
                Analyzer analyzer = new Analyzer(loader.getExecutionDataStore(), builder);
                analyzer.analyzeAll(rootPath.toFile());
                for (IClassCoverage cc : builder.getClasses()) {
                    for (IMethodCoverage mc : cc.getMethods()) {
                        if (mc.getLineCounter().getCoveredCount() > 0) {
                            out.add(cc.getName().replace('/', '.') + "#" + mc.getName());
                            if (out.size() >= limit) return out;
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("coverage_of_test analysis failed: {}", e.getMessage());
        }
        return out;
    }
}
