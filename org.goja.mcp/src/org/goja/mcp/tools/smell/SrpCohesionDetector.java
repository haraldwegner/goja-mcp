package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 20 (SOLID) — <b>Single Responsibility</b> via low cohesion (LCOM4-style).
 * Build the method↔field usage graph for a class: methods are linked if they
 * share an instance field, or one calls the other. If the "real" methods split
 * into &ge;2 disjoint components, the class is doing several jobs → split along
 * the components. Complements the §7 unfinished-encapsulation SRP signal (the
 * tagged {@code incomplete_delegation}); this is the cohesion view.
 *
 * <p>Conservative to avoid the classic LCOM false positives: requires &ge;2
 * instance fields and &ge; {@code threshold} "real" methods (default 4), and
 * excludes constructors, trivial getters/setters, and {@code equals}/
 * {@code hashCode}/{@code toString} (which would make DTOs/value classes look
 * incohesive). Pointed refactoring: <b>Extract Class</b>.</p>
 */
public final class SrpCohesionDetector extends AbstractAstDetector {

    public SrpCohesionDetector() {
        super("srp_cohesion",
            "Single Responsibility (low cohesion / LCOM) — a class whose methods split into >= 2 "
                + "disjoint field-usage clusters; points to Extract Class. Requires >= 2 fields and "
                + ">= `threshold` real methods (default 4); ignores ctors, accessors, equals/hashCode/toString.",
            4);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (node.isInterface() || Modifier.isAbstract(node.getModifiers())) {
                    return true;
                }
                Set<String> fields = instanceFields(node);
                if (fields.size() < 2) {
                    return true;
                }
                // candidate methods: concrete, non-ctor, non-accessor, non-Object-override.
                List<MethodDeclaration> candidates = new ArrayList<>();
                for (MethodDeclaration m : node.getMethods()) {
                    if (m.isConstructor() || m.getBody() == null) {
                        continue;
                    }
                    if (isAccessor(m, fields) || isObjectOverride(m)) {
                        continue;
                    }
                    candidates.add(m);
                }
                if (candidates.size() < threshold) {
                    return true;
                }

                // per method: which instance fields it uses, which sibling methods it calls.
                Map<String, Set<String>> fieldsUsed = new LinkedHashMap<>();
                Map<String, Set<String>> calls = new LinkedHashMap<>();
                Set<String> methodKeys = new HashSet<>();
                for (MethodDeclaration m : candidates) {
                    String key = key(m);
                    methodKeys.add(key);
                    fieldsUsed.put(key, new HashSet<>());
                    calls.put(key, new HashSet<>());
                    m.getBody().accept(new ASTVisitor() {
                        @Override
                        public boolean visit(SimpleName name) {
                            if (name.resolveBinding() instanceof IVariableBinding vb
                                && vb.isField() && fields.contains(vb.getName())) {
                                fieldsUsed.get(key).add(vb.getName());
                            }
                            return true;
                        }

                        @Override
                        public boolean visit(MethodInvocation mi) {
                            IMethodBinding mb = mi.resolveMethodBinding();
                            if (mb != null) {
                                calls.get(key).add(mb.getName() + "#" + mb.getParameterTypes().length);
                            }
                            return true;
                        }
                    });
                }

                // union-find: link methods that share a field, or call one another.
                Map<String, String> parent = new HashMap<>();
                for (String k : methodKeys) {
                    parent.put(k, k);
                }
                List<String> keys = new ArrayList<>(methodKeys);
                for (int i = 0; i < keys.size(); i++) {
                    for (int j = i + 1; j < keys.size(); j++) {
                        if (shareField(fieldsUsed.get(keys.get(i)), fieldsUsed.get(keys.get(j)))) {
                            union(parent, keys.get(i), keys.get(j));
                        }
                    }
                }
                for (String caller : keys) {
                    for (String callee : keys) {
                        if (!caller.equals(callee) && calls.get(caller).contains(callee)) {
                            union(parent, caller, callee);
                        }
                    }
                }

                // count components that contain at least one field-using method.
                Map<String, Boolean> rootTouchesField = new LinkedHashMap<>();
                for (String k : keys) {
                    String root = find(parent, k);
                    boolean touches = !fieldsUsed.get(k).isEmpty();
                    rootTouchesField.merge(root, touches, (a, b) -> a || b);
                }
                long components = rootTouchesField.values().stream().filter(Boolean::booleanValue).count();
                if (components >= 2) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "srp_cohesion", filePath, line, -1, "warning",
                        "Class '" + name + "' has " + components + " disjoint field-usage clusters "
                            + "(low cohesion / multiple responsibilities). Consider Extract Class.",
                        name));
                }
                return true;
            }
        });
    }

    private static Set<String> instanceFields(TypeDeclaration node) {
        Set<String> fields = new HashSet<>();
        for (FieldDeclaration fd : node.getFields()) {
            if (Modifier.isStatic(fd.getModifiers())) {
                continue;
            }
            for (Object f : fd.fragments()) {
                fields.add(((VariableDeclarationFragment) f).getName().getIdentifier());
            }
        }
        return fields;
    }

    /** Method key tolerant of overloads. */
    private static String key(MethodDeclaration m) {
        return m.getName().getIdentifier() + "#" + m.parameters().size();
    }

    private static boolean isObjectOverride(MethodDeclaration m) {
        String n = m.getName().getIdentifier();
        int p = m.parameters().size();
        return ("equals".equals(n) && p == 1) || ("hashCode".equals(n) && p == 0)
            || ("toString".equals(n) && p == 0);
    }

    /**
     * A getter ({@code return field;}), a void setter ({@code field = ...;}), or a
     * <b>fluent builder setter</b> ({@code field = ...; return ...;}). Excluding the
     * fluent form keeps builders out of the LCOM graph — each {@code withX} touches one
     * field, so they look disjoint, but a builder is one responsibility (v1.3.1 dogfood:
     * {@code SymbolFact.Builder} was a false positive).
     */
    private static boolean isAccessor(MethodDeclaration m, Set<String> fields) {
        Block body = m.getBody();
        if (body == null) {
            return false;
        }
        List<?> stmts = body.statements();
        if (stmts.size() == 1) {
            Statement s = (Statement) stmts.get(0);
            if (s instanceof ReturnStatement rs) {
                return isFieldRef(rs.getExpression(), fields);
            }
            if (s instanceof ExpressionStatement es && es.getExpression() instanceof Assignment a) {
                return isFieldRef(a.getLeftHandSide(), fields);
            }
            return false;
        }
        if (stmts.size() == 2) {
            // fluent BUILDER setter: field = ...; return this;  (must return `this` — a
            // method that returns the field/a computation, e.g. `a = a*2; return a;`, is real work).
            return stmts.get(0) instanceof ExpressionStatement es
                && es.getExpression() instanceof Assignment a
                && isFieldRef(a.getLeftHandSide(), fields)
                && stmts.get(1) instanceof ReturnStatement rs
                && rs.getExpression() instanceof ThisExpression;
        }
        return false;
    }

    private static boolean isFieldRef(Expression e, Set<String> fields) {
        if (e instanceof SimpleName sn) {
            return fields.contains(sn.getIdentifier());
        }
        if (e instanceof FieldAccess fa) {
            return fields.contains(fa.getName().getIdentifier());
        }
        return false;
    }

    private static boolean shareField(Set<String> a, Set<String> b) {
        for (String f : a) {
            if (b.contains(f)) {
                return true;
            }
        }
        return false;
    }

    private static String find(Map<String, String> parent, String x) {
        while (!parent.get(x).equals(x)) {
            parent.put(x, parent.get(parent.get(x)));
            x = parent.get(x);
        }
        return x;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        parent.put(find(parent, a), find(parent, b));
    }
}
