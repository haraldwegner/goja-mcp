package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 17 (Fowler) — <b>Speculative Generality</b>. An abstraction (interface
 * or abstract class) with exactly one subtype in the project is premature
 * generality — the "just in case" hierarchy that never paid off. Pointed
 * refactoring: <b>Collapse Hierarchy</b> / Inline Class. (Zero-subtype
 * abstractions are left alone — they may be a published extension point.)
 */
public final class SpeculativeGeneralityDetector extends AbstractAstDetector {

    public SpeculativeGeneralityDetector() {
        super("speculative_generality",
            "Speculative Generality — an interface or abstract class with exactly one subtype; "
                + "points to Collapse Hierarchy / Inline Class.",
            0);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                boolean isAbstraction = node.isInterface()
                    || Modifier.isAbstract(node.getModifiers());
                if (!isAbstraction) {
                    return true;
                }
                ITypeBinding binding = node.resolveBinding();
                if (binding == null || !(binding.getJavaElement() instanceof IType type)) {
                    return true;
                }
                try {
                    IType[] subtypes = service.getSearchService().getAllSubtypes(type);
                    if (subtypes != null && subtypes.length == 1) {
                        int line = ast.getLineNumber(node.getStartPosition());
                        String name = node.getName().getIdentifier();
                        out.add(new Finding(
                            "speculative_generality", filePath, line, -1, "warning",
                            (node.isInterface() ? "Interface '" : "Abstract class '") + name
                                + "' has exactly one subtype (" + subtypes[0].getElementName()
                                + "). Consider Collapse Hierarchy / Inline Class.",
                            name));
                    }
                } catch (Exception e) {
                    // search failure — do not flag
                }
                return true;
            }
        });
    }
}
