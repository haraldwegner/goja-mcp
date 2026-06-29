package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 17 (Fowler) — <b>Temporary Field</b>. An instance field referenced by
 * exactly one method is set/used only for that one algorithm and clutters the
 * object the rest of the time. Pointed refactoring: <b>Extract Class</b>
 * (or Introduce Null Object / move it to a parameter). Threshold is unused.
 */
public final class TemporaryFieldDetector extends AbstractAstDetector {

    public TemporaryFieldDetector() {
        super("temporary_field",
            "Temporary Field — an instance field referenced by exactly one method; points to "
                + "Extract Class.",
            0);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                ITypeBinding typeBinding = node.resolveBinding();
                if (typeBinding == null) {
                    return true;
                }
                String typeFqn = typeBinding.getErasure().getQualifiedName();

                Map<String, Integer> fieldLine = new HashMap<>();
                for (FieldDeclaration fd : node.getFields()) {
                    if (Modifier.isStatic(fd.getModifiers())) {
                        continue;
                    }
                    for (Object f : fd.fragments()) {
                        VariableDeclarationFragment frag = (VariableDeclarationFragment) f;
                        fieldLine.put(frag.getName().getIdentifier(),
                            ast.getLineNumber(frag.getStartPosition()));
                    }
                }
                if (fieldLine.isEmpty()) {
                    return true;
                }

                Map<String, Set<String>> usedIn = new HashMap<>();
                for (MethodDeclaration m : node.getMethods()) {
                    if (m.getBody() == null) {
                        continue;
                    }
                    String methodName = m.getName().getIdentifier();
                    m.getBody().accept(new ASTVisitor() {
                        @Override
                        public boolean visit(SimpleName name) {
                            if (name.resolveBinding() instanceof IVariableBinding vb
                                && vb.isField()
                                && vb.getDeclaringClass() != null
                                && typeFqn.equals(vb.getDeclaringClass().getErasure().getQualifiedName())
                                && fieldLine.containsKey(vb.getName())) {
                                usedIn.computeIfAbsent(vb.getName(), k -> new HashSet<>()).add(methodName);
                            }
                            return true;
                        }
                    });
                }

                for (Map.Entry<String, Set<String>> e : usedIn.entrySet()) {
                    if (e.getValue().size() == 1) {
                        String field = e.getKey();
                        out.add(new Finding(
                            "temporary_field", filePath, fieldLine.get(field), -1, "warning",
                            "Field '" + field + "' is referenced by only one method ("
                                + e.getValue().iterator().next() + "). Consider Extract Class.",
                            field));
                    }
                }
                return true;
            }
        });
    }
}
