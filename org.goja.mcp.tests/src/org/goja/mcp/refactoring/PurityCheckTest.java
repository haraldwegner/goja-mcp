package org.goja.mcp.refactoring;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.goja.mcp.refactoring.PurityCheck.PurityFinding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 18 — the conservative AST-diff purity check behind the orchestration gate. */
class PurityCheckTest {

    private static MethodDeclaration method(String classSrc, String name) {
        ASTParser p = ASTParser.newParser(AST.getJLSLatest());
        p.setSource(classSrc.toCharArray());
        p.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit cu = (CompilationUnit) p.createAST(null);
        TypeDeclaration td = (TypeDeclaration) cu.types().get(0);
        for (MethodDeclaration md : td.getMethods()) {
            if (md.getName().getIdentifier().equals(name)) {
                return md;
            }
        }
        throw new IllegalArgumentException("no method " + name);
    }

    private static boolean has(List<PurityFinding> findings, String rule) {
        return findings.stream().anyMatch(f -> f.rule().equals(rule));
    }

    @Test
    @DisplayName("an identical body has no findings")
    void identical_isPure() {
        String src = "class C { int m() { return a(); } }";
        assertTrue(PurityCheck.check(method(src, "m"), method(src, "m")).isEmpty());
    }

    @Test
    @DisplayName("a rename (same body) is pure")
    void renameOnly_isPure() {
        MethodDeclaration before = method("class C { void go() { send(); this.x = 1; } }", "go");
        MethodDeclaration after = method("class C { void run() { send(); this.x = 1; } }", "run");
        assertTrue(PurityCheck.check(before, after).isEmpty(), "only the name changed");
    }

    @Test
    @DisplayName("a return null smuggled onto a path flags NEW_CONTROL_FLOW_OUTCOME (the REFACTORING_LESSONS_LEARNED case)")
    void addedReturnNull_flagsControlFlow() {
        MethodDeclaration before = method("class Slot { Order place() { return doPlace(); } }", "place");
        MethodDeclaration after = method("class Slot { Order place() { if (netting) return null; return doPlace(); } }", "place");
        List<PurityFinding> findings = PurityCheck.check(3, before, after);
        assertTrue(has(findings, PurityCheck.NEW_CONTROL_FLOW_OUTCOME), findings.toString());
        assertEquals(3, findings.get(0).stepIndex());
        // Same outbound calls (doPlace) → the call-set rule stays quiet.
        assertTrue(findings.stream().noneMatch(f -> f.rule().equals(PurityCheck.CHANGED_OUTBOUND_CALLS)), findings.toString());
    }

    @Test
    @DisplayName("a repointed call flags CHANGED_OUTBOUND_CALLS only")
    void repointedCall_flagsCalls() {
        MethodDeclaration before = method("class C { int m() { return a(); } }", "m");
        MethodDeclaration after = method("class C { int m() { return b(); } }", "m");
        List<PurityFinding> findings = PurityCheck.check(before, after);
        assertTrue(has(findings, PurityCheck.CHANGED_OUTBOUND_CALLS), findings.toString());
        assertTrue(findings.stream().noneMatch(f -> f.rule().equals(PurityCheck.NEW_CONTROL_FLOW_OUTCOME)), findings.toString());
    }

    @Test
    @DisplayName("a dropped field assignment flags RELOCATED_SIDE_EFFECT only")
    void changedSideEffect_flagsSideEffect() {
        MethodDeclaration before = method("class C { void m() { this.x = 1; } }", "m");
        MethodDeclaration after = method("class C { void m() { } }", "m");
        List<PurityFinding> findings = PurityCheck.check(before, after);
        assertTrue(has(findings, PurityCheck.RELOCATED_SIDE_EFFECT), findings.toString());
        assertTrue(findings.stream().noneMatch(f -> f.rule().equals(PurityCheck.NEW_CONTROL_FLOW_OUTCOME)), findings.toString());
    }
}
