package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MemberRef;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodRef;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.goja.core.IJdtService;
import org.goja.mcp.knowledge.Confidence;
import org.goja.mcp.knowledge.SymbolFact;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 15a — Javadoc knowledge tool (parametric). Read-only ({@code analyze_}
 * prefix → readOnlyHint). Model-free: it surfaces what JDT can derive, never
 * model-written prose.
 *
 * <p>Kinds (added incrementally across the sprint): {@code ingest} (this stage)
 * parses existing Javadocs into symbol-anchored {@link SymbolFact}s. Structured
 * tags ({@code @param}/{@code @return}/{@code @throws}/{@code @deprecated}/
 * {@code @see}) are machine-readable → HIGH confidence; free-text prose is
 * surfaced verbatim at LOW confidence (no semantic extraction — honest about
 * what is deterministic).</p>
 */
public class AnalyzeJavadocsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeJavadocsTool.class);

    static final Set<String> KINDS = Set.of("ingest", "validate", "generate");

    /**
     * Doc-comment compiler options overlaid on the parser for {@code validate}
     * (passed via {@code ASTParser.setCompilerOptions} — NOT set on the project,
     * so there is no global side effect to restore). Deliberately enables
     * invalid/missing-TAG detection on documented members but NOT
     * {@code missingJavadocComments} — that would flag every undocumented
     * getter/setter (the "don't spam trivial getters" rule). "Missing entirely"
     * detection is intentionally out of scope for v1.11.
     */
    private static final Map<String, String> DOC_OPTIONS = Map.of(
        "org.eclipse.jdt.core.compiler.doc.comment.support", "enabled",
        "org.eclipse.jdt.core.compiler.problem.invalidJavadoc", "warning",
        "org.eclipse.jdt.core.compiler.problem.invalidJavadocTags", "enabled",
        "org.eclipse.jdt.core.compiler.problem.missingJavadocTags", "warning",
        "org.eclipse.jdt.core.compiler.problem.missingJavadocTagsVisibility", "public");

    public AnalyzeJavadocsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_javadocs";
    }

    @Override
    public String getDescription() {
        return """
            Javadoc knowledge tool. Read-only; model-free (surfaces facts/evidence,
            never model-written prose).

            USAGE: analyze_javadocs(kind="ingest")

            KINDS:
            - ingest   — parse existing Javadocs into symbol-anchored facts
                         { type, symbol, summary, details, source, confidence, evidence }.
                         Structured tags (@param/@return/@throws/@deprecated/@see) are
                         HIGH confidence; free-text prose is surfaced at LOW confidence.
            - validate — doclint-style validation (via the compiler): reports broken/invalid
                         Javadoc and missing tags on DOCUMENTED members. Does not flag
                         undocumented getters/setters (no missing-comment spam).
            - generate — emit a doclint-correct Javadoc SKELETON + extracted evidence for a
                         target symbol (FQN). MODEL-FREE: it returns @param/@return/@throws
                         stubs + prosePlaceholders; the calling agent writes the prose (see
                         sprint-future-agent-runner.md). Trivial getters/setters → skip:true.
                         Requires `symbol` (e.g. "com.foo.Bar#method").

            Optional: filePath to scope ingest/validate to one file; symbol (FQN) for generate;
            projectKey; maxResults (default 200). Requires load_project first.
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
        kind.put("description", "Which Javadoc operation to run. See the tool description.");
        properties.put("kind", kind);
        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "Optional. Restrict to one file; omit to scan the project.");
        properties.put("filePath", filePath);
        Map<String, Object> symbol = new LinkedHashMap<>();
        symbol.put("type", "string");
        symbol.put("description", "For kind=generate: FQN target, e.g. \"com.foo.Bar\" or \"com.foo.Bar#method\".");
        properties.put("symbol", symbol);
        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Cap on facts returned (default 200).");
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
            case "ingest" -> ingest(service, arguments);
            case "validate" -> validate(service, arguments);
            case "generate" -> generate(service, arguments);
            default -> ToolResponse.invalidParameter("kind", "Unhandled kind '" + kind + "'");
        };
    }

    private ToolResponse generate(IJdtService service, JsonNode arguments) {
        String symbol = getStringParam(arguments, "symbol");
        if (symbol == null || symbol.isBlank()) {
            return ToolResponse.invalidParameter("symbol",
                "kind=generate requires a target FQN, e.g. \"com.foo.Bar#method\" or \"com.foo.Bar\".");
        }
        try {
            java.util.Optional<org.eclipse.jdt.core.IJavaElement> resolved =
                org.goja.mcp.tools.fqn.FqnResolver.resolveWorkspace(symbol, service);
            if (resolved.isEmpty()) {
                return ToolResponse.symbolNotFound(
                    symbol + " — expected a type/method/field FQN like \"com.foo.Bar#method\".");
            }
            org.eclipse.jdt.core.IJavaElement element = resolved.get();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "analyze_javadocs");
            data.put("kind", "generate");
            data.put("symbol", symbol);

            if (element instanceof org.eclipse.jdt.core.IMethod method) {
                if (isTrivialAccessor(method)) {
                    data.put("targetKind", "method");
                    data.put("skip", true);
                    data.put("reason", "trivial accessor — not worth documenting");
                    return ToolResponse.success(data, ResponseMeta.builder().build());
                }
                fillMethodSkeleton(method, data);
            } else if (element instanceof org.eclipse.jdt.core.IType type) {
                fillTypeSkeleton(type, data);
            } else if (element instanceof org.eclipse.jdt.core.IField field) {
                fillFieldSkeleton(field, data);
            } else {
                return ToolResponse.invalidParameter("symbol",
                    "Target must be a type, method, or field; got " + element.getElementName());
            }

            data.put("note", "MODEL-FREE: prose is the calling agent's job — fill the "
                + "prosePlaceholders into the skeleton (see sprint-future-agent-runner.md).");
            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "the agent writes prose into the skeleton, then applies it"))
                .build());
        } catch (Exception e) {
            log.error("analyze_javadocs(generate) failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private static boolean isTrivialAccessor(org.eclipse.jdt.core.IMethod m) throws Exception {
        String n = m.getElementName();
        int params = m.getNumberOfParameters();
        boolean voidReturn = "V".equals(m.getReturnType());
        boolean getter = (n.startsWith("get") || n.startsWith("is")) && params == 0 && !voidReturn;
        boolean setter = n.startsWith("set") && params == 1 && voidReturn;
        return (getter || setter) && !m.isConstructor();
    }

    private static void fillMethodSkeleton(org.eclipse.jdt.core.IMethod m, Map<String, Object> data)
            throws Exception {
        String[] paramNames = m.getParameterNames();
        boolean ctor = m.isConstructor();
        boolean voidReturn = "V".equals(m.getReturnType());
        List<String> throwsSimple = new ArrayList<>();
        for (String sig : m.getExceptionTypes()) {
            throwsSimple.add(org.eclipse.jdt.core.Signature.getSignatureSimpleName(sig));
        }

        List<String> placeholders = new ArrayList<>();
        placeholders.add("summary");
        StringBuilder sk = new StringBuilder("/**\n * TODO: summarize ")
            .append(m.getElementName()).append(".\n");
        if (paramNames.length > 0 || (!voidReturn && !ctor) || !throwsSimple.isEmpty()) {
            sk.append(" *\n");
        }
        for (String p : paramNames) {
            sk.append(" * @param ").append(p).append(" TODO: describe\n");
            placeholders.add("@param " + p);
        }
        if (!voidReturn && !ctor) {
            sk.append(" * @return TODO: describe\n");
            placeholders.add("@return");
        }
        for (String t : throwsSimple) {
            sk.append(" * @throws ").append(t).append(" TODO: describe\n");
            placeholders.add("@throws " + t);
        }
        sk.append(" */");

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("visibility", visibility(m.getFlags()));
        evidence.put("constructor", ctor);
        if (!ctor) {
            evidence.put("returns", org.eclipse.jdt.core.Signature
                .getSignatureSimpleName(m.getReturnType()));
        }
        if (!throwsSimple.isEmpty()) {
            evidence.put("throws", throwsSimple);
        }
        evidence.put("deprecated", org.eclipse.jdt.core.Flags.isDeprecated(m.getFlags()));

        data.put("targetKind", "method");
        data.put("skip", false);
        data.put("skeleton", sk.toString());
        data.put("evidence", evidence);
        data.put("prosePlaceholders", placeholders);
    }

    private static void fillTypeSkeleton(org.eclipse.jdt.core.IType t, Map<String, Object> data)
            throws Exception {
        List<String> placeholders = new ArrayList<>();
        placeholders.add("summary");
        StringBuilder sk = new StringBuilder("/**\n * TODO: summarize ")
            .append(t.getElementName()).append(".\n");
        org.eclipse.jdt.core.ITypeParameter[] tps = t.getTypeParameters();
        if (tps.length > 0) {
            sk.append(" *\n");
            for (org.eclipse.jdt.core.ITypeParameter tp : tps) {
                sk.append(" * @param <").append(tp.getElementName()).append("> TODO: describe\n");
                placeholders.add("@param <" + tp.getElementName() + ">");
            }
        }
        sk.append(" */");

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("visibility", visibility(t.getFlags()));
        evidence.put("kind", t.isInterface() ? "interface" : t.isEnum() ? "enum"
            : t.isAnnotation() ? "annotation" : "class");
        evidence.put("deprecated", org.eclipse.jdt.core.Flags.isDeprecated(t.getFlags()));

        data.put("targetKind", "type");
        data.put("skip", false);
        data.put("skeleton", sk.toString());
        data.put("evidence", evidence);
        data.put("prosePlaceholders", placeholders);
    }

    private static void fillFieldSkeleton(org.eclipse.jdt.core.IField f, Map<String, Object> data)
            throws Exception {
        String sk = "/**\n * TODO: describe " + f.getElementName() + ".\n */";
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("visibility", visibility(f.getFlags()));
        evidence.put("type", org.eclipse.jdt.core.Signature.getSignatureSimpleName(f.getTypeSignature()));
        evidence.put("constant", org.eclipse.jdt.core.Flags.isStatic(f.getFlags())
            && org.eclipse.jdt.core.Flags.isFinal(f.getFlags()));
        data.put("targetKind", "field");
        data.put("skip", false);
        data.put("skeleton", sk);
        data.put("evidence", evidence);
        data.put("prosePlaceholders", List.of("summary"));
    }

    private static String visibility(int flags) {
        if (org.eclipse.jdt.core.Flags.isPublic(flags)) {
            return "public";
        }
        if (org.eclipse.jdt.core.Flags.isProtected(flags)) {
            return "protected";
        }
        if (org.eclipse.jdt.core.Flags.isPrivate(flags)) {
            return "private";
        }
        return "package-private";
    }

    private List<Path> resolveTargets(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        List<Path> targets = new ArrayList<>();
        if (filePath != null && !filePath.isBlank()) {
            Path p = Path.of(filePath);
            if (service.getCompilationUnit(p) != null) {
                targets.add(p);
            }
            return targets; // empty → caller reports fileNotFound
        }
        targets.addAll(service.getAllJavaFiles());
        return targets;
    }

    private ToolResponse validate(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 200);
        String filePath = getStringParam(arguments, "filePath");
        List<Path> targets = resolveTargets(service, arguments);
        if (targets.isEmpty() && filePath != null && !filePath.isBlank()) {
            return ToolResponse.fileNotFound(filePath);
        }

        List<Map<String, Object>> findings = new ArrayList<>();
        try {
            for (Path path : targets) {
                if (findings.size() >= maxResults) {
                    break;
                }
                ICompilationUnit cu = service.getCompilationUnit(path);
                if (cu == null) {
                    continue;
                }
                // Doc-comment problem detection via parser options ONLY — no project
                // mutation, so there is nothing to restore and no global side effect.
                // (cu.reconcile(FORCE_PROBLEM_DETECTION) only works in working-copy mode.)
                Map<String, String> opts = new java.util.HashMap<>(cu.getJavaProject().getOptions(true));
                opts.putAll(DOC_OPTIONS);

                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setSource(cu);
                parser.setKind(ASTParser.K_COMPILATION_UNIT);
                parser.setResolveBindings(true);
                parser.setBindingsRecovery(true);
                parser.setCompilerOptions(opts);
                CompilationUnit ast = (CompilationUnit) parser.createAST(null);
                if (ast == null) {
                    continue;
                }
                String rel = service.getPathUtils().formatPath(path);
                for (org.eclipse.jdt.core.compiler.IProblem problem : ast.getProblems()) {
                    if (findings.size() >= maxResults) {
                        break;
                    }
                    if (!(problem instanceof org.eclipse.jdt.core.compiler.CategorizedProblem cp)
                            || cp.getCategoryID() != org.eclipse.jdt.core.compiler.CategorizedProblem.CAT_JAVADOC) {
                        continue;
                    }
                    Map<String, Object> finding = new LinkedHashMap<>();
                    finding.put("filePath", rel);
                    finding.put("line", problem.getSourceLineNumber() - 1);
                    finding.put("severity", problem.isError() ? "error" : "warning");
                    finding.put("message", problem.getMessage());
                    finding.put("problemId", problem.getID());
                    finding.put("category", "JAVADOC");
                    findings.add(finding);
                }
            }
        } catch (Exception e) {
            log.error("analyze_javadocs(validate) failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "analyze_javadocs");
        data.put("kind", "validate");
        data.put("findingCount", findings.size());
        data.put("findings", findings);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(findings.size())
            .returnedCount(findings.size())
            .suggestedNextTools(List.of(
                "analyze_javadocs(kind=generate) for a doclint-correct skeleton to fix a gap"))
            .build());
    }

    private ToolResponse ingest(IJdtService service, JsonNode arguments) {
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

            List<Map<String, Object>> facts = new ArrayList<>();
            for (Path path : targets) {
                if (facts.size() >= maxResults) {
                    break;
                }
                ICompilationUnit cu = service.getCompilationUnit(path);
                if (cu == null) {
                    continue;
                }
                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setSource(cu);
                parser.setKind(ASTParser.K_COMPILATION_UNIT);
                parser.setResolveBindings(true);
                parser.setBindingsRecovery(true);
                CompilationUnit ast = (CompilationUnit) parser.createAST(null);
                String rel = service.getPathUtils().formatPath(path);
                collectFacts(ast, rel, facts, maxResults);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "analyze_javadocs");
            data.put("kind", "ingest");
            data.put("factCount", facts.size());
            data.put("facts", facts);
            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(facts.size())
                .returnedCount(facts.size())
                .suggestedNextTools(List.of(
                    "analyze_type to inspect a documented type",
                    "find_references to see who depends on a documented contract"))
                .build());
        } catch (Exception e) {
            log.error("analyze_javadocs(ingest) failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private void collectFacts(CompilationUnit ast, String rel,
                              List<Map<String, Object>> out, int max) {
        ast.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (out.size() >= max || !(node instanceof BodyDeclaration decl)) {
                    return;
                }
                Javadoc jd = decl.getJavadoc();
                if (jd == null) {
                    return;
                }
                String fqn = fqnOf(decl);

                String mainText = "";
                List<String> paramTags = new ArrayList<>();
                String returnText = null;
                List<String> throwsTags = new ArrayList<>();
                List<String> seeTags = new ArrayList<>();
                String deprecatedText = null;

                for (Object o : jd.tags()) {
                    TagElement tag = (TagElement) o;
                    String name = tag.getTagName();
                    if (name == null) {
                        mainText = render(tag.fragments());
                    } else if (TagElement.TAG_PARAM.equals(name)) {
                        paramTags.add(render(tag.fragments()));
                    } else if (TagElement.TAG_RETURN.equals(name)) {
                        returnText = render(tag.fragments());
                    } else if (TagElement.TAG_THROWS.equals(name) || TagElement.TAG_EXCEPTION.equals(name)) {
                        throwsTags.add(render(tag.fragments()));
                    } else if (TagElement.TAG_SEE.equals(name)) {
                        seeTags.add(render(tag.fragments()));
                    } else if (TagElement.TAG_DEPRECATED.equals(name)) {
                        deprecatedText = render(tag.fragments());
                    }
                }

                boolean structured = !paramTags.isEmpty() || returnText != null || !throwsTags.isEmpty();
                if (!structured && mainText.isBlank() && deprecatedText == null) {
                    return; // empty/marker-only javadoc — nothing to anchor
                }

                List<Object> evidence = new ArrayList<>();
                for (String p : paramTags) {
                    evidence.add("@param " + p);
                }
                if (returnText != null) {
                    evidence.add("@return " + returnText);
                }
                for (String t : throwsTags) {
                    evidence.add("@throws " + t);
                }
                for (String s : seeTags) {
                    evidence.add("@see " + s);
                }

                String summary = firstSentence(mainText);
                if (summary.isBlank()) {
                    summary = "Documented " + kindLabel(decl) + (fqn == null ? "" : " " + fqn);
                }
                SymbolFact.Builder fact = SymbolFact
                    .of(structured ? "api_contract" : "domain_fact", summary,
                        structured ? Confidence.HIGH : Confidence.LOW)
                    .source("javadoc", rel)
                    .evidence(evidence.isEmpty() ? null : evidence);
                if (fqn != null) {
                    fact.symbol(fqn);
                }
                if (!mainText.isBlank()) {
                    fact.details(mainText);
                }
                out.add(fact.build().toMap());

                if (deprecatedText != null && out.size() < max) {
                    SymbolFact.Builder dep = SymbolFact
                        .of("deprecated_behavior",
                            "Deprecated: " + firstSentence(deprecatedText.isBlank()
                                ? "see Javadoc" : deprecatedText),
                            Confidence.HIGH)
                        .source("javadoc", rel);
                    if (fqn != null) {
                        dep.symbol(fqn);
                    }
                    if (!deprecatedText.isBlank()) {
                        dep.details(deprecatedText);
                    }
                    out.add(dep.build().toMap());
                }
            }
        });
    }

    /** Best-effort FQN from bindings; null when unresolved. */
    private static String fqnOf(BodyDeclaration decl) {
        if (decl instanceof AbstractTypeDeclaration t) {
            ITypeBinding b = t.resolveBinding();
            return b == null ? null : b.getQualifiedName();
        }
        if (decl instanceof MethodDeclaration m) {
            IMethodBinding b = m.resolveBinding();
            if (b == null || b.getDeclaringClass() == null) {
                return null;
            }
            return b.getDeclaringClass().getQualifiedName() + "#" + b.getName();
        }
        if (decl instanceof FieldDeclaration f && !f.fragments().isEmpty()) {
            VariableDeclarationFragment frag = (VariableDeclarationFragment) f.fragments().get(0);
            IVariableBinding b = frag.resolveBinding();
            if (b == null || b.getDeclaringClass() == null) {
                return null;
            }
            return b.getDeclaringClass().getQualifiedName() + "#" + b.getName();
        }
        if (decl instanceof EnumConstantDeclaration e) {
            IVariableBinding b = e.resolveVariable();
            if (b == null || b.getDeclaringClass() == null) {
                return null;
            }
            return b.getDeclaringClass().getQualifiedName() + "#" + b.getName();
        }
        if (decl instanceof AnnotationTypeMemberDeclaration a) {
            IMethodBinding b = a.resolveBinding();
            if (b == null || b.getDeclaringClass() == null) {
                return null;
            }
            return b.getDeclaringClass().getQualifiedName() + "#" + b.getName();
        }
        return null;
    }

    private static String kindLabel(BodyDeclaration decl) {
        if (decl instanceof AbstractTypeDeclaration) {
            return "type";
        }
        if (decl instanceof MethodDeclaration) {
            return "method";
        }
        if (decl instanceof FieldDeclaration) {
            return "field";
        }
        return "symbol";
    }

    /** Render a TagElement's fragments to plain text (TextElement, names, inline tags). */
    private static String render(List<?> fragments) {
        StringBuilder sb = new StringBuilder();
        for (Object f : fragments) {
            if (f instanceof TextElement te) {
                sb.append(te.getText());
            } else if (f instanceof SimpleName sn) {
                sb.append(sn.getIdentifier());
            } else if (f instanceof Name n) {
                sb.append(n.getFullyQualifiedName());
            } else if (f instanceof MemberRef mr) {
                sb.append(mr.getName().getIdentifier());
            } else if (f instanceof MethodRef mr) {
                sb.append(mr.getName().getIdentifier());
            } else if (f instanceof TagElement nested) {
                sb.append(render(nested.fragments()));
            }
            sb.append(' ');
        }
        return sb.toString().trim().replaceAll("\\s+", " ");
    }

    private static String firstSentence(String text) {
        if (text == null) {
            return "";
        }
        String t = text.trim();
        int dot = t.indexOf(". ");
        if (dot > 0) {
            return t.substring(0, dot + 1).trim();
        }
        if (t.endsWith(".")) {
            return t;
        }
        return t;
    }
}
