package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 17 (Fowler) — <b>Switch Statements</b> (on a type code). A {@code switch}
 * over an {@code int}/{@code String} type code with at least {@code threshold}
 * cases (default 3) is the classic candidate to replace with polymorphism.
 * Enum switches are not flagged (often legitimate). Pointed refactoring:
 * <b>Replace Conditional with Polymorphism</b>.
 */
public final class SwitchStatementsDetector extends AbstractAstDetector {

    public SwitchStatementsDetector() {
        super("switch_statements",
            "Switch on type code — a switch over an int/String with >= `threshold` cases (default 3); "
                + "points to Replace Conditional with Polymorphism. Enum switches are not flagged.",
            3);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SwitchStatement node) {
                if (!isTypeCode(node.getExpression())) {
                    return true;
                }
                int cases = 0;
                for (Object s : node.statements()) {
                    if (s instanceof SwitchCase sc && !sc.isDefault()) {
                        cases++;
                    }
                }
                if (cases >= threshold) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    out.add(new Finding(
                        "switch_statements", filePath, line, -1, "warning",
                        "Switch on a type code with " + cases + " cases (threshold " + threshold
                            + "). Consider Replace Conditional with Polymorphism.",
                        enclosingMethod(node)));
                }
                return true;
            }
        });
    }

    private static String enclosingMethod(ASTNode node) {
        ASTNode n = node.getParent();
        while (n != null && !(n instanceof MethodDeclaration)) {
            n = n.getParent();
        }
        return n instanceof MethodDeclaration md ? md.getName().getIdentifier() : null;
    }

    /** True if the switch selector is an int-family primitive or String (a "type code"). */
    private static boolean isTypeCode(Expression selector) {
        ITypeBinding t = selector.resolveTypeBinding();
        if (t == null) {
            return false;
        }
        if (t.isEnum()) {
            return false;
        }
        String name = t.getName();
        return switch (name) {
            case "int", "short", "byte", "char", "long" -> true;
            default -> "java.lang.String".equals(t.getErasure().getQualifiedName());
        };
    }
}
