package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.core.resources.IFile;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.RefactoringChangeCache;
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
 * Extract a code block into a new method.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}.</p>
 */
public class ExtractMethodTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ExtractMethodTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ExtractMethodTool(Supplier<IJdtService> serviceSupplier,
                             RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "extract_method";
    }

    @Override
    public String getDescription() {
        return """
            Extract a code block into a new method.

            Applies the extraction directly (default) and returns
            { filesModified, diff, undoChangeId, summary }. Verify with
            compile_workspace; revert with undo_refactoring(undoChangeId).
            Pass auto_apply: false to stage instead — returns { changeId, diff }.

            USAGE: Select code range, provide method name
            OUTPUT: Modified file + unified diff + undo handle

            The tool analyzes the selected code to:
            - Determine which variables become parameters
            - Determine return type based on variables modified
            - Generate appropriate method signature

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
                "description", "Path to source file"
            ),
            "startLine", Map.of(
                "type", "integer",
                "description", "Zero-based start line of code to extract"
            ),
            "startColumn", Map.of(
                "type", "integer",
                "description", "Zero-based start column"
            ),
            "endLine", Map.of(
                "type", "integer",
                "description", "Zero-based end line of code to extract"
            ),
            "endColumn", Map.of(
                "type", "integer",
                "description", "Zero-based end column"
            ),
            "methodName", Map.of(
                "type", "string",
                "description", "Name for the new method"
            )
        ));
        schema.put("required", List.of("filePath", "startLine", "startColumn", "endLine", "endColumn", "methodName"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required"));
        }

        int startLine = getIntParam(arguments, "startLine", -1);
        int startColumn = getIntParam(arguments, "startColumn", -1);
        int endLine = getIntParam(arguments, "endLine", -1);
        int endColumn = getIntParam(arguments, "endColumn", -1);
        String methodName = getStringParam(arguments, "methodName");

        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
            return Preparation.fail(
                ToolResponse.invalidParameter("positions", "All positions must be >= 0"));
        }

        if (methodName == null || methodName.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("methodName", "Required"));
        }

        if (!isValidJavaIdentifier(methodName)) {
            return Preparation.fail(
                ToolResponse.invalidParameter("methodName", "Not a valid Java identifier"));
        }

        Path path = Path.of(filePath);
        ICompilationUnit cu = service.getCompilationUnit(path);
        if (cu == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePath));
        }

        String source = cu.getSource();
        if (source == null) {
            return Preparation.fail(ToolResponse.internalError("Cannot read source"));
        }

        // Parse to AST with bindings
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        // Calculate offsets
        int startOffset = ast.getPosition(startLine + 1, startColumn);
        int endOffset = ast.getPosition(endLine + 1, endColumn);

        if (startOffset < 0 || endOffset < 0 || startOffset >= endOffset) {
            return Preparation.fail(
                ToolResponse.invalidParameter("positions", "Invalid selection range"));
        }

        // Find the containing method
        MethodDeclaration containingMethod = findContainingMethod(ast, startOffset);
        if (containingMethod == null) {
            return Preparation.fail(
                ToolResponse.invalidParameter("selection", "Selection must be inside a method body"));
        }

        // Find the containing type
        TypeDeclaration containingType = findContainingType(ast, startOffset);
        if (containingType == null) {
            return Preparation.fail(
                ToolResponse.invalidParameter("selection", "Cannot find containing type"));
        }

            // Get the selected source code
            String selectedCode = source.substring(startOffset, endOffset).trim();

            // Analyze variables
            VariableAnalysis analysis = analyzeVariables(ast, containingMethod, startOffset, endOffset);

            // Determine return type
            String returnType = "void";
            String returnStatement = "";
            List<String> returnVars = new ArrayList<>();

            if (!analysis.modifiedAndUsedAfter.isEmpty()) {
                if (analysis.modifiedAndUsedAfter.size() == 1) {
                    VariableInfo var = analysis.modifiedAndUsedAfter.get(0);
                    returnType = var.type;
                    returnStatement = "\n        return " + var.name + ";";
                    returnVars.add(var.name);
                }
            }

            // Build parameter list
            StringBuilder params = new StringBuilder();
            List<String> paramNames = new ArrayList<>();
            for (int i = 0; i < analysis.parameters.size(); i++) {
                if (i > 0) params.append(", ");
                VariableInfo param = analysis.parameters.get(i);
                params.append(param.type).append(" ").append(param.name);
                paramNames.add(param.name);
            }

            // Get indentation
            String methodIndent = getIndentation(source, containingMethod.getStartPosition());
            String bodyIndent = methodIndent + "    ";

            // Format the extracted code with proper indentation
            String formattedBody = formatExtractedBody(selectedCode, bodyIndent);

            // Build the new method
            StringBuilder newMethod = new StringBuilder();
            newMethod.append("\n\n").append(methodIndent);
            newMethod.append("private ").append(returnType).append(" ").append(methodName);
            newMethod.append("(").append(params).append(") {\n");
            newMethod.append(formattedBody);
            newMethod.append(returnStatement);
            newMethod.append("\n").append(methodIndent).append("}");

            // Build the method call
            StringBuilder methodCall = new StringBuilder();
            if (!returnType.equals("void") && !returnVars.isEmpty()) {
                methodCall.append(returnVars.get(0)).append(" = ");
            }
            methodCall.append(methodName).append("(");
            methodCall.append(String.join(", ", paramNames));
            methodCall.append(");");

        // Calculate insertion point for new method
        int insertOffset = containingMethod.getStartPosition() + containingMethod.getLength();

        // Build JDT edits: new method inserted after the containing method,
        // selection replaced by the call.
        List<TextEdit> edits = new ArrayList<>();
        edits.add(new InsertEdit(insertOffset, newMethod.toString()));
        edits.add(new ReplaceEdit(startOffset, endOffset - startOffset, methodCall.toString()));

        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits(
            "extract method " + methodName, Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filePath", service.getPathUtils().formatPath(path));
        extras.put("methodName", methodName);
        extras.put("returnType", returnType);
        extras.put("parameters", analysis.parameters.stream()
            .map(p -> Map.of("name", p.name, "type", p.type))
            .toList());
        extras.put("newMethodCode", newMethod.toString().trim());
        extras.put("methodCall", methodCall.toString());
        if (analysis.modifiedAndUsedAfter.size() > 1) {
            extras.put("warning", "Multiple variables are modified and used after selection. " +
                "Consider extracting smaller pieces or using a result object.");
        }

        String summary = "extract method private " + returnType + " " + methodName
            + "(" + params + ")";
        return Preparation.of(change, summary, extras);
    }

    private MethodDeclaration findContainingMethod(CompilationUnit ast, int offset) {
        MethodDeclaration[] result = new MethodDeclaration[1];
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                int start = node.getStartPosition();
                int end = start + node.getLength();
                if (offset >= start && offset <= end) {
                    result[0] = node;
                }
                return true;
            }
        });
        return result[0];
    }

    private TypeDeclaration findContainingType(CompilationUnit ast, int offset) {
        TypeDeclaration[] result = new TypeDeclaration[1];
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                int start = node.getStartPosition();
                int end = start + node.getLength();
                if (offset >= start && offset <= end) {
                    result[0] = node;
                }
                return true;
            }
        });
        return result[0];
    }

    private VariableAnalysis analyzeVariables(CompilationUnit ast, MethodDeclaration method,
                                              int selectionStart, int selectionEnd) {
        VariableAnalysis analysis = new VariableAnalysis();

        Set<String> declaredBefore = new HashSet<>();
        Set<String> usedInSelection = new HashSet<>();
        Set<String> declaredInSelection = new HashSet<>();
        Set<String> modifiedInSelection = new HashSet<>();
        Set<String> usedAfterSelection = new HashSet<>();
        Map<String, String> varTypes = new HashMap<>();

        // First pass: collect variable declarations
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                int pos = node.getStartPosition();
                String name = node.getName().getIdentifier();
                IVariableBinding binding = node.resolveBinding();
                String type = binding != null ? binding.getType().getName() : "Object";
                varTypes.put(name, type);

                if (pos < selectionStart) {
                    declaredBefore.add(name);
                } else if (pos >= selectionStart && pos <= selectionEnd) {
                    declaredInSelection.add(name);
                }
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration node) {
                int pos = node.getStartPosition();
                String name = node.getName().getIdentifier();
                String type = node.getType().toString();
                varTypes.put(name, type);

                if (pos < selectionStart) {
                    declaredBefore.add(name);
                }
                return true;
            }
        });

        // Second pass: find usage and modification
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                int pos = node.getStartPosition();
                String name = node.getIdentifier();

                if (node.getParent() instanceof VariableDeclarationFragment vdf && vdf.getName() == node) {
                    return true;
                }

                boolean inSelection = pos >= selectionStart && pos <= selectionEnd;
                boolean afterSelection = pos > selectionEnd;

                if (inSelection) {
                    usedInSelection.add(name);
                    if (isAssignmentTarget(node)) {
                        modifiedInSelection.add(name);
                    }
                }

                if (afterSelection) {
                    usedAfterSelection.add(name);
                }

                return true;
            }
        });

        // Variables declared before and used in selection -> parameters
        for (String var : usedInSelection) {
            if (declaredBefore.contains(var) && !declaredInSelection.contains(var)) {
                analysis.parameters.add(new VariableInfo(var, varTypes.getOrDefault(var, "Object")));
            }
        }

        // Variables modified in selection and used after -> return values
        for (String var : modifiedInSelection) {
            if (usedAfterSelection.contains(var)) {
                analysis.modifiedAndUsedAfter.add(new VariableInfo(var, varTypes.getOrDefault(var, "Object")));
            }
        }

        return analysis;
    }

    private boolean isAssignmentTarget(SimpleName node) {
        var parent = node.getParent();
        if (parent instanceof Assignment assign) {
            return assign.getLeftHandSide() == node;
        }
        return parent instanceof PostfixExpression || parent instanceof PrefixExpression;
    }

    private String getIndentation(String source, int offset) {
        int lineStart = source.lastIndexOf('\n', offset - 1) + 1;
        StringBuilder indent = new StringBuilder();
        for (int i = lineStart; i < offset && i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == ' ' || c == '\t') {
                indent.append(c);
            } else {
                break;
            }
        }
        return indent.toString();
    }

    private String formatExtractedBody(String code, String indent) {
        String[] lines = code.split("\n");
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            result.append(indent).append(line.trim()).append("\n");
        }
        return result.toString().stripTrailing();
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

    private static class VariableAnalysis {
        List<VariableInfo> parameters = new ArrayList<>();
        List<VariableInfo> modifiedAndUsedAfter = new ArrayList<>();
    }

    private static class VariableInfo {
        String name;
        String type;

        VariableInfo(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }
}
