package org.jawata.mcp.learn;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.List;
import java.util.Map;

/**
 * Compiler-grounded features of an edit (Sprint 26, the binding modeling
 * constraint): every feature is a STRUCTURAL fact of the before/after ASTs —
 * never a token, never an identifier, never a literal value. The pin: two
 * edits differing only in names/literals produce IDENTICAL vectors.
 */
public final class FeatureVector {

    /** Feature names, fixed order — the model weights index into this. */
    public static final List<String> NAMES = List.of(
        "methodCountDelta",      // methods added/removed (structural)
        "typeCountDelta",        // types added/removed (structural)
        "signatureChanged",      // any method signature shape changed
        "controlFlowDelta",      // return/throw count change (behavior shape)
        "invocationCountDelta",  // outbound call count change
        "statementOnlyChange",   // AST changed but declarations did not
        "identicalStructure"     // no structural difference at all
    );

    private FeatureVector() {
    }

    /**
     * Extract the feature vector for an edit given the BEFORE and AFTER text
     * of the touched declarations (class-body fragments or whole files).
     */
    public static double[] extract(String before, String after) {
        Shape b = shape(before);
        Shape a = shape(after);
        double methodDelta = a.methods - b.methods;
        double typeDelta = a.types - b.types;
        double signatureChanged = b.signatureShapes.equals(a.signatureShapes) ? 0 : 1;
        double controlFlowDelta = a.controlFlow - b.controlFlow;
        double invocationDelta = a.invocations - b.invocations;
        boolean structurallyIdentical = methodDelta == 0 && typeDelta == 0
            && signatureChanged == 0 && controlFlowDelta == 0 && invocationDelta == 0;
        double statementOnly = structurallyIdentical && b.statements != a.statements ? 1 : 0;
        return new double[] {
            methodDelta, typeDelta, signatureChanged, controlFlowDelta,
            invocationDelta, statementOnly,
            structurallyIdentical && statementOnly == 0 ? 1 : 0
        };
    }

    /**
     * The Sprint-22 hand rule the edit switch competes against: an edit is
     * STRUCTURAL unless it is statement-only or structurally identical (a
     * comment/literal/javadoc touch). This is the rule verdict served beside
     * the model and the baseline of the rolling record.
     */
    public static boolean ruleSaysStructural(double[] x) {
        int statementOnly = NAMES.indexOf("statementOnlyChange");
        int identical = NAMES.indexOf("identicalStructure");
        return x[statementOnly] == 0 && x[identical] == 0;
    }

    /** Structural shape of a fragment — counts only, no names, no tokens. */
    private record Shape(int methods, int types, int controlFlow, int invocations,
                         int statements, List<String> signatureShapes) {
    }

    private static Shape shape(String source) {
        ASTNode node = parse(source);
        int[] counts = new int[5];
        java.util.ArrayList<String> signatures = new java.util.ArrayList<>();
        if (node != null) {
            node.accept(new ASTVisitor() {
                @Override public boolean visit(MethodDeclaration n) {
                    counts[0]++;
                    // Signature SHAPE only: arity + modifier count + return
                    // presence — no names, no types-as-text.
                    signatures.add(n.parameters().size() + ":" + n.modifiers().size()
                        + ":" + (n.getReturnType2() == null ? 0 : 1));
                    return true;
                }

                @Override public boolean visit(TypeDeclaration n) {
                    counts[1]++;
                    return true;
                }

                @Override public boolean visit(ReturnStatement n) {
                    counts[2]++;
                    return true;
                }

                @Override public boolean visit(ThrowStatement n) {
                    counts[2]++;
                    return true;
                }

                @Override public boolean visit(MethodInvocation n) {
                    counts[3]++;
                    return true;
                }

                @Override public boolean preVisit2(ASTNode n) {
                    if (n instanceof org.eclipse.jdt.core.dom.Statement) {
                        counts[4]++;
                    }
                    return true;
                }

                @Override public boolean visit(SimpleName n) {
                    // Names are deliberately NOT features.
                    return false;
                }
            });
        }
        java.util.Collections.sort(signatures);
        return new Shape(counts[0], counts[1], counts[2], counts[3], counts[4],
            List.copyOf(signatures));
    }

    private static ASTNode parse(String source) {
        for (int kind : new int[] {ASTParser.K_COMPILATION_UNIT,
                ASTParser.K_CLASS_BODY_DECLARATIONS, ASTParser.K_STATEMENTS}) {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setKind(kind);
            parser.setSource(source.toCharArray());
            parser.setCompilerOptions(Map.of(
                org.eclipse.jdt.core.JavaCore.COMPILER_SOURCE, "21",
                org.eclipse.jdt.core.JavaCore.COMPILER_COMPLIANCE, "21",
                org.eclipse.jdt.core.JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, "21"));
            ASTNode node = parser.createAST(null);
            if (node != null && node.subtreeBytes() > 0 && hasContent(node)) {
                return node;
            }
        }
        return null;
    }

    private static boolean hasContent(ASTNode node) {
        int[] children = {0};
        node.accept(new ASTVisitor() {
            @Override public boolean preVisit2(ASTNode n) {
                children[0]++;
                return children[0] < 3;
            }
        });
        return children[0] > 1;
    }
}
