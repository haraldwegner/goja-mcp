package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 20 (SOLID) — <b>Dependency Inversion</b>. A field declared as a
 * <em>concrete class</em> C (which implements an interface I) whose in-class
 * usages call <em>only</em> methods that I declares should depend on the
 * abstraction I, not the concretion C. Pointed refactoring: <b>retype the field
 * to I</b> (Introduce abstraction at the dependency edge).
 *
 * <p>Conservative, field-level (the doc's exit signal — "concrete-type fields
 * where interfaces exist"): flags only when a single interface fully covers the
 * field's actual usage, the field is used at least once, and no concrete-only
 * method is called. Object methods (toString/equals/…) are ignored.</p>
 */
public final class DipDetector extends AbstractAstDetector {

    public DipDetector() {
        super("dip",
            "Dependency Inversion — a field of a concrete type used only via methods of an "
                + "interface it implements; depend on the abstraction instead. Conservative "
                + "(single interface must fully cover the field's usage).",
            0);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                // 1. candidate fields: concrete-class type that implements ≥1 interface.
                Map<String, ITypeBinding> fieldType = new HashMap<>();
                Map<String, Integer> fieldLine = new HashMap<>();
                for (FieldDeclaration fd : node.getFields()) {
                    ITypeBinding t = fd.getType().resolveBinding();
                    if (t == null || t.isInterface() || t.isPrimitive() || t.isArray()
                        || t.isEnum() || t.isTypeVariable()) {
                        continue;
                    }
                    if (allInterfaces(t).isEmpty()) {
                        continue;
                    }
                    for (Object f : fd.fragments()) {
                        VariableDeclarationFragment frag = (VariableDeclarationFragment) f;
                        fieldType.put(frag.getName().getIdentifier(), t);
                        fieldLine.put(frag.getName().getIdentifier(),
                            ast.getLineNumber(frag.getStartPosition()));
                    }
                }
                if (fieldType.isEmpty()) {
                    return true;
                }

                // 2. collect, per candidate field, the method signatures called on it.
                Map<String, Set<String>> usage = new HashMap<>();
                node.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(MethodInvocation mi) {
                        String fieldName = fieldTarget(mi.getExpression(), fieldType.keySet());
                        if (fieldName == null) {
                            return true;
                        }
                        IMethodBinding mb = mi.resolveMethodBinding();
                        if (mb != null && !isObjectMethod(mb)) {
                            usage.computeIfAbsent(fieldName, k -> new HashSet<>()).add(sig(mb));
                        }
                        return true;
                    }
                });

                // 3. flag a field whose whole usage is covered by one interface.
                for (Map.Entry<String, Set<String>> e : usage.entrySet()) {
                    String fieldName = e.getKey();
                    Set<String> used = e.getValue();
                    if (used.isEmpty()) {
                        continue;
                    }
                    ITypeBinding concrete = fieldType.get(fieldName);
                    for (ITypeBinding iface : allInterfaces(concrete)) {
                        if (methodSigs(iface).containsAll(used)) {
                            out.add(new Finding(
                                "dip", filePath, fieldLine.get(fieldName), -1, "warning",
                                "Field '" + fieldName + "' (concrete " + concrete.getName() + ") is used "
                                    + "only via " + iface.getName() + " methods. Depend on the abstraction "
                                    + iface.getName() + ", not " + concrete.getName() + ".",
                                fieldName));
                            break;
                        }
                    }
                }
                return true;
            }
        });
    }

    /** Field name if {@code e} is a reference to one of {@code fields} (bare name or this.field). */
    private static String fieldTarget(Expression e, Set<String> fields) {
        if (e instanceof SimpleName sn && sn.resolveBinding() instanceof IVariableBinding vb
            && vb.isField() && fields.contains(vb.getName())) {
            return vb.getName();
        }
        if (e instanceof FieldAccess fa && fa.resolveFieldBinding() != null
            && fields.contains(fa.resolveFieldBinding().getName())) {
            return fa.resolveFieldBinding().getName();
        }
        return null;
    }

    /** All interfaces of {@code t}, transitively (implemented + their superinterfaces). */
    private static Set<ITypeBinding> allInterfaces(ITypeBinding t) {
        Set<ITypeBinding> out = new HashSet<>();
        collectInterfaces(t, out);
        return out;
    }

    private static void collectInterfaces(ITypeBinding t, Set<ITypeBinding> out) {
        if (t == null) {
            return;
        }
        for (ITypeBinding i : t.getInterfaces()) {
            if (out.add(i)) {
                collectInterfaces(i, out);
            }
        }
        collectInterfaces(t.getSuperclass(), out);
    }

    /** Method signatures (name#paramCount) declared on {@code iface} + its superinterfaces. */
    private static Set<String> methodSigs(ITypeBinding iface) {
        Set<String> sigs = new HashSet<>();
        collectSigs(iface, sigs);
        return sigs;
    }

    private static void collectSigs(ITypeBinding iface, Set<String> sigs) {
        if (iface == null) {
            return;
        }
        for (IMethodBinding m : iface.getDeclaredMethods()) {
            sigs.add(sig(m));
        }
        for (ITypeBinding sup : iface.getInterfaces()) {
            collectSigs(sup, sigs);
        }
    }

    private static String sig(IMethodBinding m) {
        return m.getName() + "#" + m.getParameterTypes().length;
    }

    private static boolean isObjectMethod(IMethodBinding m) {
        ITypeBinding decl = m.getDeclaringClass();
        return decl != null && "java.lang.Object".equals(decl.getErasure().getQualifiedName());
    }
}
