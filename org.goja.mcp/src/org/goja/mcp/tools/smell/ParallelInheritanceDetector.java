package org.goja.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Detector;
import org.goja.mcp.domain.Finding;
import org.goja.mcp.domain.Findings;
import org.goja.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Sprint 17 (Fowler) — <b>Parallel Inheritance Hierarchies</b>. When every
 * subclass of one base forces a matching subclass of another, the two
 * hierarchies vary together. Detected conservatively via the
 * {@code <Variant><BaseName>} naming convention: if two bases each have
 * subclasses {@code <V>BaseA} / {@code <V>BaseB} sharing at least
 * {@code threshold} variant tokens (default 2), the hierarchies are parallel.
 * Low-recall by design (requires the naming convention) to avoid false
 * positives. Pointed refactoring: <b>Move Method/Field</b> to merge.
 */
public final class ParallelInheritanceDetector implements Detector {

    @Override
    public String kind() {
        return "parallel_inheritance";
    }

    @Override
    public String description() {
        return "Parallel Inheritance — two hierarchies whose subclasses correspond 1:1 by the "
            + "<Variant><BaseName> naming convention (>= `threshold` shared variants, default 2); "
            + "points to Move Method/Field to merge.";
    }

    private record Loc(String filePath, int line) {
    }

    @Override
    public ToolResponse detect(IJdtService service, JsonNode arguments) {
        int threshold = AbstractAstDetector.readInt(arguments, "threshold", 2);
        // base simple name -> (variant token -> subclass location)
        Map<String, Map<String, Loc>> byBase = new HashMap<>();
        // base simple name -> (variant token -> subclass simple name)
        Map<String, Map<String, String>> subName = new HashMap<>();
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
                collect(ast, service.getPathUtils().formatPath(path), byBase, subName);
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }

        List<Finding> out = new ArrayList<>();
        List<String> bases = new ArrayList<>(byBase.keySet());
        for (int i = 0; i < bases.size(); i++) {
            for (int j = i + 1; j < bases.size(); j++) {
                String b1 = bases.get(i);
                String b2 = bases.get(j);
                Set<String> common = new TreeSet<>(byBase.get(b1).keySet());
                common.retainAll(byBase.get(b2).keySet());
                if (common.size() >= threshold) {
                    for (String variant : common) {
                        out.add(parallelFinding(b1, b2, variant, byBase, subName));
                        out.add(parallelFinding(b2, b1, variant, byBase, subName));
                    }
                }
            }
        }
        return Findings.toResponse(out);
    }

    private Finding parallelFinding(String base, String otherBase, String variant,
                                    Map<String, Map<String, Loc>> byBase,
                                    Map<String, Map<String, String>> subName) {
        Loc loc = byBase.get(base).get(variant);
        String self = subName.get(base).get(variant);
        String other = subName.get(otherBase).get(variant);
        return new Finding(
            "parallel_inheritance", loc.filePath(), loc.line(), -1, "warning",
            "Class '" + self + "' parallels '" + other + "' (hierarchies " + base + " / " + otherBase
                + " vary together on variant '" + variant + "'). Consider Move Method/Field to merge.",
            self);
    }

    private void collect(CompilationUnit ast, String filePath,
                         Map<String, Map<String, Loc>> byBase,
                         Map<String, Map<String, String>> subName) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (node.isInterface() || node.getSuperclassType() == null) {
                    return true;
                }
                ITypeBinding binding = node.resolveBinding();
                if (binding == null || binding.getSuperclass() == null) {
                    return true;
                }
                String baseSimple = binding.getSuperclass().getName();
                if (baseSimple == null || baseSimple.equals("Object")) {
                    return true;
                }
                String sub = node.getName().getIdentifier();
                String variant = stripSuffix(sub, baseSimple);
                if (variant == null || variant.isEmpty()) {
                    return true;
                }
                int line = ast.getLineNumber(node.getStartPosition());
                byBase.computeIfAbsent(baseSimple, k -> new LinkedHashMap<>())
                    .putIfAbsent(variant, new Loc(filePath, line));
                subName.computeIfAbsent(baseSimple, k -> new LinkedHashMap<>())
                    .putIfAbsent(variant, sub);
                return true;
            }
        });
    }

    /** If {@code name} ends with {@code base} and is longer, return the leading variant; else null. */
    private static String stripSuffix(String name, String base) {
        if (name.length() > base.length() && name.endsWith(base)) {
            return name.substring(0, name.length() - base.length());
        }
        return null;
    }
}
