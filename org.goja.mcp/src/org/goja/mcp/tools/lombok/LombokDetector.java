package org.goja.mcp.tools.lombok;

import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.ImportDeclaration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Sprint 15 — shared Lombok detection. Recognises Lombok usage from source
 * alone (by import + annotation simple-name), so it works WITHOUT Lombok on the
 * classpath and without the {@code -javaagent} that comprehension would need.
 *
 * <p>This is the fork-side detector behind {@code find_modernization}'s
 * {@code lombok_to_record} / {@code delombok} kinds (Lombok <em>removal</em>).
 * The manager has its own Rust-side equivalent that drives the conditional
 * {@code -javaagent:lombok.jar} for Lombok <em>comprehension</em> — same
 * concept, different runtime.</p>
 */
public final class LombokDetector {

    private LombokDetector() {}

    /** Lombok annotation simple names that synthesize members or behaviour. */
    public static final Set<String> ANNOTATIONS = Set.of(
        "Data", "Value", "Getter", "Setter", "Builder", "SuperBuilder",
        "AllArgsConstructor", "NoArgsConstructor", "RequiredArgsConstructor",
        "ToString", "EqualsAndHashCode", "With", "Wither",
        "Slf4j", "Log", "Log4j", "Log4j2", "CommonsLog", "XSlf4j", "Synchronized");

    /** Annotations that mark a class as a Lombok data carrier (record-shaped). */
    public static final Set<String> DATA_ANNOTATIONS = Set.of("Data", "Value");

    /** True when the compilation unit imports anything from {@code lombok.}. */
    public static boolean importsLombok(CompilationUnit ast) {
        for (Object o : ast.imports()) {
            ImportDeclaration imp = (ImportDeclaration) o;
            String name = imp.getName().getFullyQualifiedName();
            if (name.equals("lombok") || name.startsWith("lombok.")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lombok annotation simple names present on a declaration's modifier list.
     * Matches by simple name against {@link #ANNOTATIONS} — robust without
     * binding resolution.
     */
    public static List<String> lombokAnnotations(BodyDeclaration decl) {
        List<String> found = new ArrayList<>();
        for (Object o : decl.modifiers()) {
            if (o instanceof Annotation ann) {
                String simple = simpleName(ann);
                if (ANNOTATIONS.contains(simple)) {
                    found.add(simple);
                }
            }
        }
        return found;
    }

    /** True when the declaration carries a class-level data annotation (@Data/@Value). */
    public static boolean isDataCarrier(BodyDeclaration decl) {
        for (Object o : decl.modifiers()) {
            if (o instanceof IExtendedModifier m && m.isAnnotation()) {
                if (DATA_ANNOTATIONS.contains(simpleName((Annotation) o))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String simpleName(Annotation ann) {
        String fqn = ann.getTypeName().getFullyQualifiedName();
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
