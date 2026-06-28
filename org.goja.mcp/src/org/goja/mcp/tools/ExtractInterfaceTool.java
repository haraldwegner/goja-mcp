package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.core.resources.IFile;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.TextEdit;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.CreateFileChange;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Extract an interface from a class containing selected public methods.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool} — creates the interface file AND
 * adds the implements clause in one Change; undo removes both.</p>
 */
public class ExtractInterfaceTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ExtractInterfaceTool.class);

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
            Extract an interface from a class containing selected public methods.

            Applies the extraction directly (default): creates the interface
            file next to the class and adds the implements clause, returning
            { filesModified, diff, undoChangeId, summary }. Undo removes the
            new file and reverts the class. Pass auto_apply: false to stage.

            USAGE: Position on class, provide interface name, optionally specify methods
            OUTPUT: New interface file + modified class + undo handle

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

        // Get optional method names
        List<String> methodNamesToInclude = new ArrayList<>();
        if (arguments.has("methodNames") && arguments.get("methodNames").isArray()) {
            for (JsonNode nameNode : arguments.get("methodNames")) {
                methodNamesToInclude.add(nameNode.asText());
            }
        }

        {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return Preparation.fail(ToolResponse.fileNotFound(filePath));
            }

            // Get the type at position
            IType type = service.getTypeAtPosition(path, line, column);
            if (type == null) {
                return Preparation.fail(ToolResponse.symbolNotFound("No class found at position"));
            }

            // Verify it's a class (not interface or enum)
            if (type.isInterface()) {
                return Preparation.fail(
                    ToolResponse.invalidParameter("type", "Cannot extract interface from an interface"));
            }
            if (type.isEnum()) {
                return Preparation.fail(
                    ToolResponse.invalidParameter("type", "Cannot extract interface from an enum"));
            }

            // Collect public non-static methods
            List<IMethod> methodsToExtract = new ArrayList<>();
            for (IMethod method : type.getMethods()) {
                int flags = method.getFlags();
                // Skip constructors, static methods, and non-public methods
                if (method.isConstructor()) continue;
                if (Flags.isStatic(flags)) continue;
                if (!Flags.isPublic(flags)) continue;

                // If specific methods are requested, filter by name
                if (!methodNamesToInclude.isEmpty()) {
                    if (!methodNamesToInclude.contains(method.getElementName())) {
                        continue;
                    }
                }

                methodsToExtract.add(method);
            }

            if (methodsToExtract.isEmpty()) {
                return Preparation.fail(ToolResponse.invalidParameter("methods",
                    "No eligible public methods found to extract"));
            }

            // Get package name
            String packageName = type.getPackageFragment().getElementName();

            // Build interface content
            StringBuilder interfaceContent = new StringBuilder();

            // Package declaration
            if (!packageName.isEmpty()) {
                interfaceContent.append("package ").append(packageName).append(";\n\n");
            }

            // Interface declaration
            interfaceContent.append("public interface ").append(interfaceName).append(" {\n\n");

            // Method signatures
            List<Map<String, Object>> extractedMethods = new ArrayList<>();
            for (IMethod method : methodsToExtract) {
                String signature = buildMethodSignature(method);
                interfaceContent.append("    ").append(signature).append(";\n\n");

                Map<String, Object> methodInfo = new LinkedHashMap<>();
                methodInfo.put("name", method.getElementName());
                methodInfo.put("signature", signature);
                extractedMethods.add(methodInfo);
            }

            interfaceContent.append("}\n");

            // Parse AST to find position for 'implements' clause
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);

            // Find the type declaration in AST
            TypeDeclaration typeDecl = findTypeDeclaration(ast, type.getElementName());

            // Build the implements-clause edit
            List<TextEdit> classEdits = new ArrayList<>();

            if (typeDecl != null) {
                // Check if class already has implements clause
                @SuppressWarnings("unchecked")
                List<?> existingInterfaces = typeDecl.superInterfaceTypes();
                boolean hasImplements = !existingInterfaces.isEmpty();

                String source = cu.getSource();
                if (source != null) {
                    // Find position to insert implements clause
                    // This is after class name and type parameters, before '{'

                    int classBodyStart = findClassBodyStart(source, typeDecl);
                    if (classBodyStart > 0) {
                        if (hasImplements) {
                            // Add to the existing implements list, after the last interface
                            int insertPos = findLastImplementsPosition(source, typeDecl);
                            classEdits.add(new InsertEdit(insertPos, ", " + interfaceName));
                        } else {
                            // Add new implements clause before '{'
                            classEdits.add(new InsertEdit(classBodyStart, " implements " + interfaceName));
                        }
                    }
                }
            }

            // Determine interface file path (next to the class file)
            String interfaceFileName = interfaceName + ".java";
            Path interfacePath;
            if (path.getParent() != null) {
                interfacePath = path.getParent().resolve(interfaceFileName);
            } else {
                interfacePath = Path.of(interfaceFileName);
            }

            IFile classFile = (IFile) cu.getResource();
            IFile interfaceFile = classFile.getParent()
                .getFile(new org.eclipse.core.runtime.Path(interfaceFileName));
            if (interfaceFile.exists()) {
                return Preparation.fail(ToolResponse.invalidParameter("interfaceName",
                    "File already exists: " + interfaceFile.getFullPath()));
            }

            CompositeChange change = new CompositeChange("extract interface " + interfaceName);
            change.add(new CreateFileChange(interfaceFile, interfaceContent.toString()));
            if (!classEdits.isEmpty()) {
                change.add(ChangeEngine.fromFileEdits(
                    "implements " + interfaceName, Map.of(classFile, classEdits)));
            }

            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("className", type.getElementName());
            extras.put("interfaceName", interfaceName);
            extras.put("packageName", packageName);
            extras.put("interfaceFilePath", service.getPathUtils().formatPath(interfacePath));
            extras.put("interfaceContent", interfaceContent.toString());
            extras.put("extractedMethods", extractedMethods);
            extras.put("sourceFilePath", service.getPathUtils().formatPath(path));

            String summary = "extract interface " + interfaceName + " from "
                + type.getElementName() + " (" + extractedMethods.size() + " methods)";
            return Preparation.of((Change) change, summary, extras);
        }
    }

    private String buildMethodSignature(IMethod method) throws JavaModelException {
        StringBuilder sig = new StringBuilder();

        // Return type
        String returnType = Signature.toString(method.getReturnType());
        sig.append(returnType).append(" ");

        // Method name
        sig.append(method.getElementName());

        // Parameters
        sig.append("(");
        String[] paramTypes = method.getParameterTypes();
        String[] paramNames = method.getParameterNames();

        for (int i = 0; i < paramTypes.length; i++) {
            if (i > 0) sig.append(", ");
            sig.append(Signature.toString(paramTypes[i]));
            sig.append(" ");
            sig.append(paramNames[i]);
        }
        sig.append(")");

        // Exceptions
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

    private TypeDeclaration findTypeDeclaration(CompilationUnit ast, String typeName) {
        @SuppressWarnings("unchecked")
        List<?> types = ast.types();
        for (Object t : types) {
            if (t instanceof TypeDeclaration td) {
                if (typeName.equals(td.getName().getIdentifier())) {
                    return td;
                }
            }
        }
        return null;
    }

    private int findClassBodyStart(String source, TypeDeclaration typeDecl) {
        int start = typeDecl.getStartPosition();
        int end = start + typeDecl.getLength();

        // Find the opening brace
        for (int i = start; i < end && i < source.length(); i++) {
            if (source.charAt(i) == '{') {
                // Return position just before the brace
                return i;
            }
        }
        return -1;
    }

    private int findLastImplementsPosition(String source, TypeDeclaration typeDecl) {
        @SuppressWarnings("unchecked")
        List<?> interfaces = typeDecl.superInterfaceTypes();
        if (interfaces.isEmpty()) {
            return -1;
        }

        // Get the last interface
        Object lastInterface = interfaces.get(interfaces.size() - 1);
        if (lastInterface instanceof org.eclipse.jdt.core.dom.Type t) {
            return t.getStartPosition() + t.getLength();
        }
        return -1;
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
