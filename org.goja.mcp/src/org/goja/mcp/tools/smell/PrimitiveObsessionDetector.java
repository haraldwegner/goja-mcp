package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.List;
import java.util.Set;

/**
 * Sprint 17 (Fowler) — <b>Primitive Obsession</b>. A method/constructor whose
 * parameter list is dominated by bare primitives and {@code String} (at least
 * {@code threshold}, default 3, and a majority) is usually passing around data
 * that wants its own type. Pointed refactoring: <b>Replace Type Code with
 * Class</b> / Introduce Parameter Object.
 */
public final class PrimitiveObsessionDetector extends AbstractAstDetector {

    private static final Set<String> PRIMITIVE_LIKE = Set.of(
        "java.lang.String", "java.lang.Integer", "java.lang.Long", "java.lang.Short",
        "java.lang.Byte", "java.lang.Boolean", "java.lang.Character", "java.lang.Double",
        "java.lang.Float");

    public PrimitiveObsessionDetector() {
        super("primitive_obsession",
            "Primitive Obsession — methods/constructors whose parameters are mostly bare primitives "
                + "or String (>= `threshold`, default 3, and a majority); points to Replace Type Code "
                + "with Class.",
            3);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                List<SingleVariableDeclaration> params =
                    (List<SingleVariableDeclaration>) node.parameters();
                int total = params.size();
                if (total < threshold) {
                    return true;
                }
                int primitive = 0;
                for (SingleVariableDeclaration p : params) {
                    if (isPrimitiveLike(p)) {
                        primitive++;
                    }
                }
                if (primitive >= threshold && primitive * 2 > total) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "primitive_obsession", filePath, line, -1, "warning",
                        (node.isConstructor() ? "Constructor '" : "Method '") + name + "' takes "
                            + primitive + " primitive/String parameters of " + total
                            + ". Consider Replace Type Code with Class / Parameter Object.",
                        name));
                }
                return true;
            }
        });
    }

    private static boolean isPrimitiveLike(SingleVariableDeclaration p) {
        ITypeBinding b = p.getType().resolveBinding();
        if (b == null) {
            return false;
        }
        if (b.isPrimitive()) {
            return true;
        }
        return PRIMITIVE_LIKE.contains(b.getErasure().getQualifiedName());
    }
}
