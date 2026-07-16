package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.structure.ExtractInterfaceProcessor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.RefactoringEngine;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Extract an interface from a class containing selected public methods.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool} — creates the interface file AND
 * adds the implements clause in one Change; undo removes both.</p>
 *
 * <p>Sprint 25 (spec D1a item 6): the extraction is computed by JDT's own
 * {@link ExtractInterfaceProcessor} (the IDE's Refactor → Extract Interface),
 * driven through the {@link RefactoringEngine} seam. The original implementation
 * string-built the interface file (wholesale-copying every import from the
 * source file) and inserted the implements clause via a raw brace scan; it never
 * touched any other file. The JDT processor generates the interface with a
 * proper import rewrite, adds the implements clause through the AST, and — the
 * new capability — rewrites type occurrences across the workspace to USE the
 * extracted interface where type-safe (its supertype-constraints solver
 * decides). The v2.12.1 compile-verify gate stays wrapped around the applied
 * change.</p>
 */
public class ExtractInterfaceTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ExtractInterfaceTool.class);

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ExtractInterfaceTool(Supplier<IJdtService> serviceSupplier,
                                RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "extract_interface";
    }

    @Override
    public String getDescription() {
        return """
            Extract an interface from a class containing selected public methods
            (JDT's own Extract Interface engine).

            Applies the extraction directly (default): creates the interface file,
            adds the implements clause, and rewrites compatible type occurrences to
            USE the new interface where type-safe. Returns
            { filesModified, diff, undoChangeId, summary }. Undo removes the new
            file and reverts every touched file. Pass auto_apply: false to stage.

            USAGE: Position on class, provide interface name, optionally specify methods
            OUTPUT: New interface file + modified class (+ retyped use sites) + undo handle

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of(
                "type", "string",
                "description", "Path to source file containing the class"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number of class declaration"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
            ),
            "interfaceName", Map.of(
                "type", "string",
                "description", "Name for the new interface"
            ),
            "methodNames", Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                "description", "Specific method names to include (default: all public non-static methods)"
            )
        ));
        schema.put("required", List.of("filePath", "line", "column", "interfaceName"));
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
        String interfaceName = getStringParam(arguments, "interfaceName");

        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column", "Must be >= 0"));
        }
        if (interfaceName == null || interfaceName.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("interfaceName", "Required"));
        }
        if (!isValidJavaIdentifier(interfaceName)) {
            return Preparation.fail(
                ToolResponse.invalidParameter("interfaceName", "Not a valid Java identifier"));
        }

        List<String> methodNamesToInclude = new ArrayList<>();
        if (arguments.has("methodNames") && arguments.get("methodNames").isArray()) {
            for (JsonNode nameNode : arguments.get("methodNames")) {
                methodNamesToInclude.add(nameNode.asText());
            }
        }

        Path path = Path.of(filePath);
        ICompilationUnit cu = service.getCompilationUnit(path);
        if (cu == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePath));
        }
        IType type = service.getTypeAtPosition(path, line, column);
        if (type == null) {
            return Preparation.fail(ToolResponse.symbolNotFound("No class found at position"));
        }
        if (type.isInterface()) {
            return Preparation.fail(
                ToolResponse.invalidParameter("type", "Cannot extract interface from an interface"));
        }
        if (type.isEnum()) {
            return Preparation.fail(
                ToolResponse.invalidParameter("type", "Cannot extract interface from an enum"));
        }

        // Collect the members to extract: public non-static methods, optionally
        // filtered by name. By DEFAULT, methods that override a supertype's
        // (toString from Object, compareTo from Comparable, ...) are excluded —
        // they are the supertype's contract, not this class's, and the JDT
        // processor rewrites parameter types inside the generated interface,
        // which turns an extracted override into an unimplemented abstract
        // method (caught live by the compile gate). An explicit methodNames
        // selection overrides the exclusion.
        // MethodOverrideTester substitutes type variables (Comparable<Foo>#compareTo(T)
        // matches compareTo(Foo)); IType.findMethods compares raw simple names and misses
        // generic-supertype overrides.
        org.eclipse.jdt.internal.corext.util.MethodOverrideTester overrideTester =
            methodNamesToInclude.isEmpty()
                ? new org.eclipse.jdt.internal.corext.util.MethodOverrideTester(
                    type, type.newSupertypeHierarchy(null))
                : null;
        List<IMethod> methodsToExtract = new ArrayList<>();
        for (IMethod method : type.getMethods()) {
            int flags = method.getFlags();
            if (method.isConstructor()) continue;
            if (Flags.isStatic(flags)) continue;
            if (!Flags.isPublic(flags)) continue;
            if (!methodNamesToInclude.isEmpty()) {
                if (!methodNamesToInclude.contains(method.getElementName())) {
                    continue;
                }
            } else if (overrideTester.findOverriddenMethod(method, false) != null) {
                continue;
            }
            methodsToExtract.add(method);
        }
        if (methodsToExtract.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("methods",
                "No eligible public methods found to extract"));
        }

        String packageName = type.getPackageFragment().getElementName();
        IFile classFile = (IFile) cu.getResource();
        IFile interfaceFile = classFile.getParent()
            .getFile(new org.eclipse.core.runtime.Path(interfaceName + ".java"));
        if (interfaceFile.exists()) {
            return Preparation.fail(ToolResponse.invalidParameter("interfaceName",
                "File already exists: " + interfaceFile.getFullPath()));
        }

        HeadlessJdtConfig.ensureInitialized();
        CodeGenerationSettings settings = new CodeGenerationSettings();
        ExtractInterfaceProcessor processor = new ExtractInterfaceProcessor(type, settings);
        processor.setTypeName(interfaceName);
        processor.setExtractedMembers(methodsToExtract.toArray(new IMember[0]));
        processor.setComments(true);
        // Use-where-possible: retype compatible occurrences to the new interface
        // (the supertype-constraints solver decides type-safety per site).
        processor.setReplace(true);

        ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
        CheckedChange checked = engine.propose(refactoring, "extract interface " + interfaceName);
        if (checked.isRefused()) {
            return Preparation.fail(ToolResponse.error(
                "EXTRACT_REFUSED",
                "extract_interface refused: " + checked.messages(),
                "JDT's Extract Interface engine rejected it — a name collision or an "
                    + "unextractable member. Adjust the input and retry. No files were "
                    + "modified."));
        }

        Change change = checked.change();

        List<Map<String, Object>> extractedMethods = new ArrayList<>();
        for (IMethod method : methodsToExtract) {
            Map<String, Object> methodInfo = new LinkedHashMap<>();
            methodInfo.put("name", method.getElementName());
            methodInfo.put("signature", buildMethodSignature(method));
            extractedMethods.add(methodInfo);
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("className", type.getElementName());
        extras.put("interfaceName", interfaceName);
        extras.put("packageName", packageName);
        extras.put("interfaceFilePath", service.getPathUtils().formatPath(
            path.getParent() != null
                ? path.getParent().resolve(interfaceName + ".java")
                : Path.of(interfaceName + ".java")));
        extras.put("extractedMethods", extractedMethods);
        extras.put("sourceFilePath", service.getPathUtils().formatPath(path));
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }

        String summary = "extract interface " + interfaceName + " from "
            + type.getElementName() + " (" + extractedMethods.size() + " methods)";
        log.debug("extract_interface via JDT ExtractInterfaceProcessor: {}", summary);
        return Preparation.of(change, summary, extras);
    }

    /** Human-readable signature for the response's extractedMethods list. */
    private String buildMethodSignature(IMethod method) throws JavaModelException {
        StringBuilder sig = new StringBuilder();
        sig.append(Signature.toString(method.getReturnType())).append(" ");
        sig.append(method.getElementName()).append("(");
        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();
        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(Signature.toString(paramTypes[i])).append(" ").append(paramNames[i]);
        }
        sig.append(")");
        String[] exceptions = method.getExceptionTypes();
        if (exceptions.length > 0) {
            sig.append(" throws ");
            for (int i = 0; i < exceptions.length; i++) {
                if (i > 0) sig.append(", ");
                sig.append(Signature.toString(exceptions[i]));
            }
        }
        return sig.toString();
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
}
