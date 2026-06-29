package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 17 (Fowler) — <b>Message Chains</b>. A navigation chain
 * {@code a.b().c().d()} longer than {@code threshold} (default 3) couples the
 * caller to the whole path. Only the outermost call of a chain is reported.
 * Pointed refactoring: <b>Hide Delegate</b>.
 */
public final class MessageChainsDetector extends AbstractAstDetector {

    public MessageChainsDetector() {
        super("message_chains",
            "Message Chains — method-call chains longer than `threshold` (default 3), e.g. "
                + "a.b().c().d(); points to Hide Delegate.",
            3);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                // Only handle the outermost invocation of a chain.
                if (node.getParent() instanceof MethodInvocation parent
                    && parent.getExpression() == node) {
                    return true;
                }
                int length = 0;
                Expression e = node;
                while (e instanceof MethodInvocation mi) {
                    length++;
                    e = mi.getExpression();
                }
                if (length > threshold) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    out.add(new Finding(
                        "message_chains", filePath, line, -1, "warning",
                        "Method-call chain of length " + length + " (threshold " + threshold
                            + "). Consider Hide Delegate.",
                        enclosingMethod(node)));
                }
                return true;
            }
        });
    }

    /** Name of the method enclosing this chain, or null if at field/initializer level. */
    private static String enclosingMethod(ASTNode node) {
        ASTNode n = node.getParent();
        while (n != null && !(n instanceof MethodDeclaration)) {
            n = n.getParent();
        }
        return n instanceof MethodDeclaration md ? md.getName().getIdentifier() : null;
    }
}
