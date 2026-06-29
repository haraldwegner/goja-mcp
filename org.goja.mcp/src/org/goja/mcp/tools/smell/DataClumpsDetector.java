package org.goja.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Detector;
import org.goja.mcp.domain.Finding;
import org.goja.mcp.domain.Findings;
import org.goja.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Sprint 17 (Fowler) — <b>Data Clumps</b>. The same ordered group of
 * parameters (type + name), at least {@value #MIN_TUPLE} wide, recurring across
 * {@code threshold} or more methods/constructors (default 2). Because clumps
 * span classes, this detector aggregates project-wide rather than per-file (so
 * it implements {@link Detector} directly, reusing the AST helpers). Pointed
 * refactoring: <b>Extract Class</b> / Introduce Parameter Object.
 */
public final class DataClumpsDetector implements Detector {

    /** A clump must be at least this many parameters wide to count. */
    private static final int MIN_TUPLE = 3;

    @Override
    public String kind() {
        return "data_clumps";
    }

    @Override
    public String description() {
        return "Data Clumps — the same group of " + MIN_TUPLE + "+ parameters (type+name) recurring "
            + "across `threshold` methods (default 2); points to Extract Class / Parameter Object.";
    }

    private record Occurrence(String filePath, int line, String method) {
    }

    @Override
    public ToolResponse detect(IJdtService service, JsonNode arguments) {
        int threshold = AbstractAstDetector.readInt(arguments, "threshold", 2);
        String filePath = AbstractAstDetector.readString(arguments, "filePath");
        boolean includeTests = AbstractAstDetector.includeTests(arguments);
        Map<String, List<Occurrence>> byTuple = new LinkedHashMap<>();
        try {
            List<Path> files = (filePath != null && !filePath.isBlank())
                ? List.of(Path.of(filePath))
                : service.getAllJavaFiles();
            for (Path path : files) {
                if (!includeTests && AbstractAstDetector.isTestSource(path, service)) {
                    continue;
                }
                ICompilationUnit cu = service.getCompilationUnit(path);
                if (cu == null) {
                    continue;
                }
                CompilationUnit ast = AbstractAstDetector.parse(cu);
                if (ast == null) {
                    continue;
                }
                String formatted = service.getPathUtils().formatPath(path);
                collectTuples(ast, formatted, byTuple);
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }

        List<Finding> out = new ArrayList<>();
        for (Map.Entry<String, List<Occurrence>> entry : byTuple.entrySet()) {
            List<Occurrence> sites = entry.getValue();
            if (sites.size() >= threshold) {
                for (Occurrence site : sites) {
                    out.add(new Finding(
                        "data_clumps", site.filePath(), site.line(), -1, "warning",
                        "Parameter clump (" + entry.getKey() + ") recurs in " + sites.size()
                            + " methods (threshold " + threshold + "). Consider Extract Class / "
                            + "Introduce Parameter Object.",
                        site.method()));
                }
            }
        }
        return Findings.toResponse(out);
    }

    private void collectTuples(CompilationUnit ast, String filePath,
                               Map<String, List<Occurrence>> byTuple) {
        ast.accept(new ASTVisitor() {
            @Override
            @SuppressWarnings("unchecked")
            public boolean visit(MethodDeclaration node) {
                List<SingleVariableDeclaration> params =
                    (List<SingleVariableDeclaration>) node.parameters();
                if (params.size() >= MIN_TUPLE) {
                    String tuple = params.stream()
                        .map(p -> p.getType().toString() + " " + p.getName().getIdentifier())
                        .collect(Collectors.joining(", "));
                    int line = ast.getLineNumber(node.getStartPosition());
                    byTuple.computeIfAbsent(tuple, k -> new ArrayList<>())
                        .add(new Occurrence(filePath, line, node.getName().getIdentifier()));
                }
                return true;
            }
        });
    }
}
