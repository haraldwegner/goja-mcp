package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 20 (SOLID) — <b>Liskov Substitution</b>. A subtype must be usable
 * wherever its supertype is. An {@code @Override} whose entire body is a single
 * {@code throw} <em>rejects</em> the inherited contract — the subtype is not
 * substitutable. Pointed refactoring: <b>Replace Inheritance with Delegation</b>
 * (or split the hierarchy so the operation isn't inherited).
 *
 * <p>Broader than the Fowler {@code refused_bequest} kind (which is specifically
 * {@code UnsupportedOperationException}): {@code lsp} flags a single-throw
 * rejection of <em>any</em> exception type (e.g. {@code IllegalStateException}).
 * Java already forbids an override from declaring broader checked {@code throws},
 * so precondition-strengthening shows up exactly as these rejections, not in the
 * signature. {@code refused_bequest} stays the narrow {@code fowler} view.</p>
 */
public final class LspDetector extends AbstractAstDetector {

    public LspDetector() {
        super("lsp",
            "Liskov Substitution — an @Override whose body just throws (rejecting the inherited "
                + "contract), breaking substitutability; points to Replace Inheritance with Delegation. "
                + "Broader than refused_bequest (any exception, not only UnsupportedOperationException).",
            0);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (isOverride(node) && thrown(node) != null) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "lsp", filePath, line, -1, "warning",
                        "Override '" + name + "' rejects its inherited contract (body just throws "
                            + thrown(node) + ") — not substitutable for its supertype. Consider Replace "
                            + "Inheritance with Delegation, or split the hierarchy.",
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
        }
        return false;
    }

    /** The thrown exception's type name if the body is a single throw, else null. */
    private static String thrown(MethodDeclaration node) {
        Block body = node.getBody();
        if (body == null || body.statements().size() != 1) {
            return null;
        }
        Statement s = (Statement) body.statements().get(0);
        if (!(s instanceof ThrowStatement ts)) {
            return null;
        }
        if (ts.getExpression() instanceof ClassInstanceCreation cic && cic.getType() != null) {
            return cic.getType().toString();
        }
        return "an exception";
    }
}
