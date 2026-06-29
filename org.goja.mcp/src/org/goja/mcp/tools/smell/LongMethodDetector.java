package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 17 (Fowler) — <b>Long Method</b>. Flags a method whose physical length
 * exceeds {@code threshold} LOC (default 40) OR whose cyclomatic complexity
 * exceeds {@value #CC_TRIGGER}. Pointed refactoring: <b>Extract Method</b>.
 */
public final class LongMethodDetector extends AbstractAstDetector {

    /**
     * Secondary trigger: a short but very branchy method is still a Long Method.
     * Raised 10 → 15 in v1.2.1 — the v1.2.0 dogfood showed CC&gt;10 flooded on real
     * code (modestly-sized methods at CC 11-14 are common and not actionable).
     */
    private static final int CC_TRIGGER = 15;

    public LongMethodDetector() {
        super("long_method",
            "Long Method — methods over the LOC threshold (default 60) or cyclomatic complexity "
                + CC_TRIGGER + "; points to Extract Method. `threshold` sets the LOC cutoff "
                + "(lower it, e.g. 40, or pass filePath, to scope tighter).",
            60);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getBody() == null) {
                    return true; // abstract / interface method — no body to measure
                }
                int loc = MethodMetrics.physicalLoc(ast, node);
                int cc = MethodMetrics.cyclomaticComplexity(node);
                if (loc > threshold || cc > CC_TRIGGER) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "long_method", filePath, line, -1, "warning",
                        "Method '" + name + "' is " + loc + " LOC, cyclomatic complexity " + cc
                            + " (LOC threshold " + threshold + ", CC trigger " + CC_TRIGGER
                            + "). Consider Extract Method.",
                        name));
                }
                return true;
            }
        });
    }
}
