package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 17 (Fowler) — <b>Refused Bequest</b>. A subclass that overrides an
 * inherited method only to reject it — body is a single
 * {@code throw new UnsupportedOperationException(...)} — is refusing its
 * inheritance. Pointed refactoring: <b>Replace Inheritance with Delegation</b>.
 * (Threshold is unused for this kind.)
 */
public final class RefusedBequestDetector extends AbstractAstDetector {

    public RefusedBequestDetector() {
        super("refused_bequest",
            "Refused Bequest — an @Override whose body just throws UnsupportedOperationException; "
                + "points to Replace Inheritance with Delegation.",
            0);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (isOverride(node) && bodyJustThrowsUnsupported(node)) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "refused_bequest", filePath, line, -1, "warning",
                        "Override '" + name + "' rejects its inheritance (throws "
                            + "UnsupportedOperationException). Consider Replace Inheritance with Delegation.",
                        name));
                }
                return true;
            }
        });
    }

    private static boolean isOverride(MethodDeclaration node) {
        for (Object mod : node.modifiers()) {
            if (mod instanceof Annotation a
                && "Override".equals(a.getTypeName().getFullyQualifiedName())) {
                return true;
            }
            if (mod instanceof IExtendedModifier) {
                // non-annotation modifier; ignore
            }
        }
        return false;
    }

    private static boolean bodyJustThrowsUnsupported(MethodDeclaration node) {
        Block body = node.getBody();
        if (body == null || body.statements().size() != 1) {
            return false;
        }
        Statement s = (Statement) body.statements().get(0);
        if (!(s instanceof ThrowStatement ts)) {
            return false;
        }
        if (ts.getExpression() instanceof ClassInstanceCreation cic && cic.getType() != null) {
            String thrown = cic.getType().toString();
            return thrown.endsWith("UnsupportedOperationException");
        }
        return false;
    }
}
