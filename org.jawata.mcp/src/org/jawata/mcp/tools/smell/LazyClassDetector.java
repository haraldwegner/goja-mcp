package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 17 (Fowler) — <b>Lazy Class</b>. A concrete class that does too little
 * to justify itself: at most {@code threshold} methods (default 2) AND low
 * fan-in (referenced by at most one other type). Conservative — skips
 * interfaces, enums, abstract classes, and classes that extend a superclass or
 * implement an interface (likely strategy/impl types). Pointed refactoring:
 * <b>Inline Class</b> / Collapse Hierarchy.
 */
public final class LazyClassDetector extends AbstractAstDetector {

    public LazyClassDetector() {
        super("lazy_class",
            "Lazy Class — a concrete standalone class with <= `threshold` methods (default 2) and "
                + "fan-in <= 1; points to Inline Class. Conservative (skips interfaces/enums/abstract "
                + "and classes in a hierarchy).",
            2);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        analyze(ast, filePath, service, threshold, out, new ScanDegradation());
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out, ScanDegradation degraded) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (node.isInterface() || Modifier.isAbstract(node.getModifiers())) {
                    return true;
                }
                if (node.getSuperclassType() != null || !node.superInterfaceTypes().isEmpty()) {
                    return true; // part of a hierarchy — not a lazy leaf
                }
                if (node.getMethods().length > threshold) {
                    return true;
                }
                ITypeBinding binding = node.resolveBinding();
                if (binding == null) {
                    // The verdict on this type depends on a binding we did not get —
                    // that is a suppressed candidate, not a non-finding.
                    degraded.report("lazy_class candidate '" + node.getName() + "' (" + filePath
                        + ") skipped: its type binding did not resolve");
                    return true;
                }
                if (binding.isEnum()) {
                    return true;
                }
                // -1 = search failed → do not flag; the failure is in `degraded`.
                int fanIn = SmellSearch.referencingTypeCount(binding, service, degraded);
                if (fanIn >= 0 && fanIn <= 1) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "lazy_class", filePath, line, -1, "warning",
                        "Class '" + name + "' has <= " + threshold + " methods and low fan-in. "
                            + "Consider Inline Class.",
                        name));
                }
                return true;
            }
        });
    }
}
