package org.goja.mcp.tools;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 19 (Kerievsky) — shared helpers for the switch-driven pattern transforms
 * (refactor_to_state / refactor_to_command_dispatcher / …): parse a classic
 * {@code switch} into cases, lift a case body to source text (validating it is
 * self-contained), and small AST/naming utilities. Keeps the per-transform tools
 * focused on their own recipe.
 */
final class PatternSupport {

    private PatternSupport() {
    }

    /** One parsed case; {@code label} is null for {@code default}. */
    record ParsedCase(Expression label, boolean isDefault, List<Statement> body) {
    }

    /**
     * Split a traditional switch into cases. Returns null if any case uses a
     * multi-label form ({@code case A: case B:}), which the v1.4 transforms refuse.
     */
    static List<ParsedCase> parseCases(SwitchStatement sw) {
        List<ParsedCase> out = new ArrayList<>();
        Expression pendingLabel = null;
        boolean pendingDefault = false;
        List<Statement> body = new ArrayList<>();
        boolean started = false;
        for (Object o : sw.statements()) {
            if (o instanceof SwitchCase sc) {
                if (started) {
                    out.add(new ParsedCase(pendingLabel, pendingDefault, body));
                }
                started = true;
                body = new ArrayList<>();
                if (sc.isDefault()) {
                    pendingLabel = null;
                    pendingDefault = true;
                } else {
                    List<?> exprs = sc.expressions();
                    if (exprs.size() != 1 || !(exprs.get(0) instanceof Expression e)) {
                        return null;
                    }
                    pendingLabel = e;
                    pendingDefault = false;
                }
            } else if (o instanceof Statement s) {
                body.add(s);
            }
        }
        if (started) {
            out.add(new ParsedCase(pendingLabel, pendingDefault, body));
        }
        return out;
    }

    /**
     * The case body as source text with any trailing {@code break} stripped, or
     * null if the body falls through (no terminating break/return), uses
     * {@code this}, or assigns {@code rejectFieldAssign} (when non-null).
     */
    static String caseBodyText(List<Statement> body, String source, IVariableBinding rejectFieldAssign) {
        List<Statement> stmts = new ArrayList<>(body);
        if (stmts.isEmpty()) {
            return null;
        }
        Statement last = stmts.get(stmts.size() - 1);
        boolean endsWithBreak = last instanceof BreakStatement;
        if (!endsWithBreak && !(last instanceof ReturnStatement)) {
            return null;
        }
        if (endsWithBreak) {
            stmts.remove(stmts.size() - 1);
            if (stmts.isEmpty()) {
                return "// (empty)";
            }
        }
        boolean[] unsafe = {false};
        for (Statement s : stmts) {
            s.accept(new ASTVisitor() {
                @Override
                public boolean visit(ThisExpression node) {
                    unsafe[0] = true;
                    return true;
                }

                @Override
                public boolean visit(Assignment node) {
                    if (rejectFieldAssign != null && node.getLeftHandSide() instanceof SimpleName n
                        && n.resolveBinding() instanceof IVariableBinding b && b.isEqualTo(rejectFieldAssign)) {
                        unsafe[0] = true;
                    }
                    return true;
                }
            });
        }
        if (unsafe[0]) {
            return null;
        }
        int start = stmts.get(0).getStartPosition();
        Statement lastKept = stmts.get(stmts.size() - 1);
        return source.substring(start, lastKept.getStartPosition() + lastKept.getLength());
    }

    /** A {@code static final int} constant declared on {@code context}. */
    static boolean isStaticFinalIntConstant(Expression label, TypeDeclaration context) {
        if (!(label instanceof SimpleName name) || !(name.resolveBinding() instanceof IVariableBinding b)
            || !b.isField()) {
            return false;
        }
        int mods = b.getModifiers();
        return Modifier.isStatic(mods) && Modifier.isFinal(mods)
            && "int".equals(b.getType().getName())
            && b.getDeclaringClass() != null
            && context.resolveBinding() != null
            && b.getDeclaringClass().isEqualTo(context.resolveBinding());
    }

    /** PascalCase from an UPPER_SNAKE or plain label (STATUS_NEW -> StatusNew, RED -> Red). */
    static String pascal(String label) {
        StringBuilder sb = new StringBuilder();
        for (String part : label.split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    /** Re-indent multi-line body text (trimming each line) to {@code indent}. */
    static String reindent(String bodyText, String indent) {
        String[] lines = bodyText.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(indent).append(lines[i].trim());
        }
        return sb.toString();
    }

    static <T extends ASTNode> T enclosing(ASTNode node, Class<T> type) {
        while (node != null && !type.isInstance(node)) {
            node = node.getParent();
        }
        return type.cast(node);
    }

    static CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
