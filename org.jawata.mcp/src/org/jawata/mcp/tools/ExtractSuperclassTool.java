package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
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
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.structure.ExtractSupertypeProcessor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.CreateCompilationUnitChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.RefactoringEngine;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * <b>Extract Superclass</b> (the {@code extract(kind=superclass)} delegate),
 * TWO modes since Sprint 25 (spec D1a item 7):
 *
 * <p><b>Default ({@code mode=jdt})</b> — JDT's own
 * {@link ExtractSupertypeProcessor} (the IDE's Refactor &gt; Extract Superclass;
 * shipped headless in {@code org.eclipse.jdt.core.manipulation} — the earlier
 * claim here that the engine "lives in jdt.ui" was FALSE, falsified by the
 * Sprint-25 provenance audit). The general case: pulls up fields and
 * non-identical members, generates necessary constructors and type parameters,
 * inserts the new type between the subclasses and their existing common
 * superclass, and deletes matching duplicates in the sibling types.</p>
 *
 * <p><b>{@code mode=identical}</b> — the preserved Sprint-18 conservative
 * implementation (kept by decision, 2026-07-16): only methods that are
 * <em>byte-identical across all the types</em> and <em>self-contained</em>
 * (referencing no instance field and no non-pulled instance method) are pulled
 * up; the caret type and every sibling must not already extend anything. Its
 * guarantees are unchanged — the behaviour-preserving half of the
 * reuse-over-reinvent workflow ({@code copy_class} then
 * {@code extract_superclass}), where the pulled code is provably shared.</p>
 *
 * <p>Both modes compose a single undo; the v2.12.1 compile-verify gate stays
 * wrapped around the applied change.</p>
 */
public class ExtractSuperclassTool extends AbstractApplyingRefactoringTool {

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    public ExtractSuperclassTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "extract_superclass";
    }

    @Override
    public String getDescription() {
        return "Extract Superclass — synthesize a new abstract parent from a class and its "
            + "same-package siblings. Default mode (jdt) uses JDT's Extract Superclass engine: "
            + "pulls up the named members (fields and methods; default: the methods shared "
            + "byte-identically), creates necessary constructors, reparents every named type, "
            + "and removes matching duplicates in the siblings. mode=identical is the "
            + "conservative Sprint-18 contract: only byte-identical, self-contained methods, "
            + "types must not already extend anything. Delegate of extract(kind=superclass).";
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
            "description", "Optional: member names to pull up. Default: auto-discover the methods "
                + "shared byte-identically. mode=jdt accepts fields and non-identical members; "
                + "mode=identical requires identical methods."));
        properties.put("mode", Map.of("type", "string", "enum", List.of("jdt", "identical"),
            "description", "jdt (default): the general JDT Extract Superclass engine. "
                + "identical: the conservative byte-identical + self-contained contract."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "superclassName"));
        return withAutoApply(withProjectKey(schema));
    }

    private record Sib(String name, CompilationUnit ast, TypeDeclaration td, String src, IFile file) {}

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String mode = getStringParam(arguments, "mode");
        if (mode == null || mode.isBlank() || "jdt".equals(mode)) {
            return prepareJdtMode(service, arguments);
        }
        if ("identical".equals(mode)) {
            return prepareIdenticalMode(service, arguments);
        }
        return Preparation.fail(ToolResponse.invalidParameter("mode",
            "Unknown mode '" + mode + "'. Allowed: jdt (default), identical."));
    }

    // ==================================================================
    // Default mode: JDT ExtractSupertypeProcessor
    // ==================================================================

    private Preparation prepareJdtMode(IJdtService service, JsonNode arguments) throws Exception {
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
        IType caretType = service.getTypeAtPosition(path, line, column);
        if (caretType == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "No type at " + line + ":" + column + "."));
        }
        ICompilationUnit caretCu = caretType.getCompilationUnit();
        if (caretCu == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Source not available."));
        }
        if (caretType.isInterface() || caretType.isEnum()) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Caret must be on a class."));
        }

        // Resolve the same-package siblings; the extracted type slots between the
        // types and their COMMON superclass, so every sibling must share the
        // caret's (possibly absent) superclass.
        List<String> sibNames = stringArray(arguments, "siblings");
        IPackageFragment pkg = (IPackageFragment) caretCu.getParent();
        List<IType> types = new ArrayList<>();
        types.add(caretType);
        String caretSuper = caretType.getSuperclassName();
        for (String s : sibNames) {
            if (s.equals(caretType.getElementName())) {
                continue;
            }
            ICompilationUnit scu = pkg.getCompilationUnit(s + ".java");
            if (scu == null || !scu.exists()) {
                return Preparation.fail(ToolResponse.invalidParameter("siblings", "Sibling not found in package: " + s + "."));
            }
            IType sibType = scu.getType(s);
            if (!sibType.exists() || sibType.isInterface() || sibType.isEnum()) {
                return Preparation.fail(ToolResponse.invalidParameter("siblings", s + " is not a class in " + s + ".java."));
            }
            if (!Objects.equals(caretSuper, sibType.getSuperclassName())) {
                return Preparation.fail(ToolResponse.invalidParameter("siblings",
                    s + " does not share " + caretType.getElementName() + "'s superclass ("
                        + (caretSuper == null ? "none" : caretSuper) + " vs "
                        + (sibType.getSuperclassName() == null ? "none" : sibType.getSuperclassName())
                        + ") — the extracted type must slot under a common parent."));
            }
            types.add(sibType);
        }
        if (types.size() < 2) {
            return Preparation.fail(ToolResponse.invalidParameter("siblings",
                "Provide at least one sibling class in the same package to extract a common superclass."));
        }

        // Members to pull up, from the CARET type: explicit names (fields and
        // methods — the general case), or the shared byte-identical methods.
        List<String> explicit = stringArray(arguments, "members");
        List<IMember> members = new ArrayList<>();
        if (!explicit.isEmpty()) {
            for (String name : explicit) {
                boolean found = false;
                IField field = caretType.getField(name);
                if (field.exists()) {
                    members.add(field);
                    found = true;
                }
                for (IMethod m : caretType.getMethods()) {
                    if (!m.isConstructor() && m.getElementName().equals(name)) {
                        members.add(m);
                        found = true;
                    }
                }
                if (!found) {
                    return Preparation.fail(ToolResponse.invalidParameter("members",
                        "Member '" + name + "' not found in " + caretType.getElementName() + "."));
                }
            }
        } else {
            members.addAll(sharedIdenticalMethods(caretType, types));
            if (members.isEmpty()) {
                return Preparation.fail(ToolResponse.invalidParameter("members",
                    "No methods shared byte-identically across all the named types; name the "
                        + "members to pull up explicitly (mode=jdt accepts fields and "
                        + "non-identical members — the caret type's version wins)."));
            }
        }

        String pkgName = pkg.getElementName();
        IContainer parentDir = (IContainer) caretCu.getResource().getParent();
        IFile parentFile = parentDir.getFile(new Path(parentName + ".java"));
        if (parentFile.exists()) {
            return Preparation.fail(ToolResponse.invalidParameter("superclassName",
                "A file named " + parentName + ".java already exists in this package."));
        }

        HeadlessJdtConfig.ensureInitialized();
        ExtractSupertypeProcessor processor =
            new ExtractSupertypeProcessor(members.toArray(new IMember[0]), new CodeGenerationSettings());
        processor.setTypeName(parentName);
        processor.setTypesToExtract(types.toArray(new IType[0]));

        CheckedChange checked;
        List<String> deletedInSubtypes = new ArrayList<>();
        try {
            // The driver creates the working-copy layer (the wizard does this on
            // page transition): it materializes the extracted type and reparents
            // the types in the layer, so matching duplicates can be computed.
            RefactoringStatus layerStatus = processor.createWorkingCopyLayer(new NullProgressMonitor());
            if (layerStatus.hasFatalError()) {
                return Preparation.fail(ToolResponse.error(
                    "EXTRACT_REFUSED",
                    "extract_superclass (jdt) refused: " + layerStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL),
                    "JDT's Extract Superclass engine rejected the setup. No files were modified."));
            }
            // Sibling dedup — the wizard's checked-by-default list: matching
            // members in the reparented types are deleted (they become inherited).
            Set<IMember> moved = new LinkedHashSet<>(members);
            List<IMethod> deleted = new ArrayList<>();
            for (IMember match : processor.getMatchingElements(new NullProgressMonitor(), false)) {
                if (match instanceof IMethod m && !moved.contains(match)) {
                    deleted.add(m);
                    IType owner = m.getDeclaringType();
                    deletedInSubtypes.add((owner == null ? "?" : owner.getElementName()) + "#" + m.getElementName());
                }
            }
            processor.setDeletedMethods(deleted.toArray(new IMethod[0]));

            ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
            checked = engine.propose(refactoring, "extract superclass " + parentName);
        } finally {
            // Deterministically discard the fOwner working copies; the produced
            // change resolves files through primary-unit handles, so it stays
            // valid for the (possibly staged) apply.
            processor.resetEnvironment();
        }
        if (checked.isRefused()) {
            return Preparation.fail(ToolResponse.error(
                "EXTRACT_REFUSED",
                "extract_superclass (jdt) refused: " + checked.messages(),
                "JDT's Extract Superclass engine rejected it — a precondition failed "
                    + "(name collision, unpullable member, reference it cannot preserve). "
                    + "Adjust the input or use mode=identical for the conservative contract. "
                    + "No files were modified."));
        }

        List<String> typeNames = types.stream().map(IType::getElementName).toList();
        List<String> memberNames = members.stream().map(IMember::getElementName).distinct().toList();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("newSuperclass", parentName);
        extras.put("package", pkgName == null ? "" : pkgName);
        extras.put("subclasses", typeNames);
        extras.put("pulledUpMembers", memberNames);
        extras.put("deletedInSubtypes", deletedInSubtypes);
        extras.put("mode", "jdt");
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }
        String summary = "extract superclass " + parentName + " from " + String.join(", ", typeNames)
            + " (jdt); pulled up " + memberNames;
        return Preparation.of(checked.change(), summary, extras);
    }

    /**
     * The methods of the caret type declared byte-identically (normalized
     * whitespace) in EVERY named type — the default pull-up selection.
     */
    private static List<IMethod> sharedIdenticalMethods(IType caretType, List<IType> allTypes) throws Exception {
        List<IMethod> shared = new ArrayList<>();
        for (IMethod m : caretType.getMethods()) {
            if (m.isConstructor()) {
                continue;
            }
            String norm = normalize(m.getSource());
            boolean inAll = true;
            for (IType other : allTypes) {
                if (other.equals(caretType)) {
                    continue;
                }
                IMethod counterpart = null;
                for (IMethod om : other.getMethods()) {
                    if (!om.isConstructor()
                            && om.getElementName().equals(m.getElementName())
                            && om.getParameterTypes().length == m.getParameterTypes().length) {
                        counterpart = om;
                        break;
                    }
                }
                if (counterpart == null || !normalize(counterpart.getSource()).equals(norm)) {
                    inAll = false;
                    break;
                }
            }
            if (inAll) {
                shared.add(m);
            }
        }
        return shared;
    }

    // ==================================================================
    // mode=identical: the preserved Sprint-18 conservative implementation
    // (byte-identical + self-contained methods; kept by decision 2026-07-16).
    // Body unchanged — the parity battery gates it against its golden.
    // ==================================================================

    private Preparation prepareIdenticalMode(IJdtService service, JsonNode arguments) throws Exception {
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
        extras.put("mode", "identical");
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
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
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
