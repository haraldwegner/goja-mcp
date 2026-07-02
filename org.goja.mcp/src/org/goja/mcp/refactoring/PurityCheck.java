package org.goja.mcp.refactoring;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.ThrowStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sprint 18 — the acid-test half of the orchestration parity gate, mechanised as a
 * <b>conservative AST diff</b> of a method before and after a step labelled
 * <i>refactor</i>. Full behavioural equivalence is undecidable, so this flags the
 * three shapes a "refactor" most often uses to smuggle a behaviour change past a
 * green compile — the exact class of defect behind {@code REFACTORING_LESSONS_LEARNED}
 * (a {@code return null} added to a "move"):
 *
 * <ul>
 *   <li>{@link #NEW_CONTROL_FLOW_OUTCOME} — a new return/throw/branch on a path
 *       (control-flow outcomes increased).</li>
 *   <li>{@link #CHANGED_OUTBOUND_CALLS} — the multiset of invoked methods/constructors
 *       changed (a call added, removed, or repointed).</li>
 *   <li>{@link #RELOCATED_SIDE_EFFECT} — the set of statement-level side effects
 *       (field assignments, void call statements) changed.</li>
 * </ul>
 *
 * <p>A finding is <b>surfaced, not blocking</b>: the caller (Stage 5) either proves
 * the step pure or reclassifies it as a separate, separately-tested behaviour change.
 * The test/parity {@link ParityGate compile gate} remains the authoritative backstop;
 * this cheap diff is what makes a fallible author unable to <i>quietly</i> ship a
 * feature inside a refactor. Purely syntactic — no bindings, no service.</p>
 */
public final class PurityCheck {

    public static final String NEW_CONTROL_FLOW_OUTCOME = "NEW_CONTROL_FLOW_OUTCOME";
    public static final String CHANGED_OUTBOUND_CALLS = "CHANGED_OUTBOUND_CALLS";
    public static final String RELOCATED_SIDE_EFFECT = "RELOCATED_SIDE_EFFECT";

    /** One purity concern for the caller to resolve. */
    public record PurityFinding(int stepIndex, String rule, String detail) {}

    private PurityCheck() {}

    public static List<PurityFinding> check(MethodDeclaration before, MethodDeclaration after) {
        return check(0, before, after);
    }

    public static List<PurityFinding> check(int stepIndex, MethodDeclaration before, MethodDeclaration after) {
        Shape b = shape(before);
        Shape a = shape(after);
        List<PurityFinding> findings = new ArrayList<>();
        if (a.controlFlow > b.controlFlow) {
            findings.add(new PurityFinding(stepIndex, NEW_CONTROL_FLOW_OUTCOME,
                "control-flow outcomes " + b.controlFlow + " -> " + a.controlFlow));
        }
        if (!a.calls.equals(b.calls)) {
            findings.add(new PurityFinding(stepIndex, CHANGED_OUTBOUND_CALLS,
                "outbound calls " + delta(b.calls, a.calls)));
        }
        if (!a.sideEffects.equals(b.sideEffects)) {
            findings.add(new PurityFinding(stepIndex, RELOCATED_SIDE_EFFECT,
                "side effects " + delta(b.sideEffects, a.sideEffects)));
        }
        return findings;
    }

    private record Shape(int controlFlow, Map<String, Integer> calls, Map<String, Integer> sideEffects) {}

    private static Shape shape(MethodDeclaration method) {
        int[] cf = {0};
        Map<String, Integer> calls = new TreeMap<>();
        Map<String, Integer> sideEffects = new TreeMap<>();
        if (method == null || method.getBody() == null) {
            return new Shape(0, calls, sideEffects);
        }
        method.getBody().accept(new ASTVisitor() {
            @Override public boolean visit(ReturnStatement n) { cf[0]++; return true; }
            @Override public boolean visit(ThrowStatement n) { cf[0]++; return true; }
            @Override public boolean visit(BreakStatement n) { cf[0]++; return true; }
            @Override public boolean visit(ContinueStatement n) { cf[0]++; return true; }
            @Override public boolean visit(IfStatement n) { cf[0]++; return true; }
            @Override public boolean visit(ConditionalExpression n) { cf[0]++; return true; }
            @Override public boolean visit(SwitchCase n) { cf[0]++; return true; }

            @Override public boolean visit(MethodInvocation n) {
                bump(calls, n.getName().getIdentifier());
                return true;
            }
            @Override public boolean visit(SuperMethodInvocation n) {
                bump(calls, "super." + n.getName().getIdentifier());
                return true;
            }
            @Override public boolean visit(ClassInstanceCreation n) {
                bump(calls, "new " + n.getType().toString());
                return true;
            }
            @Override public boolean visit(ExpressionStatement n) {
                Expression e = n.getExpression();
                if (e instanceof MethodInvocation mi) {
                    bump(sideEffects, "call:" + mi.getName().getIdentifier());
                } else if (e instanceof SuperMethodInvocation smi) {
                    bump(sideEffects, "call:super." + smi.getName().getIdentifier());
                } else if (e instanceof Assignment asg) {
                    bump(sideEffects, "assign:" + asg.getLeftHandSide().toString().trim());
                }
                return true;
            }
        });
        return new Shape(cf[0], calls, sideEffects);
    }

    private static void bump(Map<String, Integer> multiset, String key) {
        multiset.merge(key, 1, Integer::sum);
    }

    /** A short "+added / -removed" description of how two multisets differ. */
    private static String delta(Map<String, Integer> before, Map<String, Integer> after) {
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            int was = before.getOrDefault(e.getKey(), 0);
            if (e.getValue() > was) {
                added.add(e.getKey() + (e.getValue() - was > 1 ? " x" + (e.getValue() - was) : ""));
            }
        }
        for (Map.Entry<String, Integer> e : before.entrySet()) {
            int now = after.getOrDefault(e.getKey(), 0);
            if (e.getValue() > now) {
                removed.add(e.getKey() + (e.getValue() - now > 1 ? " x" + (e.getValue() - now) : ""));
            }
        }
        return "changed (+" + added + " -" + removed + ")";
    }
}
