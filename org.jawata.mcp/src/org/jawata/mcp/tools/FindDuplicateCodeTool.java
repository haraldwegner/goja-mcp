package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 14 Phase B.3 (v1.8.0) — {@code find_duplicate_code}: scan every
 * loaded method body, normalize the JDT token stream (identifiers and
 * literals collapsed to their kind), group methods sharing the same
 * normalized sequence as a clone group. Catches the IntelliJ-CPD style
 * "structurally-identical methods with different identifier names" pattern.
 *
 * <p>v1.8.0 MVP runs exact-match detection (similarity 1.0). The schema
 * accepts a {@code threshold} param but the only honoured value is 1.0;
 * fuzzy n-gram Jaccard matching is reserved for v1.8.1.</p>
 *
 * <h2>Normalization rules</h2>
 *
 * <ul>
 *   <li>Whitespace + comments: dropped.</li>
 *   <li>Identifiers (variables, parameters, fields, methods, types):
 *       collapsed to {@code ID}.</li>
 *   <li>String literals: collapsed to {@code STR}.</li>
 *   <li>Integer literals (int / long): {@code INT}.</li>
 *   <li>Floating-point literals (float / double): {@code FLT}.</li>
 *   <li>Character literals: {@code CHAR}.</li>
 *   <li>{@code null}: {@code NULL}.</li>
 *   <li>{@code true} / {@code false}: {@code BOOL}.</li>
 *   <li>Everything else (operators, keywords, punctuation): kept verbatim.</li>
 * </ul>
 *
 * <h2>Output</h2>
 *
 * <pre>{@code
 * {
 *   operation: "find_duplicate_code",
 *   groupCount: N,
 *   groups: [{
 *     groupId,  // stable hash of the clone shape — feed to replace_duplicates
 *     instances: [{
 *       filePath, line, methodName, tokenCount, similarity, sourceProject
 *     }, …]
 *   }, …]
 * }
 * }</pre>
 */
public class FindDuplicateCodeTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindDuplicateCodeTool.class);

    private static final int DEFAULT_MIN_TOKENS = 50;
    private static final double DEFAULT_THRESHOLD = 1.0;
    private static final boolean DEFAULT_CROSS_PROJECT = true;

    public FindDuplicateCodeTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_duplicate_code";
    }

    @Override
    public String getDescription() {
        return """
            Find clone groups of structurally-similar methods across the
            workspace via JDT-token-stream normalization. Catches the
            IntelliJ-CPD-style "same code shape, different identifier names"
            pattern that text search and grep miss.

            USAGE:
              find_duplicate_code()
              find_duplicate_code(projectKey="core")
              find_duplicate_code(minTokens=80)
              find_duplicate_code(crossProject=false)

            Inputs:
            - projectKey — optional. Restrict to a single loaded project.
            - minTokens — default 50. A method must produce at least this
              many normalized tokens to be considered. Filters out
              one-liner setters/getters that match trivially.
            - threshold — default 1.0. v1.8.0 honours 1.0 only (exact
              normalized-token-sequence match). Fuzzy n-gram Jaccard
              matching (≥0.85 similarity) is reserved for v1.8.1.
            - crossProject — default true. When false, clones are only
              grouped within a single project (cross-project clones not
              surfaced).

            Normalization:
            - Identifiers (locals, params, fields, methods, types) → ID
            - String literals → STR, ints → INT, floats → FLT,
              chars → CHAR, null → NULL, true/false → BOOL
            - Operators / keywords / punctuation kept verbatim
            - Whitespace + comments dropped

            Output groups contain ≥2 method instances with identical
            normalized token sequence (similarity 1.0) plus a stable groupId
            (hash of the clone shape) — pass it to replace_duplicates with
            the SAME parameters to delegate same-type clones to a canonical
            method. Empty groups list means no clones above minTokens.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("minTokens", Map.of(
            "type", "integer",
            "description", "Minimum normalized-token count per method (default 50)."));
        properties.put("threshold", Map.of(
            "type", "number",
            "description", "Match threshold; v1.8.0 honours 1.0 only (exact match). Default 1.0."));
        properties.put("crossProject", Map.of(
            "type", "boolean",
            "description", "When false, group clones only within a single project. Default true."));
        properties.put("limit", Map.of(
            "type", "integer",
            "description", "Max clone groups returned (default 20); groupCount always reflects the full set (v2.8.1)."));
        properties.put("offset", Map.of(
            "type", "integer",
            "description", "Skip the first N groups (pagination; default 0) (v2.8.1)."));
        properties.put("summary", Map.of(
            "type", "boolean",
            "description", "Counts only (groupCount/instanceCount), NO groups array — for large workspaces (v2.8.1). Default false."));
        schema.put("properties", properties);
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int minTokens = getIntParam(arguments, "minTokens", DEFAULT_MIN_TOKENS);
        double threshold = arguments != null && arguments.has("threshold")
            ? arguments.get("threshold").asDouble(DEFAULT_THRESHOLD)
            : DEFAULT_THRESHOLD;
        boolean crossProject = getBooleanParam(arguments, "crossProject", DEFAULT_CROSS_PROJECT);

        if (threshold < 1.0) {
            log.debug("threshold={} requested; v1.8.0 MVP honours 1.0 only — proceeding with exact match",
                threshold);
        }

        // projectKey handled locally (not via AbstractTool scoping) because
        // the no-projectKey case needs the full multi-project view.
        String projectKey = getStringParam(arguments, "projectKey");
        Collection<LoadedProject> projects;
        if (projectKey != null && !projectKey.isBlank()) {
            Optional<LoadedProject> scoped = service.getProject(projectKey);
            if (scoped.isEmpty()) {
                Optional<Long> dropped = service.wasRecentlyDropped(projectKey);
                if (dropped.isPresent()) {
                    return ToolResponse.projectKeyDropped(projectKey, dropped.get());
                }
                return ToolResponse.invalidParameter("projectKey",
                    "Unknown projectKey '" + projectKey + "'. Use list_projects.");
            }
            projects = List.of(scoped.get());
        } else {
            projects = service.allProjects();
        }

        try {
            ScanReport report = new ScanReport();
            List<Map<String, Object>> groups;
            if (crossProject) {
                // Pool every method into one bucket; group by normalized sequence.
                Map<String, List<MethodFingerprint>> pool = new HashMap<>();
                for (LoadedProject lp : projects) {
                    collectFingerprints(pool, lp, service, minTokens, report);
                }
                groups = buildGroups(pool);
            } else {
                // Per-project pools; clones must share a project to be grouped.
                groups = new ArrayList<>();
                for (LoadedProject lp : projects) {
                    Map<String, List<MethodFingerprint>> pool = new HashMap<>();
                    collectFingerprints(pool, lp, service, minTokens, report);
                    groups.addAll(buildGroups(pool));
                }
            }

            // An empty answer from a scan that failed is not an answer.
            Optional<ToolResponse> refusal = guardAgainstAnEmptyLie(groups, report);
            if (refusal.isPresent()) {
                return refusal.get();
            }

            groups.sort(Comparator.<Map<String, Object>>comparingInt(g ->
                -((List<?>) g.get("instances")).size())
                .thenComparing(g -> (String) firstInstance(g).get("filePath")));

            // v2.8.1 (dogfood 2026-07-11): the self-scan returned 105 groups
            // as one 68k-char response — page like compile_workspace v2.7.1.
            int totalGroups = groups.size();
            int instanceCount = groups.stream()
                .mapToInt(g -> ((List<?>) g.get("instances")).size()).sum();
            boolean summary = getBooleanParam(arguments, "summary", false);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "find_duplicate_code");
            data.put("groupCount", totalGroups);
            data.put("instanceCount", instanceCount);
            // WHAT WE ACTUALLY LOOKED AT. Without this, `groupCount: 0` is unreadable: it
            // could mean "no duplicates" or "we examined nothing", and those are different
            // facts. Now the caller can always tell which.
            data.putAll(report.describe());

            if (summary) {
                return ToolResponse.success(data, ResponseMeta.builder()
                    .totalCount(totalGroups)
                    .returnedCount(0)
                    .steering(scanSteering(totalGroups, report))
                    .build());
            }

            int limit = Math.max(1, getIntParam(arguments, "limit", 20));
            int offset = Math.max(0, getIntParam(arguments, "offset", 0));
            List<Map<String, Object>> page = offset >= totalGroups
                ? List.of()
                : groups.subList(offset, Math.min(offset + limit, totalGroups));
            boolean truncated = offset > 0 || page.size() < totalGroups;
            data.put("offset", offset);
            data.put("limit", limit);
            data.put("groups", page);
            if (truncated) {
                data.put("hint", "Page " + page.size() + " of " + totalGroups
                    + " groups returned — advance with offset, or pass summary:true for counts only.");
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalGroups)
                .returnedCount(page.size())
                .truncated(truncated)
                .steering(scanSteering(totalGroups, report))
                .build());
        } catch (Exception e) {
            log.warn("find_duplicate_code threw unexpectedly: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /** Never let a zero read as a clean bill of health without saying what was examined. */
    private static String scanSteering(int groupCount, ScanReport report) {
        if (report.incomplete()) {
            return "PARTIAL: the scan FAILED on " + report.failures.size() + " project(s), so "
                + "these results are what survived, not what exists. " + report.failures;
        }
        if (report.methodsExamined == 0) {
            return "Nothing was examined — 0 methods in " + report.projectsScanned + " project(s). "
                + "This is NOT 'no duplicate code'; there was nothing to compare. Check the "
                + "projectKey and that the project has source roots.";
        }
        if (groupCount == 0) {
            return "No duplicates among " + report.methodsExamined + " methods examined — and "
                + "the scan was complete, so this really is an absence, not a failure to look.";
        }
        return null;
    }

    private static Map<String, Object> firstInstance(Map<String, Object> group) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> instances = (List<Map<String, Object>>) group.get("instances");
        return instances.isEmpty() ? Map.of("filePath", "") : instances.get(0);
    }

    private static List<Map<String, Object>> buildGroups(Map<String, List<MethodFingerprint>> pool) {
        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<MethodFingerprint>> entry : pool.entrySet()) {
            List<MethodFingerprint> bucket = entry.getValue();
            if (bucket.size() < 2) continue;
            List<Map<String, Object>> instances = new ArrayList<>();
            for (MethodFingerprint fp : bucket) {
                Map<String, Object> inst = new LinkedHashMap<>();
                inst.put("filePath", fp.filePath);
                inst.put("line", fp.line);
                inst.put("methodName", fp.methodName);
                inst.put("tokenCount", fp.tokenCount);
                inst.put("similarity", 1.0);
                inst.put("sourceProject", fp.sourceProject);
                instances.add(inst);
            }
            Map<String, Object> group = new LinkedHashMap<>();
            // Sprint 14b: deterministic id — hash of the normalized token
            // sequence, so the same clone shape yields the same id across
            // calls and replace_duplicates can re-resolve it statelessly.
            group.put("groupId", groupIdOf(entry.getKey()));
            group.put("instances", instances);
            groups.add(group);
        }
        return groups;
    }

    /** Stable id for a clone shape: SHA-1 of the normalized token sequence. */
    static String groupIdOf(String normalizedSeq) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(normalizedSeq.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < hash.length; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-1 is mandatory on every JRE; fall back to a plain hash.
            return Integer.toHexString(normalizedSeq.hashCode());
        }
    }

    /**
     * What the scan actually managed to look at.
     *
     * <p>This exists because the alternative is a lie. Walking a project can fail — a
     * {@code JavaModelException} while the model is rebuilding, a classpath not yet
     * resolved — and if that failure is swallowed, the pool comes back empty and the tool
     * cheerfully reports "no duplicate code". But an empty result from a scan that never
     * ran is not an absence; it is a <b>failure to look</b>, and the two must never be
     * presented as the same fact. So the scan counts what it examined and keeps every
     * failure, and the caller sees both.</p>
     */
    static final class ScanReport {
        int projectsScanned;
        int methodsExamined;
        /** Methods whose source could not be read — silently dropped, until now. */
        int methodsUnreadable;
        /**
         * Projects that are no longer OPEN or no longer EXIST.
         *
         * <p>These are a legitimate SKIP, not a failure: there is nothing there to read, and
         * a project that has been closed or removed is not part of the scan. Counting them as
         * failures would make every whole-workspace scan cry "incomplete" for ever after
         * somebody closed a project — which is its own kind of false alarm. But they are still
         * NAMED, because "I did not look in there" is a fact the caller is entitled to.</p>
         */
        final List<String> projectsSkipped = new ArrayList<>();
        /** Projects that ARE there and could not be read. This is a real failure. */
        final List<Map<String, Object>> failures = new ArrayList<>();

        boolean incomplete() {
            return !failures.isEmpty();
        }

        Map<String, Object> describe() {
            Map<String, Object> described = new LinkedHashMap<>();
            described.put("projectsScanned", projectsScanned);
            described.put("methodsExamined", methodsExamined);
            if (methodsUnreadable > 0) {
                described.put("methodsUnreadable", methodsUnreadable);
            }
            if (!projectsSkipped.isEmpty()) {
                described.put("projectsSkipped", projectsSkipped);
                described.put("projectsSkippedReason",
                    "closed or no longer present in the workspace — nothing there to scan");
            }
            if (incomplete()) {
                described.put("scanIncomplete", true);
                described.put("failures", failures);
            }
            return described;
        }
    }

    /**
     * Reusable detection core for {@code replace_duplicates}: the pool of
     * method fingerprints keyed by normalized token sequence.
     */
    static Map<String, List<MethodFingerprint>> collectPool(IJdtService service,
                                                            Collection<LoadedProject> projects,
                                                            int minTokens) {
        return collectPool(service, projects, minTokens, new ScanReport());
    }

    static Map<String, List<MethodFingerprint>> collectPool(IJdtService service,
                                                            Collection<LoadedProject> projects,
                                                            int minTokens,
                                                            ScanReport report) {
        Map<String, List<MethodFingerprint>> pool = new HashMap<>();
        for (LoadedProject lp : projects) {
            collectFingerprints(pool, lp, service, minTokens, report);
        }
        return pool;
    }

    private static void collectFingerprints(Map<String, List<MethodFingerprint>> sink,
                                            LoadedProject lp,
                                            IJdtService service,
                                            int minTokens,
                                            ScanReport report) {
        IJavaProject jp = lp.javaProject();

        // A project that has been closed or removed is not a failed scan — it is nothing to
        // scan. `allProjects()` keeps handing out its handle, and walking it throws, so
        // without this check ONE stale project poisons the whole workspace sweep. (That is
        // exactly what it was doing: intermittently, invisibly, for months.)
        try {
            if (!jp.exists() || jp.getProject() == null || !jp.getProject().isOpen()) {
                log.debug("Project '{}' is closed or gone — not scanned", lp.projectKey());
                report.projectsSkipped.add(String.valueOf(lp.projectKey()));
                return;
            }
        } catch (Exception e) {
            report.projectsSkipped.add(String.valueOf(lp.projectKey()));
            return;
        }

        try {
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
                for (IJavaElement child : root.getChildren()) {
                    if (!(child instanceof IPackageFragment pkg)) continue;
                    for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                        for (IType type : cu.getAllTypes()) {
                            for (IMethod method : type.getMethods()) {
                                report.methodsExamined++;
                                MethodFingerprint fp = fingerprint(method, lp, service);
                                if (fp == null) {
                                    report.methodsUnreadable++;
                                    continue;
                                }
                                if (fp.tokenCount >= minTokens) {
                                    sink.computeIfAbsent(fp.normalizedSeq,
                                        k -> new ArrayList<>()).add(fp);
                                }
                            }
                        }
                    }
                }
            }
            report.projectsScanned++;
        } catch (Exception e) {
            // NOT swallowed. This used to be a debug log, and the result was that a project
            // we could not read came back as a project with no duplicates in it.
            log.warn("Scanning project '{}' for duplicates FAILED: {}: {}",
                lp.projectKey(), e.getClass().getSimpleName(), e.getMessage());
            report.failures.add(Map.of(
                "project", String.valueOf(lp.projectKey()),
                "error", e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())));
        }
    }

    /**
     * Refuse to answer "no duplicates" when we did not manage to look.
     *
     * @return an error response when the scan cannot support the answer, else empty
     */
    static Optional<ToolResponse> guardAgainstAnEmptyLie(List<Map<String, Object>> groups,
                                                         ScanReport report) {
        if (!report.incomplete()) {
            return Optional.empty();
        }
        if (!groups.isEmpty()) {
            return Optional.empty();   // partial, but it DID find things — reported with the flag
        }
        // Empty result + a failed scan. "No duplicates" would be a lie with a number on it.
        return Optional.of(ToolResponse.error("SCAN_INCOMPLETE",
            "The scan FAILED on " + report.failures.size() + " project(s), and found nothing in "
                + "what it could read. This is NOT 'no duplicate code' — it is 'we could not "
                + "look'. Failures: " + report.failures,
            "The Java model may still be rebuilding. Run refresh_workspace (or compile_workspace) "
                + "and try again; if it keeps failing, the project's classpath is broken."));
    }

    private static MethodFingerprint fingerprint(IMethod method, LoadedProject lp,
                                                  IJdtService service) {
        try {
            String source = method.getSource();
            if (source == null || source.isBlank()) return null;
            StringBuilder seq = new StringBuilder();
            int tokenCount = countAndNormalize(source.toCharArray(), seq);
            if (tokenCount == 0) return null;

            MethodFingerprint fp = new MethodFingerprint();
            fp.normalizedSeq = seq.toString();
            fp.tokenCount = tokenCount;
            fp.methodName = method.getElementName();
            fp.method = method;
            fp.sourceProject = lp.projectKey();
            try {
                java.nio.file.Path absolute = method.getResource().getLocation().toFile().toPath();
                fp.filePath = service.getPathUtils().formatPath(absolute);
            } catch (Exception ignore) {
                fp.filePath = method.getResource() != null
                    ? method.getResource().getName() : method.getElementName();
            }
            // Approximate line number from the method's source-range start.
            // method.getSourceRange().getOffset() is char-offset; cheap to
            // map by scanning the CU once, but we don't have the CU char
            // array here. As a fallback, use 0 — the agent can re-query
            // via search_symbols if needed.
            fp.line = approximateLine(method);
            return fp;
        } catch (Exception e) {
            log.debug("Failed to fingerprint method {}: {}",
                method.getElementName(), e.getMessage());
            return null;
        }
    }

    private static int approximateLine(IMethod method) {
        try {
            String cuSource = method.getCompilationUnit().getSource();
            int offset = method.getSourceRange().getOffset();
            if (cuSource == null || offset < 0) return 0;
            int line = 0;
            for (int i = 0; i < offset && i < cuSource.length(); i++) {
                if (cuSource.charAt(i) == '\n') line++;
            }
            return line;
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
        "\"(?:\\\\.|[^\"\\\\])*\"" +                                            // string literal
        "|'(?:\\\\.|[^'\\\\])*'" +                                              // char literal
        "|\\d+\\.\\d+(?:[eE][+-]?\\d+)?[fFdD]?|\\.\\d+(?:[eE][+-]?\\d+)?[fFdD]?" + // float literal
        "|\\d+[eE][+-]?\\d+[fFdD]?" +                                           // float scientific without dot
        "|\\d+[lL]?" +                                                          // int / long literal
        "|[a-zA-Z_$][a-zA-Z0-9_$]*" +                                           // identifier / keyword
        "|<<=|>>>=|>>=|<<|>>>|>>|<=|>=|==|!=|&&|\\|\\||\\+\\+|--|->|::" +       // multi-char operators
        "|[+\\-*/%&|^~?:;,(){}\\[\\]<>=!.@]"                                    // single-char operators/punctuation
    );

    private static final Set<String> JAVA_KEYWORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
        "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for",
        "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while",
        "var", "yield", "record", "sealed", "permits"
        // "non-sealed" is hyphenated; handled by the operator branch via "-" between two ID tokens.
    );

    /**
     * Regex-based tokenizer. JDT's {@code IScanner} would be ideal but is in
     * {@code org.eclipse.jdt.core.compiler}, which the headless Tycho test
     * runtime fails to resolve at OSGi load — Sprint 14 Stage 10 noted this
     * during integration. Regex tokenization is portable, fast enough, and
     * sufficient for clone detection (the normalization step throws away
     * exactness anyway).
     */
    private static int countAndNormalize(char[] source, StringBuilder seq) {
        String src = collapseNoise(new String(source));
        Matcher m = TOKEN_PATTERN.matcher(src);
        int count = 0;
        while (m.find()) {
            String tok = m.group();
            String norm = normalize(tok);
            if (norm == null) continue;
            if (seq.length() > 0) seq.append(' ');
            seq.append(norm);
            count++;
        }
        return count;
    }

    /**
     * v2.7.1 — linear single-pass scanner: drops comments and collapses
     * string / char / text-block literals to short placeholders BEFORE the
     * regex tokenizer runs.
     *
     * <p>Why not regex: the tokenizer's quantified-alternation literal
     * branches ({@code "(?:\\.|[^"\\])*"}) burn one backtracking frame per
     * matched char — a string literal past ~2k chars overflows the stack.
     * {@link StackOverflowError} is an {@link Error}, so it sailed past every
     * {@code catch (Exception)} and killed the transport worker (dogfood find
     * 2026-07-10: jawata-mcp's own tool descriptions are text blocks far past
     * the threshold — self-scan dropped the socket). A hand scan is O(n) with
     * zero recursion, and also lexes text blocks correctly, which the old
     * regex mis-read as an empty string plus a dangling quote.</p>
     */
    static String collapseNoise(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        final int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                // line comment — drop to EOL
                while (i < n && src.charAt(i) != '\n') i++;
                out.append(' ');
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                // block comment / Javadoc — drop to */
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, n);
                out.append(' ');
            } else if (c == '"' && i + 2 < n
                    && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                // text block — collapse to a short string placeholder
                i += 3;
                while (i + 2 < n && !(src.charAt(i) == '"'
                        && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"')) {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 3, n);
                out.append("\"S\"");
            } else if (c == '"') {
                // string literal — collapse (escape-aware)
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append("\"S\"");
            } else if (c == '\'') {
                // char literal — collapse (escape-aware)
                i++;
                while (i < n && src.charAt(i) != '\'') {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append("'c'");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String normalize(String token) {
        if (token.isEmpty()) return null;
        char first = token.charAt(0);
        if (first == '"') return "STR";
        if (first == '\'') return "CHAR";
        if (Character.isDigit(first) || (first == '.' && token.length() > 1 && Character.isDigit(token.charAt(1)))) {
            char last = token.charAt(token.length() - 1);
            if (token.contains(".") || token.contains("e") || token.contains("E")
                || last == 'f' || last == 'F' || last == 'd' || last == 'D') {
                return "FLT";
            }
            return "INT";
        }
        if (Character.isJavaIdentifierStart(first)) {
            if ("null".equals(token)) return "NULL";
            if ("true".equals(token) || "false".equals(token)) return "BOOL";
            if (JAVA_KEYWORDS.contains(token)) return token;
            return "ID";
        }
        // Operator / punctuation: keep verbatim
        return token;
    }

    /** Package-visible so replace_duplicates can reuse the detection pool. */
    static final class MethodFingerprint {
        String normalizedSeq;
        int tokenCount;
        String methodName;
        IMethod method;
        String filePath;
        int line;
        String sourceProject;
    }
}
