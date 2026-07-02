package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.text.edits.TextEdit;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.CreateCompilationUnitChange;
import org.goja.mcp.refactoring.RefactoringChangeCache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 18 — <b>Extract Superclass</b> (the {@code extract(kind=superclass)}
 * cost-tool primitive). Given a class at a caret and one or more sibling classes
 * in the same package, synthesise a new abstract superclass and pull the members
 * they share up into it — the second half of the reuse-over-reinvent workflow
 * ({@code copy_class} then {@code extract_superclass}).
 *
 * <p>Conservative-or-refuse (v1.5): the caret type and every named sibling must be
 * classes in the same package that do <em>not</em> already extend a superclass;
 * only <b>methods</b> are pulled up (not fields/constructors), and only those that
 * are <b>byte-identical across all the types</b> (so the move is behaviour-
 * preserving) and <b>self-contained</b> (they reference no instance field of the
 * class and no non-pulled instance method — so they still resolve from the parent).
 * The new file must not already exist. JDT's own {@code ExtractSupertypeRefactoring}
 * lives in {@code jdt.ui} (off our target platform), so this composes the change
 * from primitives: one {@link CreateCompilationUnitChange} for the parent plus a
 * {@link ChangeEngine#fromFileEdits} edit per subclass (add {@code extends} +
 * delete the pulled methods), wrapped in a {@link CompositeChange} so a single undo
 * reverts everything.</p>
 *
 * <p>A delegate of the {@code extract} front door (kind {@code superclass}).</p>
 */
public class ExtractSuperclassTool extends AbstractApplyingRefactoringTool {

    public ExtractSuperclassTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "extract_superclass";
    }

    @Override
    public String getDescription() {
        return "Extract Superclass — synthesize a new abstract parent and pull up the methods shared "
            + "(byte-identical + self-contained) by a class and its same-package siblings. "
            + "Delegate of extract(kind=superclass).";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string", "description", "Source file of the caret class."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line on the class."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column on the class."));
        properties.put("superclassName", Map.of("type", "string", "description", "Name for the generated abstract superclass."));
        properties.put("siblings", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "Sibling class simple names in the same package to also reparent + pull from."));
        properties.put("members", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "Optional: method names to pull up (must be identical across all types). Default: auto-discover."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "superclassName"));
        return withAutoApply(withProjectKey(schema));
    }

    private record Sib(String name, CompilationUnit ast, TypeDeclaration td, String src, IFile file) {}

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required."));
        }
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column", "Must be >= 0 (zero-based)."));
        }
        String parentName = getStringParam(arguments, "superclassName");
        if (parentName == null || !isIdentifier(parentName)) {
            return Preparation.fail(ToolResponse.invalidParameter("superclassName", "A valid Java type name is required."));
        }

        java.nio.file.Path path = java.nio.file.Path.of(filePath);
        IType aType = service.getTypeAtPosition(path, line, column);
        if (aType == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "No type at " + line + ":" + column + "."));
        }
        ICompilationUnit aCu = aType.getCompilationUnit();
        if (aCu == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Source not available."));
        }
        CompilationUnit aAst = parse(aCu);
        TypeDeclaration aTd = findType(aAst, aType.getElementName());
        if (aTd == null || aTd.isInterface()) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Caret must be on a class."));
        }
        if (aTd.getSuperclassType() != null) {
            return Preparation.fail(ToolResponse.invalidParameter("type",
                aType.getElementName() + " already extends a superclass; extract_superclass is conservative and refuses to reparent."));
        }
        ITypeBinding aBinding = aTd.resolveBinding();
        if (aBinding == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Could not resolve the class bindings."));
        }
        String aSrc = aCu.getSource();

        // Resolve the same-package siblings.
        List<String> sibNames = stringArray(arguments, "siblings");
        IPackageFragment pkg = (IPackageFragment) aCu.getParent();
        List<Sib> sibs = new ArrayList<>();
        for (String s : sibNames) {
            if (s.equals(aType.getElementName())) {
                continue;
            }
            ICompilationUnit scu = pkg.getCompilationUnit(s + ".java");
            if (scu == null || !scu.exists()) {
                return Preparation.fail(ToolResponse.invalidParameter("siblings", "Sibling not found in package: " + s + "."));
            }
            CompilationUnit sast = parse(scu);
            TypeDeclaration std = findType(sast, s);
            if (std == null || std.isInterface()) {
                return Preparation.fail(ToolResponse.invalidParameter("siblings", s + " is not a class in " + s + ".java."));
            }
            if (std.getSuperclassType() != null) {
                return Preparation.fail(ToolResponse.invalidParameter("siblings", s + " already extends a superclass."));
            }
            sibs.add(new Sib(s, sast, std, scu.getSource(), (IFile) scu.getResource()));
        }
        if (sibs.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("siblings",
                "Provide at least one sibling class in the same package to extract a common superclass."));
        }

        // Candidate methods = present + byte-identical across the caret class AND every sibling.
        List<String> explicit = stringArray(arguments, "members");
        Set<String> explicitSet = new LinkedHashSet<>(explicit);
        Map<String, MethodDeclaration> byKey = new LinkedHashMap<>();
        for (MethodDeclaration m : aTd.getMethods()) {
            if (m.isConstructor()) {
                continue;
            }
            if (!explicitSet.isEmpty() && !explicitSet.contains(m.getName().getIdentifier())) {
                continue;
            }
            String key = sigKey(m);
            String norm = normalize(source(m, aSrc));
            boolean inAll = true;
            for (Sib sib : sibs) {
                MethodDeclaration sm = methodByKey(sib.td(), key);
                if (sm == null || !normalize(source(sm, sib.src())).equals(norm)) {
                    inAll = false;
                    break;
                }
            }
            if (inAll) {
                byKey.put(key, m);
            }
        }
        if (!explicitSet.isEmpty()) {
            // Every explicitly-named member must have qualified.
            for (String name : explicitSet) {
                boolean found = byKey.values().stream().anyMatch(m -> m.getName().getIdentifier().equals(name));
                if (!found) {
                    return Preparation.fail(ToolResponse.invalidParameter("members",
                        "Member '" + name + "' is not identical across all the named types (cannot pull up safely)."));
                }
            }
        }

        // Self-containment: drop candidates that reference an instance field of the class or a
        // non-candidate instance method (they would not resolve from the parent). Fixpoint, since
        // dropping one candidate can disqualify another that called it.
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<String> candidateSigs = new LinkedHashSet<>(byKey.keySet());
            for (Map.Entry<String, MethodDeclaration> e : new ArrayList<>(byKey.entrySet())) {
                if (!selfContained(e.getValue(), aBinding, candidateSigs)) {
                    byKey.remove(e.getKey());
                    changed = true;
                }
            }
        }
        if (byKey.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("members",
                "No common, self-contained methods to pull up (need methods byte-identical across all types "
                    + "that reference no instance field or non-pulled method)."));
        }
        Set<String> pulledSigs = new LinkedHashSet<>(byKey.keySet());
        List<String> pulledNames = byKey.values().stream().map(m -> m.getName().getIdentifier()).toList();

        // Build the parent source (imports carried over defensively; unused ones are warnings, not errors).
        String pkgName = aAst.getPackage() != null ? aAst.getPackage().getName().getFullyQualifiedName() : null;
        List<String> typeNames = new ArrayList<>();
        typeNames.add(aType.getElementName());
        sibs.forEach(s -> typeNames.add(s.name()));
        String parentSource = buildParentSource(pkgName, parentName, aAst, aSrc, byKey.values(), typeNames);

        IContainer parentDir = (IContainer) aCu.getResource().getParent();
        IFile parentFile = parentDir.getFile(new Path(parentName + ".java"));
        if (parentFile.exists()) {
            return Preparation.fail(ToolResponse.invalidParameter("superclassName",
                "A file named " + parentName + ".java already exists in this package."));
        }

        // Compose: create the parent + reparent-and-strip each subclass, one atomic undo.
        CompositeChange composite = new CompositeChange("extract superclass " + parentName);
        composite.add(new CreateCompilationUnitChange(parentFile, parentSource));
        Map<IFile, List<TextEdit>> editsByFile = new LinkedHashMap<>();
        editsByFile.put((IFile) aCu.getResource(),
            List.of(subclassEdit(aAst, aTd, parentName, pulledSigs)));
        for (Sib sib : sibs) {
            editsByFile.put(sib.file(), List.of(subclassEdit(sib.ast(), sib.td(), parentName, pulledSigs)));
        }
        composite.add(ChangeEngine.fromFileEdits("extract superclass edits", editsByFile));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("newSuperclass", parentName);
        extras.put("package", pkgName == null ? "" : pkgName);
        extras.put("subclasses", typeNames);
        extras.put("pulledUpMembers", pulledNames);
        String summary = "extract superclass " + parentName + " from " + String.join(", ", typeNames)
            + "; pulled up " + pulledNames;
        return Preparation.of(composite, summary, extras);
    }

    /** ASTRewrite for one subclass: add {@code extends Parent} and delete the pulled methods. */
    private static TextEdit subclassEdit(CompilationUnit ast, TypeDeclaration td, String parentName, Set<String> pulledSigs)
            throws Exception {
        AST a = ast.getAST();
        ASTRewrite rewrite = ASTRewrite.create(a);
        rewrite.set(td, TypeDeclaration.SUPERCLASS_TYPE_PROPERTY,
            a.newSimpleType(a.newSimpleName(parentName)), null);
        for (MethodDeclaration m : td.getMethods()) {
            if (!m.isConstructor() && pulledSigs.contains(sigKey(m))) {
                rewrite.remove(m, null);
            }
        }
        return rewrite.rewriteAST();
    }

    private static String buildParentSource(String pkgName, String parentName, CompilationUnit aAst,
                                            String aSrc, Iterable<MethodDeclaration> members, List<String> typeNames) {
        StringBuilder sb = new StringBuilder();
        if (pkgName != null && !pkgName.isBlank()) {
            sb.append("package ").append(pkgName).append(";\n\n");
        }
        boolean anyImport = false;
        for (Object o : aAst.imports()) {
            sb.append(source((ImportDeclaration) o, aSrc)).append("\n");
            anyImport = true;
        }
        if (anyImport) {
            sb.append("\n");
        }
        sb.append("/**\n")
            .append(" * Extracted superclass for ").append(String.join(", ", typeNames)).append(".\n")
            .append(" * Generated by extract(kind=superclass); shared methods pulled up.\n")
            .append(" */\n");
        sb.append("public abstract class ").append(parentName).append(" {\n\n");
        for (MethodDeclaration m : members) {
            sb.append("    ").append(source(m, aSrc)).append("\n\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    /** True when the method references no instance field of the type and no non-candidate instance method. */
    private static boolean selfContained(MethodDeclaration method, ITypeBinding owner, Set<String> candidateSigs) {
        boolean[] ok = {true};
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (!ok[0]) {
                    return false;
                }
                if (node.resolveBinding() instanceof IVariableBinding vb
                    && vb.isField() && sameType(vb.getDeclaringClass(), owner)) {
                    ok[0] = false;
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                if (!ok[0]) {
                    return false;
                }
                IMethodBinding mb = node.resolveMethodBinding();
                if (mb != null && !org.eclipse.jdt.core.dom.Modifier.isStatic(mb.getModifiers())
                    && sameType(mb.getDeclaringClass(), owner)
                    && !candidateSigs.contains(sigKeyOf(mb))) {
                    ok[0] = false;
                }
                return true;
            }
        });
        return ok[0];
    }

    private static boolean sameType(ITypeBinding a, ITypeBinding b) {
        return a != null && b != null && a.getErasure().isEqualTo(b.getErasure());
    }

    private static String sigKey(MethodDeclaration m) {
        StringBuilder sb = new StringBuilder(m.getName().getIdentifier()).append('(');
        for (Object p : m.parameters()) {
            sb.append(((SingleVariableDeclaration) p).getType().toString()).append(',');
        }
        return sb.append(')').toString();
    }

    private static String sigKeyOf(IMethodBinding mb) {
        StringBuilder sb = new StringBuilder(mb.getName()).append('(');
        for (ITypeBinding pt : mb.getParameterTypes()) {
            sb.append(pt.getName()).append(',');
        }
        return sb.append(')').toString();
    }

    private static MethodDeclaration methodByKey(TypeDeclaration td, String key) {
        for (MethodDeclaration m : td.getMethods()) {
            if (!m.isConstructor() && sigKey(m).equals(key)) {
                return m;
            }
        }
        return null;
    }

    private static String source(org.eclipse.jdt.core.dom.ASTNode node, String src) {
        return src.substring(node.getStartPosition(), node.getStartPosition() + node.getLength());
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static CompilationUnit parse(ICompilationUnit cu) throws Exception {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }

    private static TypeDeclaration findType(CompilationUnit ast, String simpleName) {
        for (Object t : ast.types()) {
            if (t instanceof TypeDeclaration td && simpleName.equals(td.getName().getIdentifier())) {
                return td;
            }
        }
        return null;
    }

    private List<String> stringArray(JsonNode arguments, String name) {
        List<String> out = new ArrayList<>();
        JsonNode node = arguments.get(name);
        if (node != null && node.isArray()) {
            for (JsonNode n : node) {
                String s = n.asText();
                if (s != null && !s.isBlank()) {
                    out.add(s.trim());
                }
            }
        }
        return out;
    }

    private static boolean isIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
