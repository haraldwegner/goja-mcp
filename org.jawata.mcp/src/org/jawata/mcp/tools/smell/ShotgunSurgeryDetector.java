package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 17 (Fowler) — <b>Shotgun Surgery</b>. The structural dual of Divergent
 * Change: where Divergent Change is one class touched for many reasons, Shotgun
 * Surgery is one change that ripples across many classes. Proxy: a <em>concrete</em>
 * type referenced from more than {@code threshold} distinct other types (default
 * 10) — a change to its surface forces edits in all of them. Pointed refactoring:
 * <b>Move Method/Field</b> (gather the scattered responsibility) / Inline Class.
 *
 * <p>v1.3.1 recalibration (dogfood): <b>interfaces and abstract classes are
 * skipped</b> — they are abstractions and are <em>meant</em> to be depended on
 * widely (that's DIP, not a smell; flagging IJdtService/Detector/Tool was noise),
 * and the default threshold was raised 5 → 10.</p>
 */
public final class ShotgunSurgeryDetector extends AbstractAstDetector {

    public ShotgunSurgeryDetector() {
        super("shotgun_surgery",
            "Shotgun Surgery — a concrete type referenced from > `threshold` distinct other types "
                + "(default 10); a change to it ripples across all of them. Points to Move Method/Field. "
                + "Skips interfaces/abstract classes (wide dependence on an abstraction is DIP, not a smell).",
            10);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        analyze(ast, filePath, service, threshold, out, new ScanDegradation());
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out, ScanDegradation degraded) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (node.isInterface() || Modifier.isAbstract(node.getModifiers())) {
                    return true; // abstractions are meant to be widely depended on (DIP)
                }
                ITypeBinding binding = node.resolveBinding();
                if (binding == null || !(binding.getJavaElement() instanceof IType type)) {
                    degraded.report("shotgun_surgery candidate '" + node.getName() + "' (" + filePath
                        + ") skipped: " + (binding == null
                            ? "its type binding did not resolve"
                            : "binding has no IType in the Java model"));
                    return true;
                }
                // -1 (lookup failed) never exceeds the threshold, so the finding is
                // SUPPRESSED — which is why the failure must land in `degraded`.
                int spread = SmellSearch.referencingTypeCount(type, service, degraded);
                if (spread > threshold) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "shotgun_surgery", filePath, line, -1, "warning",
                        "Type '" + name + "' is referenced from " + spread + " distinct types (threshold "
                            + threshold + "); a change to it ripples widely. Consider Move Method/Field "
                            + "to gather the scattered responsibility." + OcpCure.HINT,
                        name));
                }
                return true;
            }
        });
    }
}
