package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.Statement;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Extract an expression into a local variable.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}.</p>
 */
public class ExtractVariableTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ExtractVariableTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ExtractVariableTool(Supplier<IJdtService> serviceSupplier,
                               RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "extract_variable";
    }

    @Override
    public String getDescription() {
        return """
            Extract an expression at the given position into a local variable.

            Applies the extraction directly (default) and returns
            { filesModified, diff, undoChangeId, summary }. Verify with
            compile_workspace; revert with undo_refactoring(undoChangeId).
            Pass auto_apply: false to stage instead — returns { changeId, diff }.

            USAGE: Select expression by providing start and end positions
            OUTPUT: Modified file + unified diff + undo handle

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
                "description", "Zero-based start line of expression"
            ),
            "startColumn", Map.of(
                "type", "integer",
                "description", "Zero-based start column of expression"
            ),
            "endLine", Map.of(
                "type", "integer",
                "description", "Zero-based end line of expression"
            ),
            "endColumn", Map.of(
                "type", "integer",
                "description", "Zero-based end column of expression"
            ),
            "variableName", Map.of(
                "type", "string",
                "description", "Name for the new variable (optional, will suggest if not provided)"
            )
        ));
        schema.put("required", List.of("filePath", "startLine", "startColumn", "endLine", "endColumn"));
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
        String variableName = getStringParam(arguments, "variableName");

        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
            return Preparation.fail(
                ToolResponse.invalidParameter("positions", "All positions must be >= 0"));
        }

        Path path = Path.of(filePath);
        ICompilationUnit cu = service.getCompilationUnit(path);
        if (cu == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePath));
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

        // Find the expression at this range
        NodeFinder finder = new NodeFinder(ast, startOffset, endOffset - startOffset);
        ASTNode coveredNode = finder.getCoveredNode();
        ASTNode coveringNode = finder.getCoveringNode();

        Expression expression = null;
        if (coveredNode instanceof Expression expr) {
            expression = expr;
        } else if (coveringNode instanceof Expression expr) {
            expression = expr;
        }

        if (expression == null) {
            return Preparation.fail(
                ToolResponse.invalidParameter("selection", "No extractable expression at selection"));
        }

        // Get the type of the expression
        ITypeBinding typeBinding = expression.resolveTypeBinding();
        String typeName = typeBinding != null ? typeBinding.getName() : "var";

        // Generate variable name if not provided
        if (variableName == null || variableName.isBlank()) {
            variableName = suggestVariableName(expression, typeBinding);
        }

        if (!isValidJavaIdentifier(variableName)) {
            return Preparation.fail(
                ToolResponse.invalidParameter("variableName", "Not a valid Java identifier"));
        }

        // Find the containing statement to insert before
        Statement containingStatement = findContainingStatement(expression);
        if (containingStatement == null) {
            return Preparation.fail(
                ToolResponse.invalidParameter("selection", "Cannot find containing statement"));
        }

        // Get expression text
        String expressionText = expression.toString();

        // Build the variable declaration
        String declaration = typeName + " " + variableName + " = " + expressionText + ";";

        // Calculate insertion point
        int insertOffset = containingStatement.getStartPosition();

        // Get indentation
        String indent = getIndentation(cu, containingStatement);

        // Build JDT edits: declaration inserted before the containing
        // statement, expression replaced by the variable name.
        List<TextEdit> edits = new ArrayList<>();
        edits.add(new InsertEdit(insertOffset, indent + declaration + "\n"));
        edits.add(new ReplaceEdit(
            expression.getStartPosition(), expression.getLength(), variableName));

        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits(
            "extract variable " + variableName, Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filePath", service.getPathUtils().formatPath(path));
        extras.put("variableName", variableName);
        extras.put("variableType", typeName);
        extras.put("expressionText", expressionText);
        extras.put("declaration", declaration);

        String summary = "extract variable " + typeName + " " + variableName
            + " = " + expressionText;
        return Preparation.of(change, summary, extras);
    }

    private Statement findContainingStatement(ASTNode node) {
        while (node != null) {
            if (node instanceof Statement stmt && !(node instanceof Block)) {
                return stmt;
            }
            node = node.getParent();
        }
        return null;
    }

    private String getIndentation(ICompilationUnit cu, ASTNode node) {
        try {
            String source = cu.getSource();
            if (source == null) return "        ";

            int nodeStart = node.getStartPosition();
            int lineStart = source.lastIndexOf('\n', nodeStart - 1) + 1;

            StringBuilder indent = new StringBuilder();
            for (int i = lineStart; i < nodeStart && i < source.length(); i++) {
                char c = source.charAt(i);
                if (c == ' ' || c == '\t') {
                    indent.append(c);
                } else {
                    break;
                }
            }
            return indent.toString();
        } catch (JavaModelException e) {
            log.debug("Error getting indentation: {}", e.getMessage());
            return "        ";
        }
    }

    private String suggestVariableName(Expression expression, ITypeBinding typeBinding) {
        String suggested = null;

        if (typeBinding != null) {
            String typeName = typeBinding.getName();
            if (typeName.length() > 0) {
                int genericIndex = typeName.indexOf('<');
                if (genericIndex > 0) {
                    typeName = typeName.substring(0, genericIndex);
                }
                suggested = Character.toLowerCase(typeName.charAt(0)) +
                       (typeName.length() > 1 ? typeName.substring(1) : "");
            }
        }

        if (suggested == null && expression instanceof MethodInvocation mi) {
            String methodName = mi.getName().getIdentifier();
            if (methodName.startsWith("get") && methodName.length() > 3) {
                suggested = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else {
                suggested = methodName + "Result";
            }
        }

        if (suggested == null && expression instanceof ClassInstanceCreation cic) {
            String typeName = cic.getType().toString();
            suggested = Character.toLowerCase(typeName.charAt(0)) +
                   (typeName.length() > 1 ? typeName.substring(1) : "");
        }

        if (suggested == null) {
            suggested = "extracted";
        }

        // Fall back to generic names if suggested name is a reserved word
        if (RESERVED_WORDS.contains(suggested)) {
            if (typeBinding != null && typeBinding.isPrimitive()) {
                return suggested + "Value";  // e.g., "intValue", "booleanValue"
            }
            return "value";
        }

        return suggested;
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
