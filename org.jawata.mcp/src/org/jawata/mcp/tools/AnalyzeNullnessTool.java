package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.knowledge.Confidence;
import org.jawata.mcp.knowledge.SymbolFact;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.nullness.NullnessStyle;
import org.jawata.mcp.tools.shared.SourceScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 15b — null-safety analysis tool (parametric, read-only; {@code analyze_}
 * prefix → readOnlyHint). Model-free.
 *
 * <p>Kinds added across the sprint: {@code detect_style} (this stage) identifies
 * the project's nullness annotation family; {@code find_violations},
 * {@code infer_contracts}, {@code check} follow. Source-mutating operations live
 * in the separate {@code apply_null_annotations} tool (apply/undo contract).</p>
 */
public class AnalyzeNullnessTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeNullnessTool.class);

    static final Set<String> KINDS = Set.of("detect_style", "find_violations", "infer_contracts", "check");

    /**
     * Type-level annotations that mark a type as framework-managed or generated —
     * a RISKY signal where conservative inference emits NO contract (the type's
     * members may be reflectively/externally populated). Source-only simple names.
     */
    private static final Set<String> RISKY_TYPE_ANNOTATIONS = Set.of(
        "Generated", "Entity", "Embeddable", "Component", "Service", "Repository",
        "Controller", "RestController", "Configuration", "Mapper");

    /**
     * Compiler options enabling null analysis, overlaid on the parser (no project
     * mutation — same pattern as AnalyzeJavadocsTool.validate). Flow checks
     * (nullReference/potentialNullReference) work WITHOUT annotations; the
     * annotation-name + nullanalysis options additionally activate contract
     * checks when the project carries its nullness annotations. The annotation
     * FQNs are set per-project to the detected family (or Eclipse defaults).
     */
    private static final Map<String, String> NULL_FLOW_OPTIONS = Map.of(
        "org.eclipse.jdt.core.compiler.problem.nullReference", "warning",
        "org.eclipse.jdt.core.compiler.problem.potentialNullReference", "warning",
        "org.eclipse.jdt.core.compiler.annotation.nullanalysis", "enabled",
        "org.eclipse.jdt.core.compiler.problem.nullSpecViolation", "warning",
        "org.eclipse.jdt.core.compiler.problem.nullAnnotationInferenceConflict", "warning",
        "org.eclipse.jdt.core.compiler.problem.nullUncheckedConversion", "warning");

    public AnalyzeNullnessTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_nullness";
    }

    @Override
    public String getDescription() {
        return """
            Java null-safety analysis. Read-only; model-free. Source-mutating ops
            (add/migrate annotations) are in apply_null_annotations.

            USAGE: analyze_nullness(kind="detect_style")

            KINDS:
            - detect_style    — identify the nullness annotation family already used in the
                                project (JSpecify / Eclipse JDT / JetBrains / JSR-305 / SpotBugs /
                                Checker / AndroidX), by import scan. Returns the dominant family,
                                per-family counts, and evidence. "none" when no family is used.
            - find_violations — enable JDT null analysis and report probable null bugs (null
                                dereferences, nullable→non-null flows, contract mismatches).
                                Flow checks work with no annotations; contract checks activate
                                when the project carries its nullness jar. Compiler-driven.
            - infer_contracts — conservatively infer nullness contracts (param/return) from
                                strong signals (Objects.requireNonNull → @NonNull; `return null`
                                → @Nullable) as null_contract facts. Emits NO contract inside
                                framework-managed/generated types (risky). A contract IS an API
                                contract — false confidence is worse than none.
            - check          — focused: detected style + violations + inferred contracts for a
                                single `filePath` or `symbol` (FQN).

            Optional: filePath to scope; symbol (FQN) for check; projectKey; maxResults.
            Requires load_project.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", KINDS);
        kind.put("description", "Which null-safety analysis to run. See the tool description.");
        properties.put("kind", kind);
        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "Optional. Restrict find_violations/infer_contracts to one file; omit to scan the project. For check: the file to focus.");
        properties.put("filePath", filePath);
        Map<String, Object> symbol = new LinkedHashMap<>();
        symbol.put("type", "string");
        symbol.put("description", "For kind=check: focus on this FQN symbol (resolves to its file).");
        properties.put("symbol", symbol);
        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Cap on results (default 50 for detect_style evidence; 200 for violations).");
        properties.put("maxResults", maxResults);
        schema.put("properties", properties);
        schema.put("required", List.of("kind"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + KINDS);
        }
        if (!KINDS.contains(kind)) {
            return ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        }
        return switch (kind) {
            case "detect_style" -> detectStyle(service, arguments);
            case "find_violations" -> findViolations(service, arguments);
            case "infer_contracts" -> inferContracts(service, arguments);
            case "check" -> check(service, arguments);
            default -> ToolResponse.invalidParameter("kind", "Unhandled kind '" + kind + "'");
        };
    }

    private ToolResponse findViolations(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 200);
        String filePath = getStringParam(arguments, "filePath");

        List<Path> targets = new ArrayList<>();
        try {
            if (filePath != null && !filePath.isBlank()) {
                Path p = Path.of(filePath);
                if (service.getCompilationUnit(p) == null) {
                    return ToolResponse.fileNotFound(filePath);
                }
                targets.add(p);
            } else {
                targets.addAll(service.getAllJavaFiles());
            }

            NullnessStyle family = dominantStyle(service);

            List<Map<String, Object>> findings = new ArrayList<>();
            SourceScan scan = SourceScan.of(targets);
            for (Path path : scan.files()) {
                if (findings.size() >= maxResults) {
                    break;
                }
                ICompilationUnit cu = scan.resolve(service, path);
                if (cu == null) {
                    continue;   // RECORDED, not swallowed — see SourceScan
                }
                scan.examined();
                collectFileViolations(service, cu, family, findings, maxResults);
            }

            // "No null violations" is only sayable if we managed to read the code.
            Optional<ToolResponse> blind = scan.refuseIfBlind("null violations");
            if (blind.isPresent()) {
                return blind.get();
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "analyze_nullness");
            data.put("kind", "find_violations");
            data.put("nullnessStyle", family.name());
            data.put("violationCount", findings.size());
            data.put("violations", findings);
            data.putAll(scan.describe());
            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(findings.size())
                .returnedCount(findings.size())
                .steering(scan.steering(findings.size(), "null violations"))
                .suggestedNextTools(List.of(
                    "apply_null_annotations(kind=add) to record a confirmed contract"))
                .build());
        } catch (Exception e) {
            log.error("analyze_nullness(find_violations) failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /** Per-file null-violation collection (shared by find_violations + check). */
    private void collectFileViolations(IJdtService service, ICompilationUnit cu,
                                       NullnessStyle family, List<Map<String, Object>> out, int max)
            throws Exception {
        Map<String, String> opts = new java.util.HashMap<>(cu.getJavaProject().getOptions(true));
        opts.putAll(NULL_FLOW_OPTIONS);
        opts.put("org.eclipse.jdt.core.compiler.annotation.nullable", family.nullableFqn());
        opts.put("org.eclipse.jdt.core.compiler.annotation.nonnull", family.nonnullFqn());
        if (family == NullnessStyle.ECLIPSE) {
            opts.put("org.eclipse.jdt.core.compiler.annotation.nonnullbydefault",
                "org.eclipse.jdt.annotation.NonNullByDefault");
        }
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setCompilerOptions(opts);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        if (ast == null) {
            return;
        }
        String rel = service.getPathUtils().formatPath(pathOf(cu, service));
        for (org.eclipse.jdt.core.compiler.IProblem problem : ast.getProblems()) {
            if (out.size() >= max) {
                break;
            }
            String msg = problem.getMessage();
            // Null problems have no dedicated IProblem category; their messages reliably
            // mention "null". Match on that — robust + compile-safe.
            if (msg == null || !msg.toLowerCase().contains("null")) {
                continue;
            }
            Map<String, Object> finding = new LinkedHashMap<>();
            finding.put("filePath", rel);
            finding.put("line", problem.getSourceLineNumber() - 1);
            finding.put("severity", problem.isError() ? "error" : "warning");
            finding.put("message", msg);
            finding.put("problemId", problem.getID());
            out.add(finding);
        }
    }

    private ToolResponse inferContracts(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 200);
        String filePath = getStringParam(arguments, "filePath");
        try {
            List<Path> targets = new ArrayList<>();
            if (filePath != null && !filePath.isBlank()) {
                Path p = Path.of(filePath);
                if (service.getCompilationUnit(p) == null) {
                    return ToolResponse.fileNotFound(filePath);
                }
                targets.add(p);
            } else {
                targets.addAll(service.getAllJavaFiles());
            }
            List<Map<String, Object>> contracts = new ArrayList<>();
            SourceScan scan = SourceScan.of(targets);
            for (Path path : scan.files()) {
                if (contracts.size() >= maxResults) {
                    break;
                }
                ICompilationUnit cu = scan.resolve(service, path);
                if (cu != null) {   // a null here is RECORDED, not swallowed — see SourceScan
                    scan.examined();
                    inferContractsForFile(service, cu, contracts, maxResults);
                }
            }

            // "No inferable contracts" is only sayable if we managed to read the code.
            Optional<ToolResponse> blind = scan.refuseIfBlind("inferable nullness contracts");
            if (blind.isPresent()) {
                return blind.get();
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "analyze_nullness");
            data.put("kind", "infer_contracts");
            data.put("contractCount", contracts.size());
            data.put("contracts", contracts);
            data.putAll(scan.describe());
            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(contracts.size())
                .returnedCount(contracts.size())
                .steering(scan.steering(contracts.size(), "inferable nullness contracts"))
                .suggestedNextTools(List.of(
                    "apply_null_annotations(kind=add) to record a confirmed contract"))
                .build());
        } catch (Exception e) {
            log.error("analyze_nullness(infer_contracts) failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private void inferContractsForFile(IJdtService service, ICompilationUnit cu,
                                       List<Map<String, Object>> out, int max) throws Exception {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        String rel = service.getPathUtils().formatPath(pathOf(cu, service));
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (isRiskyType(node)) {
                    return false; // framework-managed/generated → emit no contract (risky)
                }
                for (MethodDeclaration m : node.getMethods()) {
                    if (out.size() >= max) {
                        return false;
                    }
                    inferMethod(m, rel, out);
                }
                return true; // descend into nested types
            }
        });
    }

    private void inferMethod(MethodDeclaration m, String rel, List<Map<String, Object>> out) {
        String fqn = fqnOfMethod(m);
        // @NonNull params: validated by Objects.requireNonNull(param).
        for (Object o : m.parameters()) {
            SingleVariableDeclaration p = (SingleVariableDeclaration) o;
            String name = p.getName().getIdentifier();
            if (bodyRequiresNonNull(m, name)) {
                addContract(out, fqn, "param:" + name, "nonnull", Confidence.HIGH, rel,
                    "Objects.requireNonNull(" + name + ")");
            }
        }
        // @Nullable return: an explicit `return null`.
        if (m.getReturnType2() != null && !m.isConstructor() && bodyReturnsNull(m)) {
            addContract(out, fqn, "return", "nullable", Confidence.HIGH, rel, "return null");
        }
    }

    private ToolResponse check(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        String symbol = getStringParam(arguments, "symbol");
        try {
            ICompilationUnit cu = null;
            if (filePath != null && !filePath.isBlank()) {
                cu = service.getCompilationUnit(Path.of(filePath));
                if (cu == null) {
                    return ToolResponse.fileNotFound(filePath);
                }
            } else if (symbol != null && !symbol.isBlank()) {
                java.util.Optional<org.eclipse.jdt.core.IJavaElement> el =
                    org.jawata.mcp.tools.fqn.FqnResolver.resolveWorkspace(symbol, service);
                if (el.isEmpty()) {
                    return ToolResponse.symbolNotFound(symbol);
                }
                org.eclipse.jdt.core.IJavaElement anc =
                    el.get().getAncestor(org.eclipse.jdt.core.IJavaElement.COMPILATION_UNIT);
                if (!(anc instanceof ICompilationUnit found)) {
                    return ToolResponse.invalidParameter("symbol", "symbol has no source file");
                }
                cu = found;
            } else {
                return ToolResponse.invalidParameter("filePath/symbol",
                    "check requires a filePath or a symbol (FQN)");
            }

            NullnessStyle family = dominantStyle(service);
            List<Map<String, Object>> violations = new ArrayList<>();
            collectFileViolations(service, cu, family, violations, 200);
            List<Map<String, Object>> contracts = new ArrayList<>();
            inferContractsForFile(service, cu, contracts, 200);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "analyze_nullness");
            data.put("kind", "check");
            data.put("target", symbol != null && !symbol.isBlank() ? symbol
                : service.getPathUtils().formatPath(pathOf(cu, service)));
            data.put("detectedStyle", family.name());
            data.put("violations", violations);
            data.put("contracts", contracts);
            return ToolResponse.success(data, ResponseMeta.builder().build());
        } catch (Exception e) {
            log.error("analyze_nullness(check) failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private static boolean isRiskyType(TypeDeclaration node) {
        for (Object o : node.modifiers()) {
            if (o instanceof Annotation ann) {
                String fqn = ann.getTypeName().getFullyQualifiedName();
                String simple = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                if (RISKY_TYPE_ANNOTATIONS.contains(simple)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean bodyRequiresNonNull(MethodDeclaration m, String paramName) {
        if (m.getBody() == null) {
            return false;
        }
        boolean[] found = {false};
        m.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if ("requireNonNull".equals(node.getName().getIdentifier())
                        && !node.arguments().isEmpty()
                        && node.arguments().get(0) instanceof SimpleName sn
                        && sn.getIdentifier().equals(paramName)) {
                    found[0] = true;
                }
                return true;
            }
        });
        return found[0];
    }

    private static boolean bodyReturnsNull(MethodDeclaration m) {
        if (m.getBody() == null) {
            return false;
        }
        boolean[] found = {false};
        m.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                if (node.getExpression() instanceof NullLiteral) {
                    found[0] = true;
                }
                return true;
            }
        });
        return found[0];
    }

    private static String fqnOfMethod(MethodDeclaration m) {
        IMethodBinding b = m.resolveBinding();
        if (b != null && b.getDeclaringClass() != null) {
            return b.getDeclaringClass().getQualifiedName() + "#" + b.getName();
        }
        return m.getName().getIdentifier();
    }

    private static void addContract(List<Map<String, Object>> out, String fqn, String target,
                                    String nullness, Confidence conf, String rel, String evidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nullness", nullness);
        m.put("target", target);
        m.putAll(SymbolFact
            .of("null_contract", target + " is @" + (nullness.equals("nonnull") ? "NonNull" : "Nullable")
                + " on " + fqn, conf)
            .symbol(fqn)
            .source("inference", rel)
            .evidence(List.<Object>of(evidence))
            .build()
            .toMap());
        out.add(m);
    }

    private static Path pathOf(ICompilationUnit cu, IJdtService service) {
        try {
            if (cu.getResource() != null && cu.getResource().getLocation() != null) {
                return Path.of(cu.getResource().getLocation().toOSString());
            }
        } catch (RuntimeException ignore) {
            // fall through
        }
        return Path.of(cu.getElementName());
    }

    /** Dominant nullness family across the project, or the Eclipse default for option FQNs. */
    private NullnessStyle dominantStyle(IJdtService service) throws Exception {
        // A vote. A file we cannot read is a vote never counted — and enough of those make the
        // "dominant" family simply the wrong one, chosen in silence.
        Map<NullnessStyle, Integer> totals = new EnumMap<>(NullnessStyle.class);
        org.jawata.mcp.tools.shared.SourceScan scan =
            org.jawata.mcp.tools.shared.SourceScan.of(service.getAllJavaFiles());
        for (Path path : scan.files()) {
            ICompilationUnit cu = scan.resolve(service, path);
            if (cu == null) {
                continue;
            }
            CompilationUnit ast = scan.parse(cu, path, false);
            if (ast == null) {
                continue;
            }
            scan.examined();
            List<String> imports = new ArrayList<>();
            for (Object o : ast.imports()) {
                imports.add(((ImportDeclaration) o).getName().getFullyQualifiedName());
            }
            NullnessStyle.tally(imports).forEach((s, n) -> totals.merge(s, n, Integer::sum));
        }
        NullnessStyle dominant = NullnessStyle.ECLIPSE; // option-FQN default (JDT-native)
        int best = 0;
        for (Map.Entry<NullnessStyle, Integer> e : totals.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                dominant = e.getKey();
            }
        }
        return dominant;
    }

    private ToolResponse detectStyle(IJdtService service, JsonNode arguments) {
        int maxEvidence = getIntParam(arguments, "maxResults", 50);
        Map<NullnessStyle, Integer> totals = new EnumMap<>(NullnessStyle.class);
        List<String> evidence = new ArrayList<>();
        SourceScan scan = SourceScan.of(service.getAllJavaFiles());
        try {
            for (Path path : scan.files()) {
                ICompilationUnit cu = scan.resolve(service, path);
                if (cu == null) {
                    continue;   // RECORDED, not swallowed — see SourceScan
                }
                CompilationUnit ast = scan.parse(cu, path, false);
                if (ast == null) {
                    continue;
                }
                scan.examined();
                List<String> imports = new ArrayList<>();
                for (Object o : ast.imports()) {
                    imports.add(((ImportDeclaration) o).getName().getFullyQualifiedName());
                }
                Map<NullnessStyle, Integer> fileTally = NullnessStyle.tally(imports);
                if (!fileTally.isEmpty()) {
                    fileTally.forEach((s, n) -> totals.merge(s, n, Integer::sum));
                    if (evidence.size() < maxEvidence) {
                        evidence.add(service.getPathUtils().formatPath(path));
                    }
                }
            }
        } catch (Exception e) {
            log.error("analyze_nullness(detect_style) failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }

        // "No nullness family in use" is only sayable if we managed to read the imports.
        Optional<ToolResponse> blind = scan.refuseIfBlind("nullness annotation family in use");
        if (blind.isPresent()) {
            return blind.get();
        }

        NullnessStyle dominant = null;
        int best = 0;
        Map<String, Object> families = new LinkedHashMap<>();
        for (Map.Entry<NullnessStyle, Integer> e : totals.entrySet()) {
            families.put(e.getKey().name(), e.getValue());
            if (e.getValue() > best) {
                best = e.getValue();
                dominant = e.getKey();
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "analyze_nullness");
        data.put("kind", "detect_style");
        data.put("detectedStyle", dominant == null ? "none" : dominant.name());
        data.put("families", families);
        data.put("evidence", evidence);
        data.putAll(scan.describe());
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering(scan.steering(families.size(), "nullness annotation family in use"))
            .suggestedNextTools(List.of(
                "analyze_nullness(kind=find_violations) to surface probable null bugs"))
            .build());
    }
}
