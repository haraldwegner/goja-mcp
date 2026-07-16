package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.internal.corext.refactoring.structure.ChangeSignatureProcessor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.RefactoringEngine;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Change method signature (parameters, return type, name, visibility) and
 * update all call sites.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}.</p>
 *
 * <p>Sprint 25 (D1b): the signature change (name / return type / parameters /
 * visibility) is computed by JDT's own {@link ChangeSignatureProcessor} — the
 * engine behind the IDE's Refactor → Change Method Signature — driven through
 * the {@link RefactoringEngine} seam. The original implementation string-built
 * the new declaration and rewrote each call site by hand; the JDT engine
 * resolves the ripple across overrides, generics, and varargs, and inserts an
 * added parameter's default value at every call site. The {@code retargetCallsTo}
 * mode (rewrite callers to a DIFFERENT existing method — not a signature change)
 * has no JDT equivalent and stays a hand-rolled call-site rewrite.</p>
 */
public class ChangeMethodSignatureTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ChangeMethodSignatureTool.class);

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    /**
     * A signature change legitimately leaves bodies and call sites to adapt (a
     * return-type change makes old {@code return} statements red BY DESIGN) —
     * so introduced TYPE errors are kept and reported loudly rather than
     * undone. SYNTAX errors still refuse: garbage is never a follow-up task.
     */
    @Override
    protected GateMode compileGateMode() {
        return GateMode.REPORT;
    }

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    private static final Set<String> VISIBILITIES = Set.of("public", "protected", "package", "private");

    public ChangeMethodSignatureTool(Supplier<IJdtService> serviceSupplier,
                                     RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "change_method_signature";
    }

    @Override
    public String getDescription() {
        return """
            Change method signature (parameters, return type, or name) and update all call sites.

            Applies the change directly (default) and returns
            { filesModified, diff, undoChangeId, summary }. Verify with
            compile_workspace; revert with undo_refactoring(undoChangeId).
            Pass auto_apply: false to stage instead — returns { changeId, diff }.

            USAGE: Position on method declaration, provide changes
            OUTPUT: Modified files + unified diff + undo handle

            PARAMETER OPERATIONS:
            - Add new parameter with default value for existing calls
            - Remove parameter (will remove from calls)
            - Rename parameter
            - Reorder parameters (specify all parameters in new order)

            IMPORTANT: Uses ZERO-BASED coordinates.

            COUPLED CHANGES: some changes cannot leave the code compiling on their own —
            removing a parameter the body still uses, or a return-type change a
            value-returning body cannot satisfy (neither the signature nor the body
            compiles without the other). These ARE APPLIED anyway; the response marks
            coupledChange: true and lists every introduced compiler error as your
            worklist. Change the signature here, then fix each reported body/caller site
            by its compile failure; undo_refactoring to abandon. The reported error
            locations ARE the edits to make — do not fall back to a text search.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of(
            "type", "string",
            "description", "Path to source file containing the method"
        ));
        properties.put("line", Map.of(
            "type", "integer",
            "description", "Zero-based line number of method declaration"
        ));
        properties.put("column", Map.of(
            "type", "integer",
            "description", "Zero-based column number"
        ));
        properties.put("newName", Map.of(
            "type", "string",
            "description", "New method name (optional, omit to keep current)"
        ));
        properties.put("newReturnType", Map.of(
            "type", "string",
            "description", "New return type (optional, omit to keep current)"
        ));
        properties.put("newParameters", Map.of(
            "type", "array",
            "description", "New parameter list. Each item: {name, type, defaultValue?}. Order matters.",
            "items", Map.of(
                "type", "object",
                "properties", Map.of(
                    "name", Map.of("type", "string", "description", "Parameter name"),
                    "type", Map.of("type", "string", "description", "Parameter type"),
                    "defaultValue", Map.of("type", "string", "description", "Default value for new params at call sites")
                )
            )
        ));
        properties.put("visibility", Map.of(
            "type", "string",
            "enum", List.of("public", "protected", "package", "private"),
            "description", "New visibility (optional). 'package' removes the access modifier; "
                + "the response reports the reference-impact list of affected call sites."
        ));
        properties.put("retargetCallsTo", Map.of(
            "type", "string",
            "description", "Rewrite every call site to invoke this (already-existing) method name "
                + "instead, leaving the declaration unchanged — e.g. redirect callers of a "
                + "deprecated method to its replacement. Exclusive with the signature/visibility "
                + "changes; verify the result with compile_workspace."
        ));

        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "method whose signature changes"));
        schema.put("properties", properties);
        // Sprint 24 (D1): position OR name form.
        schema.put("required", List.of());
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required"));
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column", "Must be >= 0"));
        }

        String newName = getStringParam(arguments, "newName");
        String newReturnType = getStringParam(arguments, "newReturnType");
        String visibility = getStringParam(arguments, "visibility");
        if (visibility != null && !VISIBILITIES.contains(visibility)) {
            return Preparation.fail(ToolResponse.invalidParameter("visibility",
                "must be one of: public, protected, package, private"));
        }

        String retargetCallsTo = getStringParam(arguments, "retargetCallsTo");
        boolean retarget = retargetCallsTo != null && !retargetCallsTo.isBlank();
        if (retarget) {
            if (!isValidJavaIdentifier(retargetCallsTo)) {
                return Preparation.fail(ToolResponse.invalidParameter("retargetCallsTo",
                    "not a valid Java identifier"));
            }
            if (newName != null || newReturnType != null
                    || arguments.has("newParameters") || visibility != null) {
                return Preparation.fail(ToolResponse.invalidParameter("retargetCallsTo",
                    "retargetCallsTo rewrites call sites only and cannot be combined with "
                        + "newName / newReturnType / newParameters / visibility"));
            }
        }

        // Parse the requested parameter list (jawata form).
        List<ParameterInfo> newParameters = null;
        if (arguments.has("newParameters") && arguments.get("newParameters").isArray()) {
            newParameters = new ArrayList<>();
            for (JsonNode param : arguments.get("newParameters")) {
                String pName = param.has("name") ? param.get("name").asText() : null;
                String pType = param.has("type") ? param.get("type").asText() : null;
                String pDefault = param.has("defaultValue") ? param.get("defaultValue").asText() : null;
                if (pName == null || pType == null) {
                    return Preparation.fail(ToolResponse.invalidParameter("newParameters",
                        "Each parameter must have 'name' and 'type'"));
                }
                newParameters.add(new ParameterInfo(pName, pType, pDefault));
            }
        }

        if (newName == null && newReturnType == null && newParameters == null
                && visibility == null && !retarget) {
            return Preparation.fail(ToolResponse.invalidParameter("changes",
                "At least one of newName, newReturnType, newParameters, visibility, or "
                    + "retargetCallsTo must be specified"));
        }

        if (newName != null && !isValidJavaIdentifier(newName)) {
            return Preparation.fail(
                ToolResponse.invalidParameter("newName", "Not a valid Java identifier"));
        }

        Path path = Path.of(filePath);
        IJavaElement element = service.getElementAtPosition(path, line, column);
        if (!(element instanceof IMethod method)) {
            return Preparation.fail(ToolResponse.symbolNotFound("No method found at position"));
        }

        if (retarget) {
            return prepareRetarget(service, method, retargetCallsTo);
        }
        return prepareSignatureChange(service, method, newName, newReturnType, newParameters, visibility);
    }

    // ===================== JDT signature change =====================

    private Preparation prepareSignatureChange(IJdtService service, IMethod method,
                                               String newName, String newReturnType,
                                               List<ParameterInfo> newParameters,
                                               String visibility) throws Exception {
        HeadlessJdtConfig.ensureInitialized();

        String oldName = method.getElementName();
        boolean isConstructor = method.isConstructor();
        String oldReturnType = isConstructor ? null : Signature.toString(method.getReturnType());
        int oldParamCount = method.getParameterTypes().length;

        ChangeSignatureProcessor processor = new ChangeSignatureProcessor(method);
        if (newName != null && !newName.equals(oldName)) {
            processor.setNewMethodName(newName);
        }
        String newReturnTypeFinal = isConstructor ? null
            : (newReturnType != null ? newReturnType : oldReturnType);
        if (!isConstructor && newReturnType != null) {
            processor.setNewReturnTypeName(newReturnType);
        }
        if (visibility != null) {
            processor.setVisibility(jdtVisibility(visibility));
        }
        int newParamCount = oldParamCount;
        if (newParameters != null) {
            newParamCount = newParameters.size();
            applyParameterChanges(processor, newParameters);
        }

        ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
        CheckedChange checked = engine.propose(refactoring, "change signature of " + oldName);
        if (checked.isRefused()) {
            // JDT refuses a change that would leave the DECLARING method's body
            // inconsistent — remove a parameter the body still uses, or a return-type
            // change a value-returning body cannot satisfy. Those changes are COUPLED:
            // neither the signature nor the body compiles alone (signature-first =
            // "void methods cannot return a value"; body-first = "missing return
            // statement"), so they cannot be done one JDT operation at a time. Fall
            // back to the hand-rolled apply-and-report path: make the mechanical edit
            // and let the compile-verify gate (GateMode.REPORT) name the errors the
            // caller now fixes in the body. That "change the signature, find the body
            // edits by the compile failures" workflow is exactly why REPORT mode exists.
            log.debug("JDT refused ({}); falling back to REPORT-mode signature edit",
                checked.messages());
            return prepareSignatureChangeFallback(service, method, newName, newReturnType,
                newParameters, visibility);
        }

        Change change = checked.change();

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("oldName", oldName);
        extras.put("newName", newName != null ? newName : oldName);
        extras.put("oldReturnType", oldReturnType);
        extras.put("newReturnType", newReturnTypeFinal);
        extras.put("oldParameterCount", oldParamCount);
        extras.put("newParameterCount", newParamCount);
        extras.put("totalEdits", leafEditCount(change));
        extras.put("filesAffected", ChangeEngine.affectedFilePaths(change, service).size());
        if (visibility != null) {
            extras.put("visibility", visibility);
            List<SearchMatch> references = service.getSearchService().findReferences(
                method, IJavaSearchConstants.REFERENCES, 1000);
            extras.put("referenceImpact", referenceImpact(service, references));
        }
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }

        String summary = "change signature of " + oldName + " -> " + processor.getNewMethodSignature();
        log.debug("change_method_signature via JDT ChangeSignatureProcessor: {}", summary);
        return Preparation.of(change, summary, extras);
    }

    /**
     * Map the requested parameter list onto JDT's {@link ChangeSignatureProcessor}
     * parameter-info model: reuse a same-named existing parameter (retyped if
     * needed), synthesize an added one (with a compiling default at call sites),
     * and mark the ones no longer present as deleted. The non-deleted infos, in
     * request order, become the new signature order.
     */
    private static void applyParameterChanges(ChangeSignatureProcessor processor,
                                              List<ParameterInfo> requested) {
        List<org.eclipse.jdt.internal.corext.refactoring.ParameterInfo> infos =
            processor.getParameterInfos();

        Map<String, org.eclipse.jdt.internal.corext.refactoring.ParameterInfo> byOldName = new HashMap<>();
        for (var pi : infos) {
            byOldName.put(pi.getOldName(), pi);
        }
        Set<String> keptNames = new HashSet<>();
        for (ParameterInfo p : requested) {
            keptNames.add(p.name);
        }

        List<org.eclipse.jdt.internal.corext.refactoring.ParameterInfo> deleted = new ArrayList<>();
        for (var pi : infos) {
            if (!keptNames.contains(pi.getOldName())) {
                pi.markAsDeleted();
                deleted.add(pi);
            }
        }

        List<org.eclipse.jdt.internal.corext.refactoring.ParameterInfo> ordered = new ArrayList<>();
        for (ParameterInfo p : requested) {
            var existing = byOldName.get(p.name);
            if (existing != null && !existing.isDeleted()) {
                if (p.type != null && !p.type.equals(existing.getOldTypeName())) {
                    existing.setNewTypeName(p.type);
                }
                ordered.add(existing);
            } else {
                // The old tool synthesized "/* TODO name */ zeroValue" for an added
                // parameter without a default; JDT inserts an added parameter's
                // default-value expression verbatim at every call site, so hand it
                // the same synthesized value to preserve that behavior.
                String def = p.defaultValue != null
                    ? p.defaultValue
                    : "/* TODO " + p.name + " */ " + zeroValueFor(p.type);
                ordered.add(org.eclipse.jdt.internal.corext.refactoring.ParameterInfo
                    .createInfoForAddedParameter(p.type, p.name, def));
            }
        }

        infos.clear();
        infos.addAll(ordered);
        infos.addAll(deleted);
    }

    /** JDT {@link Modifier} flag for a visibility keyword ('package' = no modifier). */
    private static int jdtVisibility(String visibility) {
        return switch (visibility) {
            case "public" -> Modifier.PUBLIC;
            case "protected" -> Modifier.PROTECTED;
            case "private" -> Modifier.PRIVATE;
            default -> Modifier.NONE; // package-private
        };
    }

    // ===================== retarget (hand-rolled — no JDT equivalent) =====================

    private Preparation prepareRetarget(IJdtService service, IMethod method,
                                        String retargetCallsTo) throws Exception {
        String oldName = method.getElementName();
        boolean isConstructor = method.isConstructor();

        // Identity parameter list: retarget only renames the call target; arguments
        // are unchanged, so reuse the call-site rewriter with a no-op mapping.
        String[] oldParamNames = method.getParameterNames();
        String[] oldParamTypes = method.getParameterTypes();
        List<ParameterInfo> current = new ArrayList<>();
        for (int i = 0; i < oldParamNames.length; i++) {
            current.add(new ParameterInfo(oldParamNames[i], Signature.toString(oldParamTypes[i]), null));
        }
        int[] identity = buildParameterMapping(oldParamNames, current);

        List<SearchMatch> references = service.getSearchService().findReferences(
            method, IJavaSearchConstants.REFERENCES, 1000);

        Map<IFile, List<TextEdit>> editsByFile = new LinkedHashMap<>();
        for (SearchMatch match : references) {
            try {
                updateCallSite(match, oldName, retargetCallsTo, current, identity, isConstructor, editsByFile);
            } catch (Exception e) {
                log.debug("Error updating call site: {}", e.getMessage());
            }
        }

        int totalEdits = editsByFile.values().stream().mapToInt(List::size).sum();
        Change change = ChangeEngine.fromFileEdits("retarget calls of " + oldName, editsByFile);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("oldName", oldName);
        extras.put("newName", oldName);
        extras.put("retargetedTo", retargetCallsTo);
        extras.put("totalEdits", totalEdits);
        extras.put("filesAffected", editsByFile.size());

        String summary = "retarget calls of " + oldName + " -> " + retargetCallsTo
            + " (" + totalEdits + " edits in " + editsByFile.size() + " files)";
        return Preparation.of(change, summary, extras);
    }

    private int[] buildParameterMapping(String[] oldNames, List<ParameterInfo> newParams) {
        int[] mapping = new int[oldNames.length];
        for (int oldIdx = 0; oldIdx < oldNames.length; oldIdx++) {
            mapping[oldIdx] = -1;
            for (int newIdx = 0; newIdx < newParams.size(); newIdx++) {
                if (oldNames[oldIdx].equals(newParams.get(newIdx).name)) {
                    mapping[oldIdx] = newIdx;
                    break;
                }
            }
        }
        return mapping;
    }

    /** {@code {count, locations:[{filePath, offset}]}} of the method's references. */
    private Map<String, Object> referenceImpact(IJdtService service, List<SearchMatch> references) {
        List<Map<String, Object>> refLocs = new ArrayList<>();
        for (SearchMatch m : references) {
            org.eclipse.core.resources.IResource res = m.getResource();
            String rp = (res != null && res.getLocation() != null)
                ? service.getPathUtils().formatPath(res.getLocation().toOSString())
                : String.valueOf(m.getElement());
            Map<String, Object> loc = new LinkedHashMap<>();
            loc.put("filePath", rp);
            loc.put("offset", m.getOffset());
            refLocs.add(loc);
        }
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("count", references.size());
        impact.put("locations", refLocs);
        return impact;
    }

    @SuppressWarnings("unchecked")
    private void updateCallSite(SearchMatch match, String oldName, String newName,
                                List<ParameterInfo> newParams, int[] paramMapping,
                                boolean isConstructor,
                                Map<IFile, List<TextEdit>> editsByFile) throws JavaModelException {
        Object element = match.getElement();
        if (!(element instanceof IJavaElement javaElement)) {
            return;
        }
        ICompilationUnit cu = (ICompilationUnit) javaElement.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (cu == null) {
            return;
        }
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        final int matchOffset = match.getOffset();
        final ASTNode[] found = {null};
        ast.accept(new ASTVisitor() {
            private boolean covers(ASTNode node) {
                return node.getStartPosition() <= matchOffset
                    && matchOffset < node.getStartPosition() + node.getLength();
            }
            @Override
            public boolean visit(MethodInvocation node) {
                if (isConstructor || found[0] != null) return true;
                if ((node.getName().getStartPosition() == matchOffset || covers(node))
                        && oldName.equals(node.getName().getIdentifier())) {
                    found[0] = node;
                    return false;
                }
                return true;
            }
            @Override
            public boolean visit(ClassInstanceCreation node) {
                if (isConstructor && found[0] == null && covers(node)) {
                    found[0] = node;
                    return false;
                }
                return true;
            }
            @Override
            public boolean visit(ConstructorInvocation node) {
                if (isConstructor && found[0] == null && covers(node)) {
                    found[0] = node;
                    return false;
                }
                return true;
            }
            @Override
            public boolean visit(SuperConstructorInvocation node) {
                if (isConstructor && found[0] == null && covers(node)) {
                    found[0] = node;
                    return false;
                }
                return true;
            }
        });

        if (found[0] == null) {
            return;
        }
        ASTNode node = found[0];

        List<Expression> oldArgs;
        String prefix;
        if (node instanceof MethodInvocation mi) {
            oldArgs = mi.arguments();
            prefix = (mi.getExpression() != null ? mi.getExpression().toString() + "." : "")
                + newName + "(";
        } else if (node instanceof ClassInstanceCreation cic) {
            oldArgs = cic.arguments();
            prefix = (cic.getExpression() != null ? cic.getExpression().toString() + "." : "")
                + "new " + cic.getType().toString() + "(";
        } else if (node instanceof ConstructorInvocation ci) {
            oldArgs = ci.arguments();
            prefix = "this(";
        } else if (node instanceof SuperConstructorInvocation sci) {
            oldArgs = sci.arguments();
            prefix = (sci.getExpression() != null ? sci.getExpression().toString() + "." : "")
                + "super(";
        } else {
            return;
        }

        String newCall = prefix + String.join(", ", buildNewArgs(oldArgs, newParams, paramMapping)) + ")";
        if (cu.getResource() instanceof IFile callFile) {
            editsByFile.computeIfAbsent(callFile, k -> new ArrayList<>())
                .add(new ReplaceEdit(node.getStartPosition(), node.getLength(), newCall));
        }
    }

    private List<String> buildNewArgs(List<Expression> oldArgs, List<ParameterInfo> newParams,
                                      int[] paramMapping) {
        List<String> newArgs = new ArrayList<>();
        for (int newIdx = 0; newIdx < newParams.size(); newIdx++) {
            ParameterInfo newParam = newParams.get(newIdx);
            int oldIdx = -1;
            for (int i = 0; i < paramMapping.length; i++) {
                if (paramMapping[i] == newIdx) {
                    oldIdx = i;
                    break;
                }
            }
            if (oldIdx >= 0 && oldIdx < oldArgs.size()) {
                newArgs.add(oldArgs.get(oldIdx).toString());
            } else if (newParam.defaultValue != null) {
                newArgs.add(newParam.defaultValue);
            } else {
                newArgs.add("/* TODO " + newParam.name + " */ " + zeroValueFor(newParam.type));
            }
        }
        return newArgs;
    }

    /** The compiling zero value for a type — placeholder arguments must parse. */
    private static String zeroValueFor(String type) {
        return switch (type == null ? "" : type) {
            case "boolean" -> "false";
            case "byte", "short", "int" -> "0";
            case "long" -> "0L";
            case "char" -> "(char) 0";
            case "float" -> "0f";
            case "double" -> "0d";
            default -> "null";
        };
    }

    /** Count the leaf text edits across a change tree — reported as {@code totalEdits}. */
    private static int leafEditCount(Change change) {
        if (change instanceof CompositeChange composite) {
            int total = 0;
            for (Change child : composite.getChildren()) {
                total += leafEditCount(child);
            }
            return total;
        }
        if (change instanceof TextChange textChange) {
            TextEdit root = textChange.getEdit();
            return root == null ? 0 : countLeaves(root);
        }
        return 0;
    }

    private static int countLeaves(TextEdit edit) {
        if (!edit.hasChildren()) {
            return 1;
        }
        int total = 0;
        for (TextEdit child : edit.getChildren()) {
            total += countLeaves(child);
        }
        return total;
    }

    private boolean isValidJavaIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return !RESERVED_WORDS.contains(name);
    }

    // ============ hand-rolled REPORT fallback (JDT refused a coupled change) ============

    /**
     * The pre-JDT signature editor, kept as the fallback for a change JDT refuses
     * because it would break the declaring body / call sites (see the call site).
     * Builds the new declaration + rewrites call sites textually and applies under
     * {@link GateMode#REPORT}: the change lands and the introduced compiler errors
     * are named in the response for the caller to fix — the "change the signature,
     * find the body edits by the compile failures" workflow.
     */
    private Preparation prepareSignatureChangeFallback(IJdtService service, IMethod method,
                                                       String newName, String newReturnType,
                                                       List<ParameterInfo> newParameters,
                                                       String visibility) throws Exception {
        String oldName = method.getElementName();
        if (newName == null) {
            newName = oldName;
        }
        boolean isConstructor = method.isConstructor();
        String[] oldParamTypes = method.getParameterTypes();
        String[] oldParamNames = method.getParameterNames();
        String oldReturnType = isConstructor ? null : Signature.toString(method.getReturnType());
        if (isConstructor) {
            newReturnType = null;
        } else if (newReturnType == null) {
            newReturnType = oldReturnType;
        }
        if (newParameters == null) {
            newParameters = new ArrayList<>();
            for (int i = 0; i < oldParamTypes.length; i++) {
                newParameters.add(new ParameterInfo(
                    oldParamNames[i], Signature.toString(oldParamTypes[i]), null));
            }
        }
        int[] paramMapping = buildParameterMapping(oldParamNames, newParameters);
        List<SearchMatch> references = service.getSearchService().findReferences(
            method, IJavaSearchConstants.REFERENCES, 1000);

        Map<IFile, List<TextEdit>> editsByFile = new LinkedHashMap<>();
        ICompilationUnit methodCu = method.getCompilationUnit();
        if (methodCu == null) {
            return Preparation.fail(
                ToolResponse.invalidParameter("method", "Cannot access method source"));
        }
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(methodCu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        MethodDeclaration methodDecl = findMethodDeclaration(ast, method);
        if (methodDecl == null) {
            return Preparation.fail(
                ToolResponse.invalidParameter("method", "Cannot find method in AST"));
        }

        String baseSignature = buildMethodSignature(newName, newReturnType, newParameters);
        int sigStart = getSignatureStart(methodDecl);
        int sigEnd = getSignatureEnd(methodDecl);
        IFile methodFile = (IFile) methodCu.getResource();

        String signaturePrefix = "";
        Map<String, Object> visibilityImpact = null;
        if (visibility != null) {
            Modifier currentMod = findVisibilityModifier(methodDecl);
            String currentVis = currentMod == null ? "package" : currentMod.getKeyword().toString();
            if (!visibility.equals(currentVis)) {
                if (currentMod != null && "package".equals(visibility)) {
                    int s = currentMod.getStartPosition();
                    int e = s + currentMod.getLength();
                    String src = methodCu.getSource();
                    if (src != null && e < src.length() && src.charAt(e) == ' ') {
                        e++;
                    }
                    editsByFile.computeIfAbsent(methodFile, k -> new ArrayList<>())
                        .add(new ReplaceEdit(s, e - s, ""));
                } else if (currentMod != null) {
                    editsByFile.computeIfAbsent(methodFile, k -> new ArrayList<>())
                        .add(new ReplaceEdit(currentMod.getStartPosition(),
                            currentMod.getLength(), visibility));
                } else {
                    signaturePrefix = visibility + " ";
                }
            }
            visibilityImpact = referenceImpact(service, references);
        }

        String newSignature = signaturePrefix + baseSignature;
        editsByFile.computeIfAbsent(methodFile, k -> new ArrayList<>())
            .add(new ReplaceEdit(sigStart, sigEnd - sigStart, newSignature));

        for (SearchMatch match : references) {
            try {
                updateCallSite(match, oldName, newName, newParameters, paramMapping,
                    isConstructor, editsByFile);
            } catch (Exception e) {
                log.debug("Error updating call site: {}", e.getMessage());
            }
        }

        int totalEdits = editsByFile.values().stream().mapToInt(List::size).sum();
        Change change = ChangeEngine.fromFileEdits("change signature of " + oldName, editsByFile);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("oldName", oldName);
        extras.put("newName", newName);
        extras.put("oldReturnType", oldReturnType);
        extras.put("newReturnType", newReturnType);
        extras.put("oldParameterCount", oldParamTypes.length);
        extras.put("newParameterCount", newParameters.size());
        extras.put("newParameters", newParameters.stream()
            .map(p -> Map.of("name", p.name, "type", p.type)).toList());
        extras.put("totalEdits", totalEdits);
        extras.put("filesAffected", editsByFile.size());
        if (visibility != null) {
            extras.put("visibility", visibility);
            extras.put("referenceImpact", visibilityImpact);
        }
        // JDT refused this as a coupled change; the hand-rolled path applied it and the
        // compile-verify gate (REPORT mode) returns every introduced error as the
        // worklist. The marker lets an agent branch deterministically on "this one is
        // the change-the-signature-then-fix-by-the-compile-failures case".
        extras.put("coupledChange", true);

        String summary = "change signature of " + oldName + " -> " + newSignature
            + " (" + totalEdits + " edits in " + editsByFile.size() + " files)";
        return Preparation.of(change, summary, extras);
    }

    private String buildMethodSignature(String name, String returnType, List<ParameterInfo> params) {
        StringBuilder sig = new StringBuilder();
        // Constructors pass returnType == null — emit `ClassName(...)`, not `void ClassName(...)`.
        if (returnType != null && !returnType.isBlank()) {
            sig.append(returnType).append(" ");
        }
        sig.append(name).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) {
                sig.append(", ");
            }
            sig.append(params.get(i).type).append(" ").append(params.get(i).name);
        }
        sig.append(")");
        return sig.toString();
    }

    private int getSignatureStart(MethodDeclaration decl) {
        if (decl.getReturnType2() != null) {
            return decl.getReturnType2().getStartPosition();
        }
        return decl.getName().getStartPosition();
    }

    private int getSignatureEnd(MethodDeclaration decl) {
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = decl.parameters();
        if (!params.isEmpty()) {
            SingleVariableDeclaration lastParam = params.get(params.size() - 1);
            return lastParam.getStartPosition() + lastParam.getLength() + 1; // +1 for ')'
        }
        return decl.getName().getStartPosition() + decl.getName().getLength() + 2; // +2 for '()'
    }

    private MethodDeclaration findMethodDeclaration(CompilationUnit ast, IMethod method) {
        final MethodDeclaration[] result = {null};
        final String methodName = method.getElementName();
        try {
            ISourceRange nameRange = method.getNameRange();
            final int nameOffset = nameRange != null ? nameRange.getOffset() : -1;
            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration node) {
                    if (methodName.equals(node.getName().getIdentifier())) {
                        if (nameOffset >= 0 && node.getName().getStartPosition() == nameOffset) {
                            result[0] = node;
                            return false;
                        }
                        if (result[0] == null) {
                            result[0] = node;
                        }
                    }
                    return true;
                }
            });
        } catch (JavaModelException e) {
            log.debug("Error finding method: {}", e.getMessage());
        }
        return result[0];
    }

    /** The method's visibility {@link Modifier}, or {@code null} when package-private. */
    private static Modifier findVisibilityModifier(MethodDeclaration decl) {
        for (Object o : decl.modifiers()) {
            if (o instanceof Modifier m) {
                Modifier.ModifierKeyword kw = m.getKeyword();
                if (kw == Modifier.ModifierKeyword.PUBLIC_KEYWORD
                    || kw == Modifier.ModifierKeyword.PROTECTED_KEYWORD
                    || kw == Modifier.ModifierKeyword.PRIVATE_KEYWORD) {
                    return m;
                }
            }
        }
        return null;
    }

    /** The requested parameter list item (jawata form). */
    private static class ParameterInfo {
        final String name;
        final String type;
        final String defaultValue;

        ParameterInfo(String name, String type, String defaultValue) {
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
        }
    }
}
