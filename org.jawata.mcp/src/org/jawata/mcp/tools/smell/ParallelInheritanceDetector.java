package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Detector;
import org.jawata.mcp.domain.Finding;
import org.jawata.mcp.domain.Findings;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.SourceScan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        boolean includeTests = AbstractAstDetector.includeTests(arguments);
        // base simple name -> (variant token -> subclass location)
        Map<String, Map<String, Loc>> byBase = new HashMap<>();
        // base simple name -> (variant token -> subclass simple name)
        Map<String, Map<String, String>> subName = new HashMap<>();

        // The files we intend to read — the test-source filter is part of the LISTING, so a
        // deliberately-skipped test file never counts as one we failed to open.
        List<Path> inScope = new ArrayList<>();
        for (Path path : service.getAllJavaFiles()) {
            if (includeTests || !AbstractAstDetector.isTestSource(path, service)) {
                inScope.add(path);
            }
        }

        SourceScan scan = SourceScan.of(inScope);
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
                collect(ast, service.getPathUtils().formatPath(path), byBase, subName);
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }

        // A hierarchy we never read is a hierarchy that cannot pair with anything — a missed
        // file silently REMOVES parallels. "None" is not sayable without the whole set.
        Optional<ToolResponse> blind = scan.refuseIfBlind("parallel inheritance hierarchies");
        if (blind.isPresent()) {
            return blind.get();
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
        return Findings.toResponse(out, scan.describe(),
            scan.steering(out.size(), "parallel inheritance hierarchies"));
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
