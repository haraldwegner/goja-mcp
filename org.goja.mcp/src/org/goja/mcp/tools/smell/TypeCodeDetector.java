package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 19 (Kerievsky) — detects a <b>Type Code</b>: a class holding a group of
 * {@code static final} constants of the same type ({@code int} or {@code String})
 * sharing a common prefix (e.g. {@code STATUS_NEW}, {@code STATUS_PAID},
 * {@code STATUS_SHIPPED}). Such a set is a primitive stand-in for a type — the
 * trigger for {@code refactor_to_pattern(kind=replace_type_code_with_class)}, which
 * introduces a type-safe enum. Default threshold: 3 constants sharing a prefix.
 */
public final class TypeCodeDetector extends AbstractAstDetector {

    public TypeCodeDetector() {
        super("type_code",
            "Type Code — a class with >= `threshold` static-final constants of the same type "
                + "(int/String) sharing a prefix (STATUS_*, TYPE_*). A primitive stand-in for a type; "
                + "candidate for Replace Type Code with Class (refactor_to_pattern). Default threshold 3.",
            3);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                // group static-final int/String constant names by (typeTag + prefix-before-first-underscore)
                Map<String, Integer> groups = new LinkedHashMap<>();
                Map<String, String> groupPrefix = new LinkedHashMap<>();
                for (FieldDeclaration f : node.getFields()) {
                    int mods = f.getModifiers();
                    if (!Modifier.isStatic(mods) || !Modifier.isFinal(mods)) {
                        continue;
                    }
                    String typeTag = typeTag(f.getType());
                    if (typeTag == null) {
                        continue;
                    }
                    for (Object frag : f.fragments()) {
                        String name = ((VariableDeclarationFragment) frag).getName().getIdentifier();
                        String prefix = name.contains("_") ? name.substring(0, name.indexOf('_')) : name;
                        String key = typeTag + ":" + prefix;
                        groups.merge(key, 1, Integer::sum);
                        groupPrefix.putIfAbsent(key, prefix);
                    }
                }
                groups.entrySet().stream()
                    .filter(e -> e.getValue() >= threshold)
                    .max(Map.Entry.comparingByValue())
                    .ifPresent(e -> {
                        int line = ast.getLineNumber(node.getStartPosition());
                        String name = node.getName().getIdentifier();
                        String prefix = groupPrefix.get(e.getKey());
                        out.add(new Finding(
                            "type_code", filePath, line, -1, "info",
                            "Class '" + name + "' has " + e.getValue() + " '" + prefix
                                + "_*' type-code constants. Consider Replace Type Code with Class "
                                + "(refactor_to_pattern kind=replace_type_code_with_class).",
                            name));
                    });
                return true;
            }
        });
    }

    /** "int"/"String" for a supported type-code type, else null. */
    private static String typeTag(Type type) {
        if (type.isPrimitiveType() && ((PrimitiveType) type).getPrimitiveTypeCode() == PrimitiveType.INT) {
            return "int";
        }
        ITypeBinding b = type.resolveBinding();
        if (b != null && "java.lang.String".equals(b.getQualifiedName())) {
            return "String";
        }
        return null;
    }
}
