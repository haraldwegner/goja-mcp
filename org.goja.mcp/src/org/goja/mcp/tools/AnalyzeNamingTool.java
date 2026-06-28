package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
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
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Sprint 15a — naming-convention tool (parametric). Read-only ({@code analyze_}
 * prefix → readOnlyHint). Model-free.
 *
 * <p>Shallow-precise inference (Harald's call): per-category name-pattern
 * conformance + cheap structural signals (test source root, static-final =
 * constant), with confidence + a min-sample gate ({@code < MIN_SAMPLE} →
 * nothing/"unclear") + recorded exceptions. NO deep body/role analysis in
 * v1.11. Deterministic, sorted output.</p>
 *
 * <p>Kinds this stage: {@code infer} (project conventions), {@code get}
 * (conventions filtered by category). {@code suggest}/{@code check} follow.</p>
 */
public class AnalyzeNamingTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AnalyzeNamingTool.class);

    static final Set<String> KINDS = Set.of("infer", "get", "suggest", "check");

    private static final Set<String> CATEGORIES =
        Set.of("type", "method", "field", "constant", "package", "test");

    /** Below this many samples in a category, do not assert a convention. */
    private static final int MIN_SAMPLE = 3;

    private enum Style {
        UPPER_CAMEL("[A-Z][A-Za-z0-9]*", "UpperCamelCase"),
        LOWER_CAMEL("[a-z][A-Za-z0-9]*", "lowerCamelCase"),
        UPPER_SNAKE("[A-Z][A-Z0-9_]*", "UPPER_SNAKE_CASE");

        final String regex;
        final String label;

        Style(String regex, String label) {
            this.regex = regex;
            this.label = label;
        }
    }

    public AnalyzeNamingTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_naming";
    }

    @Override
    public String getDescription() {
        return """
            Naming-convention tool. Read-only; model-free; shallow-precise
            (name-pattern conformance + cheap structural signals, confidence-gated,
            with exceptions; no deep role analysis).

            USAGE: analyze_naming(kind="infer")
                   analyze_naming(kind="get", category="constant")

            KINDS:
            - infer   — infer per-category conventions (type/method/field/constant/package/test)
                        as naming_convention facts with confidence + examples + exceptions.
                        A category with too few samples yields no convention (unclear).
            - get     — return inferred conventions, optionally filtered by `category`.
            - suggest — apply the category's convention to a caller-supplied `intent`
                        (the words/meaning) → candidate name(s). MODEL-FREE: it only re-cases
                        your intent; with no `intent` it returns the convention SHAPE, never an
                        invented stem. Requires `category`.
            - check   — validate a proposed `name` against its category convention → conforms,
                        violations, and a re-cased suggestion. Requires `name` + `category`.

            Params: category (type/method/field/constant/package/test); intent (suggest);
            name (check); projectKey; maxResults. Requires load_project first.
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
        kind.put("description", "Which naming operation to run. See the tool description.");
        properties.put("kind", kind);
        Map<String, Object> category = new LinkedHashMap<>();
        category.put("type", "string");
        category.put("description", "Category (type/method/field/constant/package/test). "
            + "Filters get; required for suggest/check.");
        properties.put("category", category);
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("type", "string");
        intent.put("description", "For kind=suggest: the words/meaning of the symbol, e.g. "
            + "\"client for the billing HTTP API\" or \"max retry count\".");
        properties.put("intent", intent);
        Map<String, Object> name = new LinkedHashMap<>();
        name.put("type", "string");
        name.put("description", "For kind=check: the proposed name to validate.");
        properties.put("name", name);
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
        try {
            if ("suggest".equals(kind)) {
                return suggest(service, arguments);
            }
            if ("check".equals(kind)) {
                return check(service, arguments);
            }
            List<Convention> conventions = inferConventions(service);
            if ("get".equals(kind)) {
                String category = getStringParam(arguments, "category");
                if (category != null && !category.isBlank()) {
                    conventions = conventions.stream()
                        .filter(c -> c.category.equals(category))
                        .toList();
                }
            }
            List<Map<String, Object>> rendered = new ArrayList<>();
            for (Convention c : conventions) {
                rendered.add(c.toMap());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "analyze_naming");
            data.put("kind", kind);
            data.put("conventionCount", rendered.size());
            data.put("conventions", rendered);
            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(rendered.size())
                .returnedCount(rendered.size())
                .suggestedNextTools(List.of(
                    "analyze_naming(kind=check) to validate a proposed name"))
                .build());
        } catch (Exception e) {
            log.error("analyze_naming({}) failed: {}", kind, e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse suggest(IJdtService service, JsonNode arguments) throws Exception {
        String category = getStringParam(arguments, "category");
        if (category == null || !CATEGORIES.contains(category)) {
            return ToolResponse.invalidParameter("category", "required; one of " + CATEGORIES);
        }
        String intent = getStringParam(arguments, "intent");
        String suffix = "test".equals(category) ? inferTestSuffix(service) : null;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "analyze_naming");
        data.put("kind", "suggest");
        data.put("category", category);
        data.put("convention", styleLabelFor(category) + (suffix != null ? " + *" + suffix + " suffix" : ""));

        if (intent == null || intent.isBlank()) {
            // MODEL-FREE: no stem is invented — only the convention shape is returned.
            data.put("needsIntent", true);
            data.put("note", "Provide `intent` (the words/meaning of the symbol). This tool only "
                + "applies the convention's casing; it does not invent a name.");
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }

        List<String> tokens = tokenize(intent);
        String candidate = render(tokens, category) + (suffix != null ? suffix : "");
        data.put("needsIntent", false);
        data.put("candidates", List.of(candidate));
        // Exemplars from the inferred convention for this category, when present.
        for (Convention c : inferConventions(service)) {
            if (c.category.equals(category) && c.fact.get("evidence") != null) {
                data.put("exemplars", c.fact.get("evidence"));
                break;
            }
        }
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    private ToolResponse check(IJdtService service, JsonNode arguments) throws Exception {
        String name = getStringParam(arguments, "name");
        if (name == null || name.isBlank()) {
            return ToolResponse.invalidParameter("name", "the proposed name is required");
        }
        String category = getStringParam(arguments, "category");
        if (category == null || !CATEGORIES.contains(category)) {
            return ToolResponse.invalidParameter("category", "required; one of " + CATEGORIES);
        }

        List<String> violations = new ArrayList<>();
        boolean conforms;
        String suggestion = null;
        if ("package".equals(category)) {
            boolean ok = true;
            for (String seg : name.split("\\.")) {
                if (!seg.matches("[a-z][a-z0-9]*")) {
                    ok = false;
                    break;
                }
            }
            conforms = ok;
            if (!ok) {
                violations.add("expected all-lowercase package segments");
                suggestion = name.toLowerCase();
            }
        } else {
            Style style = categoryStyle(category);
            boolean caseOk = name.matches(style.regex);
            String suffix = "test".equals(category) ? inferTestSuffix(service) : null;
            boolean suffixOk = suffix == null || name.endsWith(suffix);
            conforms = caseOk && suffixOk;
            if (!caseOk) {
                violations.add("expected " + style.label);
            }
            if (!suffixOk) {
                violations.add("expected the *" + suffix + " suffix");
            }
            if (!conforms) {
                suggestion = render(tokenize(name), category) + (suffix != null ? suffix : "");
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "analyze_naming");
        data.put("kind", "check");
        data.put("name", name);
        data.put("category", category);
        data.put("conforms", conforms);
        data.put("convention", styleLabelFor(category));
        if (!violations.isEmpty()) {
            data.put("violations", violations);
        }
        if (suggestion != null && !suggestion.equals(name)) {
            data.put("suggestion", suggestion);
        }
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    private static Style categoryStyle(String category) {
        return switch (category) {
            case "type", "test" -> Style.UPPER_CAMEL;
            case "method", "field" -> Style.LOWER_CAMEL;
            case "constant" -> Style.UPPER_SNAKE;
            default -> Style.LOWER_CAMEL; // package handled separately by callers
        };
    }

    private static String styleLabelFor(String category) {
        if ("package".equals(category)) {
            return "all-lowercase";
        }
        return categoryStyle(category).label;
    }

    /** Split a name or free-text intent into lowercase word tokens. */
    private static List<String> tokenize(String text) {
        String spaced = text.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        List<String> tokens = new ArrayList<>();
        for (String part : spaced.split("[^A-Za-z0-9]+")) {
            if (!part.isBlank()) {
                tokens.add(part.toLowerCase());
            }
        }
        return tokens;
    }

    private static String render(List<String> tokens, String category) {
        if ("package".equals(category)) {
            return String.join(".", tokens);
        }
        Style style = categoryStyle(category);
        StringBuilder sb = new StringBuilder();
        switch (style) {
            case UPPER_SNAKE -> {
                for (int i = 0; i < tokens.size(); i++) {
                    if (i > 0) {
                        sb.append('_');
                    }
                    sb.append(tokens.get(i).toUpperCase());
                }
            }
            case UPPER_CAMEL -> tokens.forEach(t -> sb.append(capitalize(t)));
            case LOWER_CAMEL -> {
                for (int i = 0; i < tokens.size(); i++) {
                    sb.append(i == 0 ? tokens.get(i) : capitalize(tokens.get(i)));
                }
            }
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String inferTestSuffix(IJdtService service) throws Exception {
        for (Convention c : inferConventions(service)) {
            if ("test".equals(c.category)) {
                String summary = String.valueOf(c.fact.get("summary"));
                int star = summary.indexOf('*');
                int suf = summary.indexOf(" suffix");
                if (star >= 0 && suf > star) {
                    return summary.substring(star + 1, suf);
                }
            }
        }
        return "Test";
    }

    /** Internal: a convention + its category (for get-filtering). */
    private record Convention(String category, Map<String, Object> fact) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("category", category);
            m.putAll(fact);
            return m;
        }
    }

    private List<Convention> inferConventions(IJdtService service) throws Exception {
        Set<String> typeNames = new TreeSet<>();
        Set<String> methodNames = new TreeSet<>();
        Set<String> fieldNames = new TreeSet<>();
        Set<String> constantNames = new TreeSet<>();
        Set<String> packageNames = new TreeSet<>();
        Set<String> testTypeNames = new TreeSet<>();

        for (Path path : service.getAllJavaFiles()) {
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                continue;
            }
            boolean inTestRoot = path.toString().contains("/src/test/")
                || path.toString().contains("/test/java/");
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);
            if (ast.getPackage() != null) {
                packageNames.add(ast.getPackage().getName().getFullyQualifiedName());
            }
            ast.accept(new ASTVisitor() {
                @Override
                public void preVisit(ASTNode node) {
                    if (node instanceof AbstractTypeDeclaration t) {
                        String n = t.getName().getIdentifier();
                        typeNames.add(n);
                        if (inTestRoot) {
                            testTypeNames.add(n);
                        }
                    } else if (node instanceof MethodDeclaration m && !m.isConstructor()) {
                        methodNames.add(m.getName().getIdentifier());
                    } else if (node instanceof FieldDeclaration f) {
                        boolean constant = Modifier.isStatic(f.getModifiers())
                            && Modifier.isFinal(f.getModifiers());
                        for (Object frag : f.fragments()) {
                            String n = ((VariableDeclarationFragment) frag).getName().getIdentifier();
                            if (constant) {
                                constantNames.add(n);
                            } else {
                                fieldNames.add(n);
                            }
                        }
                    }
                }
            });
        }

        List<Convention> out = new ArrayList<>();
        addCasing(out, "type", typeNames, Style.UPPER_CAMEL);
        addCasing(out, "method", methodNames, Style.LOWER_CAMEL);
        addCasing(out, "field", fieldNames, Style.LOWER_CAMEL);
        addCasing(out, "constant", constantNames, Style.UPPER_SNAKE);
        addPackage(out, packageNames);
        addTestSuffix(out, testTypeNames);
        return out;
    }

    private void addCasing(List<Convention> out, String category, Set<String> names, Style style) {
        if (names.size() < MIN_SAMPLE) {
            return; // unclear — too few samples
        }
        List<String> conforming = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        for (String n : names) {
            if (n.matches(style.regex)) {
                conforming.add(n);
            } else {
                exceptions.add(n);
            }
        }
        double share = conforming.size() / (double) names.size();
        Confidence conf = confidenceFor(share);
        if (conf == null) {
            return; // too inconsistent to assert
        }
        SymbolFact fact = SymbolFact
            .of("naming_convention", category + " names use " + style.label, conf)
            .details(conforming.size() + "/" + names.size() + " " + category
                + " names match " + style.label + ".")
            .evidence(new ArrayList<>(conforming.subList(0, Math.min(5, conforming.size()))))
            .exceptions(exceptions.isEmpty() ? null
                : exceptions.subList(0, Math.min(10, exceptions.size())))
            .build();
        out.add(new Convention(category, fact.toMap()));
    }

    private void addPackage(List<Convention> out, Set<String> packageNames) {
        if (packageNames.size() < MIN_SAMPLE) {
            return; // single/few packages → unclear
        }
        List<String> conforming = new ArrayList<>();
        List<String> exceptions = new ArrayList<>();
        for (String p : packageNames) {
            boolean ok = true;
            for (String seg : p.split("\\.")) {
                if (!seg.matches("[a-z][a-z0-9]*")) {
                    ok = false;
                    break;
                }
            }
            (ok ? conforming : exceptions).add(p);
        }
        double share = conforming.size() / (double) packageNames.size();
        Confidence conf = confidenceFor(share);
        if (conf == null) {
            return;
        }
        SymbolFact fact = SymbolFact
            .of("naming_convention", "package names are all-lowercase", conf)
            .evidence(new ArrayList<>(conforming.subList(0, Math.min(5, conforming.size()))))
            .exceptions(exceptions.isEmpty() ? null : exceptions)
            .build();
        out.add(new Convention("package", fact.toMap()));
    }

    private void addTestSuffix(List<Convention> out, Set<String> testTypeNames) {
        if (testTypeNames.size() < MIN_SAMPLE) {
            return;
        }
        String[] suffixes = {"Test", "Tests", "IT", "TestCase"};
        String best = null;
        int bestCount = 0;
        for (String suf : suffixes) {
            int c = (int) testTypeNames.stream().filter(n -> n.endsWith(suf)).count();
            if (c > bestCount) {
                bestCount = c;
                best = suf;
            }
        }
        if (best == null) {
            return;
        }
        double share = bestCount / (double) testTypeNames.size();
        Confidence conf = confidenceFor(share);
        if (conf == null) {
            return;
        }
        final String suffix = best;
        List<String> conforming = testTypeNames.stream().filter(n -> n.endsWith(suffix)).toList();
        List<String> exceptions = testTypeNames.stream().filter(n -> !n.endsWith(suffix)).toList();
        SymbolFact fact = SymbolFact
            .of("naming_convention", "test classes use the *" + suffix + " suffix", conf)
            .evidence(new ArrayList<>(conforming.subList(0, Math.min(5, conforming.size()))))
            .exceptions(exceptions.isEmpty() ? null : new ArrayList<>(exceptions))
            .build();
        out.add(new Convention("test", fact.toMap()));
    }

    private static Confidence confidenceFor(double share) {
        if (share >= 0.9) {
            return Confidence.HIGH;
        }
        if (share >= 0.7) {
            return Confidence.MEDIUM;
        }
        return null; // too inconsistent — do not assert a convention
    }
}
