package org.goja.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Detector;
import org.goja.mcp.domain.Finding;
import org.goja.mcp.domain.Findings;
import org.goja.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 17 (Fowler) — <b>Inappropriate Intimacy</b>. Two classes that each
 * reach into the other's fields are too entangled. Aggregates cross-type field
 * accesses project-wide; flags an unordered pair {A,B} when A accesses B's
 * fields AND B accesses A's fields, each at least {@code threshold} times
 * (default 2). Pointed refactoring: <b>Move Method/Field</b> (or Change
 * Bidirectional Association to Unidirectional).
 */
public final class InappropriateIntimacyDetector implements Detector {

    @Override
    public String kind() {
        return "inappropriate_intimacy";
    }

    @Override
    public String description() {
        return "Inappropriate Intimacy — two classes each accessing the other's fields "
            + "(>= `threshold` accesses each way, default 2); points to Move Method/Field.";
    }

    private record Loc(String filePath, int line) {
    }

    @Override
    public ToolResponse detect(IJdtService service, JsonNode arguments) {
        int threshold = AbstractAstDetector.readInt(arguments, "threshold", 2);
        // accessor type -> (target type -> count of field accesses)
        Map<String, Map<String, Integer>> access = new HashMap<>();
        Map<String, Loc> typeLoc = new HashMap<>();
        try {
            for (Path path : service.getAllJavaFiles()) {
                ICompilationUnit cu = service.getCompilationUnit(path);
                if (cu == null) {
                    continue;
                }
                CompilationUnit ast = AbstractAstDetector.parse(cu);
                if (ast == null) {
                    continue;
                }
                String formatted = service.getPathUtils().formatPath(path);
                scan(ast, formatted, access, typeLoc);
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }

        List<Finding> out = new ArrayList<>();
        List<String> types = new ArrayList<>(typeLoc.keySet());
        for (int i = 0; i < types.size(); i++) {
            for (int j = i + 1; j < types.size(); j++) {
                String a = types.get(i);
                String b = types.get(j);
                int aToB = count(access, a, b);
                int bToA = count(access, b, a);
                if (aToB >= threshold && bToA >= threshold) {
                    out.add(intimacyFinding(a, b, aToB, bToA, typeLoc.get(a)));
                    out.add(intimacyFinding(b, a, bToA, aToB, typeLoc.get(b)));
                }
            }
        }
        return Findings.toResponse(out);
    }

    private Finding intimacyFinding(String self, String other, int outward, int inward, Loc loc) {
        return new Finding(
            "inappropriate_intimacy", loc.filePath(), loc.line(), -1, "warning",
            "Class '" + simpleName(self) + "' and '" + simpleName(other) + "' access each other's "
                + "fields (" + outward + " out, " + inward + " in). Consider Move Method/Field.",
            simpleName(self));
    }

    private void scan(CompilationUnit ast, String filePath,
                      Map<String, Map<String, Integer>> access, Map<String, Loc> typeLoc) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (node.resolveBinding() != null) {
                    String fqn = node.resolveBinding().getErasure().getQualifiedName();
                    int line = ast.getLineNumber(node.getStartPosition());
                    typeLoc.putIfAbsent(fqn, new Loc(filePath, line));
                }
                return true;
            }

            @Override
            public boolean visit(FieldAccess fa) {
                record(fa, fa.resolveFieldBinding());
                return true;
            }

            @Override
            public boolean visit(QualifiedName qn) {
                if (qn.resolveBinding() instanceof IVariableBinding vb && vb.isField()) {
                    record(qn, vb);
                }
                return true;
            }

            private void record(ASTNode node, IVariableBinding field) {
                if (field == null || field.getDeclaringClass() == null) {
                    return;
                }
                String target = field.getDeclaringClass().getErasure().getQualifiedName();
                String enclosing = enclosingType(node);
                if (enclosing == null || target == null || enclosing.equals(target)) {
                    return;
                }
                if (target.startsWith("java.") || target.startsWith("javax.")) {
                    return;
                }
                access.computeIfAbsent(enclosing, k -> new HashMap<>()).merge(target, 1, Integer::sum);
            }
        });
    }

    private static String enclosingType(ASTNode node) {
        ASTNode n = node.getParent();
        while (n != null && !(n instanceof TypeDeclaration)) {
            n = n.getParent();
        }
        if (n instanceof TypeDeclaration td && td.resolveBinding() != null) {
            return td.resolveBinding().getErasure().getQualifiedName();
        }
        return null;
    }

    private static int count(Map<String, Map<String, Integer>> access, String from, String to) {
        return access.getOrDefault(from, Map.of()).getOrDefault(to, 0);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
