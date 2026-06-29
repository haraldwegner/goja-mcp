package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 17 (Fowler) — <b>Middle Man</b>. A class whose methods are mostly
 * one-line delegations to a field (delegation ratio &ge; {@code threshold}
 * percent, default 50, over a class with at least 3 concrete methods) does too
 * little of its own. Pointed refactoring: <b>Remove Middle Man</b>.
 */
public final class MiddleManDetector extends AbstractAstDetector {

    private static final int MIN_METHODS = 3;

    public MiddleManDetector() {
        super("middle_man",
            "Middle Man — a class whose methods are mostly (>= `threshold`%, default 50) one-line "
                + "delegations to a field; points to Remove Middle Man.",
            50);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int thresholdPercent, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (node.isInterface()) {
                    return true;
                }
                int total = 0;
                int delegating = 0;
                for (MethodDeclaration m : node.getMethods()) {
                    if (m.isConstructor() || m.getBody() == null) {
                        continue;
                    }
                    total++;
                    if (isFieldDelegation(m)) {
                        delegating++;
                    }
                }
                if (total >= MIN_METHODS && delegating * 100 >= thresholdPercent * total) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "middle_man", filePath, line, -1, "warning",
                        "Class '" + name + "' delegates " + delegating + "/" + total + " methods to a "
                            + "field (>= " + thresholdPercent + "%). Consider Remove Middle Man.",
                        name));
                }
                return true;
            }
        });
    }

    /** True if the method body is a single statement that delegates to a field's method. */
    private static boolean isFieldDelegation(MethodDeclaration m) {
        Block body = m.getBody();
        List<?> statements = body.statements();
        if (statements.size() != 1) {
            return false;
        }
        Statement s = (Statement) statements.get(0);
        Expression call = null;
        if (s instanceof ReturnStatement rs && rs.getExpression() instanceof MethodInvocation mi) {
            call = mi;
        } else if (s instanceof ExpressionStatement es && es.getExpression() instanceof MethodInvocation mi) {
            call = mi;
        }
        if (!(call instanceof MethodInvocation invocation)) {
            return false;
        }
        Expression target = invocation.getExpression();
        if (target == null) {
            return false; // call on `this` — not delegation to a field
        }
        if (target instanceof SimpleName sn) {
            return sn.resolveBinding() instanceof IVariableBinding vb && vb.isField();
        }
        if (target instanceof FieldAccess fa) {
            return fa.resolveFieldBinding() != null;
        }
        return false;
    }
}
