package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Detector;
import org.jawata.mcp.domain.Finding;
import org.jawata.mcp.domain.Findings;
import org.jawata.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 17 — base for Fowler smell {@link Detector}s. Handles the uniform
 * boilerplate every AST-walk detector shares: read the optional {@code filePath}
 * + {@code threshold} args, iterate the project's Java files (or just one), parse
 * each with binding resolution, collect {@link Finding}s, sort by severity, and
 * render via {@link Findings#toResponse}. Subclasses implement only
 * {@link #analyze} — the visitor + heuristic for one smell.
 *
 * <p>Findings use 1-based line/column (JDT convention), matching {@link Finding}.</p>
 */
public abstract class AbstractAstDetector implements Detector {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(AbstractAstDetector.class);

    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
        "error", 3, "warning", 2, "info", 1);

    private final String kind;
    private final String description;
    private final int defaultThreshold;

    protected AbstractAstDetector(String kind, String description, int defaultThreshold) {
        this.kind = kind;
        this.description = description;
        this.defaultThreshold = defaultThreshold;
    }

    @Override
    public final String kind() {
        return kind;
    }

    @Override
    public final String description() {
        return description;
    }

    /** Default threshold for this smell when the caller omits {@code threshold}. */
    protected final int defaultThreshold() {
        return defaultThreshold;
    }

    /**
     * Run the detector — and <b>report what it actually managed to look at</b>.
     *
     * <p>This used to skip, in silence, every file it could not resolve or parse. So a
     * detector could examine ZERO files and answer {@code count: 0} — "no smells found" —
     * which is not an absence but a <b>failure to look</b>. It happened for real: a
     * {@code JavaModelException} while the Java model rebuilds makes
     * {@code getCompilationUnit} return null (the core swallows it), the file is skipped,
     * and the tool cheerfully declares the code clean. It surfaced as a "flaky" test that
     * went green on a re-run — which is exactly how a lying tool stays hidden.</p>
     *
     * <p>We LISTED the files ourselves. So if we listed N and read fewer, that gap IS the
     * failure, whatever caused it — and the caller is told.</p>
     */
    @Override
    public ToolResponse detect(IJdtService service, JsonNode arguments) {
        int threshold = readInt(arguments, "threshold", defaultThreshold);
        List<Finding> findings = new ArrayList<>();

        int listed = 0;
        int examined = 0;
        List<String> unreadable = new ArrayList<>();
        List<String> unparseable = new ArrayList<>();
        List<String> bindingsDead = new ArrayList<>();
        ScanDegradation degraded = new ScanDegradation();

        try {
            List<Path> files = scopedSourceFiles(service, arguments);
            listed = files.size();
            for (Path path : files) {
                ICompilationUnit cu = service.getCompilationUnit(path);
                if (cu == null) {
                    // We listed this file, and now we cannot resolve it. That is not "it has
                    // no smells"; it is "we could not open it".
                    unreadable.add(String.valueOf(path));
                    continue;
                }
                CompilationUnit ast = parse(cu);
                if (ast == null) {
                    unparseable.add(String.valueOf(path));
                    continue;
                }
                if (!ast.getAST().hasResolvedBindings()) {
                    // The parse "succeeded" but produced an AST WITHOUT bindings — the
                    // contract of analyze() (and every resolveBinding() call inside a
                    // detector) is void. A detector walking this AST finds nothing, about
                    // anything, and that nothing is not an absence. Count it as missed.
                    bindingsDead.add(String.valueOf(path));
                    continue;
                }
                examined++;
                String formatted = service.getPathUtils().formatPath(path);
                analyze(ast, formatted, service, threshold, findings, degraded);
            }
        } catch (org.jawata.core.SourceListingException e) {
            // The LISTING itself failed — we do not even know what we did not read.
            return ToolResponse.error("SCAN_LISTING_FAILED",
                "This scan could not LIST the source files (project '" + e.projectName()
                    + "'): " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage())
                    + ". No verdict about " + kind + " is possible — not even a partial one.",
                org.jawata.mcp.tools.shared.SourceScan.AGENT_CONTRACT);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }

        int missed = unreadable.size() + unparseable.size() + bindingsDead.size();

        // NOTHING WAS EXAMINED. Whatever we return, it is not a verdict on this code.
        if (examined == 0 && listed > 0) {
            return ToolResponse.error("SCAN_EXAMINED_NOTHING",
                "This scan listed " + listed + " source file(s) and could not read a single one"
                    + " (" + unreadable.size() + " unresolvable, " + unparseable.size()
                    + " unparseable, " + bindingsDead.size() + " without resolvable bindings). "
                    + "A result of 'no " + kind + " found' would be a statement "
                    + "about code we never opened. Examples: "
                    + firstFew(unreadable, unparseable, bindingsDead),
                org.jawata.mcp.tools.shared.SourceScan.AGENT_CONTRACT);
        }

        // The listing returned NOTHING for a workspace that had source files on disk
        // when it was loaded. That is a failed listing wearing an empty project's
        // clothes — refuse, do not certify an absence over it.
        if (listed == 0 && readString(arguments, "filePath") == null) {
            int knownAtLoad = service.allProjects().stream()
                .mapToInt(p -> Math.max(p.sourceFileCount(), 0)).sum();
            if (knownAtLoad > 0) {
                return ToolResponse.error("SCAN_LISTING_FAILED",
                    "This scan listed ZERO source files, but the workspace knew of "
                        + knownAtLoad + " .java file(s) when its project(s) were loaded. The "
                        + "listing failed (or every source root became unreadable); an answer of "
                        + "'no " + kind + " found' would be a verdict about files we never saw.",
                    org.jawata.mcp.tools.shared.SourceScan.AGENT_CONTRACT);
            }
        }

        findings.sort(Comparator.comparingInt(
            (Finding f) -> SEVERITY_RANK.getOrDefault(f.severity(), 0)).reversed());

        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("filesListed", listed);
        scan.put("filesExamined", examined);
        if (missed > 0) {
            scan.put("filesMissed", missed);
            scan.put("scanIncomplete", true);
            if (!unreadable.isEmpty()) {
                scan.put("unresolvable", cap(unreadable));
            }
            if (!unparseable.isEmpty()) {
                scan.put("unparseable", cap(unparseable));
            }
            if (!bindingsDead.isEmpty()) {
                scan.put("bindingsUnresolved", cap(bindingsDead));
            }
        }
        if (!degraded.isEmpty()) {
            scan.put("scanDegraded", true);
            scan.put("lookupFailures", degraded.notes());
        }
        return Findings.toResponse(findings, scan,
            steeringFor(findings.size(), examined, missed, degraded));
    }

    private String steeringFor(int found, int examined, int missed, ScanDegradation degraded) {
        if (missed > 0) {
            return "PARTIAL SCAN: " + missed + " file(s) could not be read, so these findings are "
                + "what survived — not what exists. " + (found == 0
                    ? "In particular, 'none found' here is NOT a clean bill of health."
                    : "There may be more in the files we could not open.")
                + " Run refresh_workspace and re-run to get a complete answer.";
        }
        if (!degraded.isEmpty()) {
            return "DEGRADED SCAN: every file was read, but " + degraded.count()
                + " lookup(s) the detector depends on FAILED (see lookupFailures), and a finding "
                + "whose lookup fails is suppressed, never guessed. " + (found == 0
                    ? "'None found' here is NOT a clean bill of health."
                    : "There may be more findings than these.")
                + " Run refresh_workspace and re-run to get a complete answer.";
        }
        if (found == 0) {
            return "None found — and the scan was COMPLETE (" + examined + " file(s) examined, "
                + "every lookup answered), so this is a real absence, not a failure to look.";
        }
        return null;
    }

    private static List<String> cap(List<String> paths) {
        return paths.size() <= 10 ? paths : new ArrayList<>(paths.subList(0, 10));
    }

    private static String firstFew(List<String> a, List<String> b, List<String> c) {
        List<String> all = new ArrayList<>(a);
        all.addAll(b);
        all.addAll(c);
        return String.valueOf(cap(all));
    }

    /**
     * Collects "a lookup this detector depends on FAILED" notes from one
     * {@link #analyze} pass — the channel by which a detector tells the scan
     * report that a finding may have been <em>suppressed by a failure</em>
     * rather than genuinely absent (fan-in search died, a binding it needed
     * was null). Without this channel the suppression is silent, and a
     * detector whose every lookup failed reports "none found" — the exact
     * empty-result-on-failure lie, one level below file reading.
     */
    public static final class ScanDegradation {
        private static final int MAX_NOTES = 25;
        private final List<String> notes = new ArrayList<>();
        private int count;

        /** Record one failed lookup (note capped; the count never is). */
        public void report(String note) {
            count++;
            if (notes.size() < MAX_NOTES) {
                notes.add(note);
            } else if (notes.size() == MAX_NOTES) {
                notes.add("... (further lookup failures elided; see the count)");
            }
        }

        public boolean isEmpty() {
            return count == 0;
        }

        public int count() {
            return count;
        }

        List<String> notes() {
            return notes;
        }
    }

    /**
     * Sprint 22a 2.6.1 — the source files a detector should scan: the single
     * {@code filePath} if set, else all non-test source files. A relative
     * {@code filePath} is RESOLVED against the project root, so it never leaks a
     * CWD/AppImage-mount path through {@code formatPath} (bug #3) and every finding
     * carries a project-relative path. Shared by the AST detectors AND the
     * git-history churn detectors ({@code DivergentChangeDetector}) so {@code filePath}
     * scopes EVERY detector uniformly (bug #2) — not just the AST ones.
     */
    public static List<Path> scopedSourceFiles(IJdtService service, JsonNode args) {
        String filePath = readString(args, "filePath");
        boolean includeTests = includeTests(args);
        List<Path> candidates = new ArrayList<>();
        if (filePath != null && !filePath.isBlank()) {
            Path p = Path.of(filePath);
            if (!p.isAbsolute() && service.getProjectRoot() != null) {
                p = service.getProjectRoot().resolve(p);
            }
            candidates.add(p.normalize());
        } else {
            candidates.addAll(service.getAllJavaFiles());
        }
        List<Path> out = new ArrayList<>();
        for (Path f : candidates) {
            if (includeTests || !isTestSource(f, service)) {
                out.add(f);
            }
        }
        return out;
    }

    /**
     * Analyse one parsed compilation unit and append findings.
     *
     * @param ast       the parsed unit (bindings resolved)
     * @param filePath  the formatted path for {@link Finding#filePath()}
     * @param service   the project-scoped JDT service (for cross-file queries)
     * @param threshold the effective threshold (caller value or {@link #defaultThreshold()})
     * @param out       collect findings here
     */
    protected abstract void analyze(CompilationUnit ast, String filePath,
                                    IJdtService service, int threshold, List<Finding> out);

    /**
     * Degradation-aware variant: detectors whose heuristic depends on lookups
     * that can FAIL (a reference search, a binding they must resolve) override
     * this one and {@link ScanDegradation#report report} every failed lookup —
     * a failed lookup suppresses the finding, and the suppression must reach
     * the scan report instead of masquerading as an absence. The default
     * delegates to the 5-arg form for the many detectors whose analysis is
     * purely structural.
     */
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out, ScanDegradation degraded) {
        analyze(ast, filePath, service, threshold, out);
    }

    /**
     * Parse a compilation unit with binding resolution; null on failure.
     *
     * <p>The failure is LOGGED at warn. It used to vanish entirely, which meant a file that
     * could not be parsed was indistinguishable from a file with nothing wrong in it — and
     * the caller was never told either had happened. The caller now counts these
     * ({@code unparseable}); the log is how you find out WHY.</p>
     */
    protected static CompilationUnit parse(ICompilationUnit cu) {
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            return (CompilationUnit) parser.createAST(null);
        } catch (Exception e) {
            LOG.warn("Parsing {} FAILED, so it was not scanned: {}: {}",
                cu.getElementName(), e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    protected static int readInt(JsonNode args, String name, int def) {
        if (args == null || !args.has(name) || args.get(name).isNull()) {
            return def;
        }
        return args.get(name).asInt(def);
    }

    protected static String readString(JsonNode args, String name) {
        if (args == null || !args.has(name) || args.get(name).isNull()) {
            return null;
        }
        return args.get(name).asText();
    }

    /** Whether the caller opted into scanning test sources (default false). */
    public static boolean includeTests(JsonNode args) {
        return args != null && args.has("includeTests") && !args.get("includeTests").isNull()
            && args.get("includeTests").asBoolean(false);
    }

    /**
     * Heuristic: is this a test source? Test code legitimately has long methods
     * and methods that hammer their subject-under-test, so smell scans exclude it
     * by default (the v1.2.1 dogfood found ~70% of long_method/feature_envy noise
     * came from test sources). Matches Maven ({@code src/test/}) and PDE/Tycho
     * test bundles ({@code *.tests/}).
     *
     * <p>The check runs on the path <em>relative to the project root</em> — an
     * absolute check falsely flags a project that merely lives under a
     * {@code *.tests/} directory (e.g. a fixture under
     * {@code org.jawata.core.tests/test-resources/…}).</p>
     */
    public static boolean isTestSource(Path path, IJdtService service) {
        if (path == null) {
            return false;
        }
        String rel;
        try {
            Path root = service != null ? service.getProjectRoot() : null;
            rel = (root != null ? root.relativize(path) : path).toString().replace('\\', '/');
        } catch (Exception e) {
            rel = path.toString().replace('\\', '/');
        }
        return rel.contains("src/test/") || rel.contains("test/java/") || rel.contains(".tests/");
    }
}

