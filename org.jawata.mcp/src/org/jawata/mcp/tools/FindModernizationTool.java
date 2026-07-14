package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.tools.lombok.LombokDetector;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.SourceScan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 15 — modernization sweeps. Find-only (read-only) detector that scans
 * the workspace for code that could adopt a newer Java idiom, returning ranked
 * candidates with location + an "after" sketch. Parametric (`kind`) in the
 * style of {@code find_pattern_usages} / {@code find_quality_issue} so the six
 * sweeps cost ONE tool against the client tool-cap, not six.
 *
 * <p>Candidates are heuristic suggestions, never guarantees — the apply side is
 * the existing refactoring tools (e.g. {@code convert_anonymous_to_lambda}) or
 * a future orchestration step. Detection deliberately errs toward surfacing a
 * candidate the human/agent can judge, not toward silent certainty.</p>
 */
public class FindModernizationTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindModernizationTool.class);

    static final Set<String> KINDS = Set.of(
        "anon_to_lambda", "switch_to_pattern", "loop_to_stream",   // batch 1 (B3)
        "optional", "class_to_record", "sealed",                   // batch 2 (B4)
        "lombok_to_record", "delombok");                           // Lombok removal (B5b)

    public FindModernizationTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_modernization";
    }

    @Override
    public String getDescription() {
        return """
            Find code that could adopt a newer Java language idiom. Read-only;
            returns ranked candidates with { filePath, line, snippet, suggestion }.
            Apply the change with the matching refactoring tool — this only finds.

            USAGE: find_modernization(kind="<kind>")

            KINDS:
            - anon_to_lambda   — anonymous single-method implementations of a
                                 functional interface that can become a lambda
                                 (apply with convert_anonymous_to_lambda).
            - switch_to_pattern — classic `switch` statements that could become a
                                 switch expression / pattern-matching switch (Java 21).
            - loop_to_stream   — enhanced-for accumulation loops that could become a
                                 Stream map/filter/collect pipeline.
            - optional         — methods that return a reference type and `return null`,
                                 candidates for Optional<T> + map/orElse.
            - class_to_record  — plain immutable data classes (final fields + accessors +
                                 equals/hashCode/toString, no other behaviour) that fit a
                                 Java 16 record. The language-native answer to getter/setter
                                 boilerplate — prefer this over generating accessors.
            - sealed           — abstract base classes whose subclass set may be closed,
                                 candidates for a `sealed` + `permits` hierarchy (Java 17).
            - lombok_to_record — classes annotated @Data/@Value that are data carriers,
                                 candidates to drop Lombok for a native record (Java 16).
            - delombok         — any class using Lombok annotations; candidate to remove
                                 the Lombok dependency by materializing generated members.

            Optional: projectKey to scope to one loaded project; maxResults (default 200).
            Requires load_project first.
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
        kind.put("description", "Which modernization to scan for. See the tool description.");
        properties.put("kind", kind);
        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Cap on candidates returned (default 200).");
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
        int maxResults = getIntParam(arguments, "maxResults", 200);

        List<Map<String, Object>> candidates = new ArrayList<>();
        SourceScan scan = SourceScan.of(service.getAllJavaFiles());
        try {
            for (Path filePath : scan.files()) {
                if (candidates.size() >= maxResults) {
                    break;
                }
                ICompilationUnit cu = scan.resolve(service, filePath);
                if (cu == null) {
                    continue;   // RECORDED, not swallowed — see SourceScan
                }
                CompilationUnit ast = scan.parse(cu, filePath, true);
                if (ast == null) {
                    continue;
                }
                scan.examined();
                String rel = service.getPathUtils().formatPath(filePath);
                collect(kind, ast, rel, candidates, maxResults);
            }
        } catch (Exception e) {
            log.error("find_modernization({}) failed: {}", kind, e.getMessage(), e);
            return ToolResponse.internalError(e);
        }

        // "Nothing to modernize" is only sayable if we managed to read the code.
        Optional<ToolResponse> blind = scan.refuseIfBlind(kind + " candidates");
        if (blind.isPresent()) {
            return blind.get();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "find_modernization");
        data.put("kind", kind);
        data.put("candidateCount", candidates.size());
        data.put("candidates", candidates);
        data.putAll(scan.describe());
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering(scan.steering(candidates.size(), kind + " candidates"))
            .suggestedNextTools(List.of(
                "convert_anonymous_to_lambda / the matching refactoring tool to apply a candidate",
                "analyze_type to inspect a candidate's enclosing type"))
            .build());
    }

    private void collect(String kind, CompilationUnit ast, String rel,
                         List<Map<String, Object>> out, int max) {
        switch (kind) {
            case "anon_to_lambda" -> ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(ClassInstanceCreation node) {
                    if (out.size() >= max) {
                        return false;
                    }
                    AnonymousClassDeclaration anon = node.getAnonymousClassDeclaration();
                    if (anon == null) {
                        return true;
                    }
                    // Exactly one body member, and it's a method → SAM shape.
                    List<?> body = anon.bodyDeclarations();
                    if (body.size() != 1 || !(body.get(0) instanceof MethodDeclaration)) {
                        return true;
                    }
                    // Prefer a binding check: the created type must be an interface
                    // (functional). When bindings are unavailable, still surface it
                    // as a candidate (the single-method shape is the strong signal).
                    ITypeBinding tb = node.getType().resolveBinding();
                    boolean ifaceOrUnknown = (tb == null) || tb.isInterface();
                    if (!ifaceOrUnknown) {
                        return true;
                    }
                    add(out, rel, ast.getLineNumber(node.getStartPosition()),
                        "anonymous " + node.getType().toString(),
                        "replace with a lambda (convert_anonymous_to_lambda)");
                    return true;
                }
            });
            case "switch_to_pattern" -> ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(SwitchStatement node) {
                    if (out.size() >= max) {
                        return false;
                    }
                    add(out, rel, ast.getLineNumber(node.getStartPosition()),
                        "switch (" + node.getExpression().toString() + ")",
                        "consider a switch expression / pattern-matching switch (Java 21)");
                    return true;
                }
            });
            case "loop_to_stream" -> ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(EnhancedForStatement node) {
                    if (out.size() >= max) {
                        return false;
                    }
                    // Accumulation shape: body mentions a collection mutator
                    // (.add(/.put() — the common map/filter/collect tell.
                    String bodySrc = node.getBody().toString();
                    if (!bodySrc.contains(".add(") && !bodySrc.contains(".put(")) {
                        return true;
                    }
                    add(out, rel, ast.getLineNumber(node.getStartPosition()),
                        "for (" + node.getParameter().toString() + " : "
                            + node.getExpression().toString() + ")",
                        "consider a Stream map/filter/collect pipeline");
                    return true;
                }
            });
            case "optional" -> ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration node) {
                    if (out.size() >= max) {
                        return false;
                    }
                    Type rt = node.getReturnType2();
                    if (rt == null || rt.isPrimitiveType()) {
                        return true; // void / primitive → not an Optional candidate
                    }
                    String body = node.getBody() == null ? "" : node.getBody().toString();
                    if (!body.contains("return null")) {
                        return true;
                    }
                    add(out, rel, ast.getLineNumber(node.getStartPosition()),
                        node.getName().getIdentifier() + "(...) returns " + rt + " incl. null",
                        "consider Optional<" + rt + "> + map/orElse instead of returning null");
                    return true;
                }
            });
            case "class_to_record" -> ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(TypeDeclaration node) {
                    if (out.size() >= max) {
                        return false;
                    }
                    if (node.isInterface() || Modifier.isAbstract(node.getModifiers())
                            || node.getSuperclassType() != null) {
                        return true; // records can't extend; skip abstract/interfaces
                    }
                    FieldDeclaration[] fields = node.getFields();
                    if (fields.length == 0) {
                        return true;
                    }
                    // Data-only: every method is a constructor, a no-arg accessor, or
                    // equals/hashCode/toString. Any other method = real behaviour.
                    boolean dataOnly = true;
                    for (MethodDeclaration m : node.getMethods()) {
                        if (m.isConstructor()) {
                            continue;
                        }
                        String n = m.getName().getIdentifier();
                        int p = m.parameters().size();
                        boolean accessor = (n.startsWith("get") || n.startsWith("is")) && p == 0;
                        boolean objectMethod = (n.equals("equals") && p == 1)
                            || (n.equals("hashCode") && p == 0)
                            || (n.equals("toString") && p == 0);
                        if (!accessor && !objectMethod) {
                            dataOnly = false;
                            break;
                        }
                    }
                    if (!dataOnly) {
                        return true;
                    }
                    add(out, rel, ast.getLineNumber(node.getStartPosition()),
                        "class " + node.getName().getIdentifier(),
                        "consider a record (immutable accessors + equals/hashCode/toString for free)");
                    return true;
                }
            });
            case "sealed" -> ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(TypeDeclaration node) {
                    if (out.size() >= max) {
                        return false;
                    }
                    // Focus on abstract classes (interfaces are far noisier).
                    if (node.isInterface() || !Modifier.isAbstract(node.getModifiers())) {
                        return true;
                    }
                    add(out, rel, ast.getLineNumber(node.getStartPosition()),
                        "abstract class " + node.getName().getIdentifier(),
                        "consider 'sealed' + 'permits' if the subclass set is closed (Java 17)");
                    return true;
                }
            });
            case "lombok_to_record" -> ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(TypeDeclaration node) {
                    if (out.size() >= max) {
                        return false;
                    }
                    if (node.isInterface() || node.getSuperclassType() != null
                            || !LombokDetector.isDataCarrier(node)) {
                        return true; // records can't extend; need @Data/@Value
                    }
                    add(out, rel, ast.getLineNumber(node.getStartPosition()),
                        "@Data/@Value class " + node.getName().getIdentifier(),
                        "drop Lombok for a native record (accessors + equals/hashCode/toString for free)");
                    return true;
                }
            });
            case "delombok" -> ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(TypeDeclaration node) {
                    if (out.size() >= max) {
                        return false;
                    }
                    List<String> anns = LombokDetector.lombokAnnotations(node);
                    if (anns.isEmpty()) {
                        return true;
                    }
                    add(out, rel, ast.getLineNumber(node.getStartPosition()),
                        node.getName().getIdentifier() + " uses Lombok " + anns,
                        "delombok: materialize generated members and remove the Lombok dependency");
                    return true;
                }
            });
            default -> { /* validated earlier */ }
        }
    }

    private static void add(List<Map<String, Object>> out, String rel, int line,
                            String snippet, String suggestion) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("filePath", rel);
        c.put("line", line);
        c.put("snippet", snippet);
        c.put("suggestion", suggestion);
        out.add(c);
    }
}
