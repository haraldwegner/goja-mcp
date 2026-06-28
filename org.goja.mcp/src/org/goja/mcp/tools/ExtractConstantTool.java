package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.TypeDeclaration;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Extract an expression into a static final constant.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}.</p>
 */
public class ExtractConstantTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ExtractConstantTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ExtractConstantTool(Supplier<IJdtService> serviceSupplier,
                               RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "extract_constant";
    }

    @Override
    public String getDescription() {
        return """
            Extract an expression into a static final constant at class level.

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
            "constantName", Map.of(
                "type", "string",
                "description", "Name for the constant (should be UPPER_SNAKE_CASE)"
            )
        ));
        schema.put("required", List.of("filePath", "startLine", "startColumn", "endLine", "endColumn", "constantName"));
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
        String constantName = getStringParam(arguments, "constantName");

        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
            return Preparation.fail(
                ToolResponse.invalidParameter("positions", "All positions must be >= 0"));
        }

        if (constantName == null || constantName.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("constantName", "Required"));
        }

        if (!isValidConstantName(constantName)) {
            return Preparation.fail(
                ToolResponse.invalidParameter("constantName", "Not a valid Java identifier"));
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
        String typeName = typeBinding != null ? typeBinding.getName() : "Object";

        // Handle primitive types - use wrapper class name for clarity
        if (typeBinding != null && typeBinding.isPrimitive()) {
            typeName = typeBinding.getName();
        }

        // Find the containing type declaration
        AbstractTypeDeclaration containingType = findContainingType(expression);
        if (containingType == null) {
            return Preparation.fail(
                ToolResponse.invalidParameter("selection", "Cannot find containing type"));
        }

        // Get expression text
        String expressionText = expression.toString();

        // Build the constant declaration
        String declaration = "private static final " + typeName + " " + constantName + " = " + expressionText + ";";

        // Find insertion point - after existing constants/fields or at the beginning
        int insertOffset = findConstantInsertionPoint(containingType, cu);

        // Get indentation
        String indent = getIndentation(cu, containingType);
        String memberIndent = indent + "    ";

        // Build JDT edits: constant declaration inserted at class level,
        // expression replaced by the constant name.
        List<TextEdit> edits = new ArrayList<>();
        edits.add(new InsertEdit(insertOffset, memberIndent + declaration + "\n\n"));
        edits.add(new ReplaceEdit(
            expression.getStartPosition(), expression.getLength(), constantName));

        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits(
            "extract constant " + constantName, Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filePath", service.getPathUtils().formatPath(path));
        extras.put("constantName", constantName);
        extras.put("constantType", typeName);
        extras.put("expressionText", expressionText);
        extras.put("declaration", declaration);
        extras.put("containingType", containingType.getName().getIdentifier());

        String summary = "extract constant " + declaration;
        return Preparation.of(change, summary, extras);
    }

    private AbstractTypeDeclaration findContainingType(ASTNode node) {
        while (node != null) {
            if (node instanceof AbstractTypeDeclaration type) {
                return type;
            }
            node = node.getParent();
        }
        return null;
    }

    private int findConstantInsertionPoint(AbstractTypeDeclaration type, ICompilationUnit cu) {
        // Try to insert after existing static final fields, or at the beginning of the class body
        if (type instanceof TypeDeclaration td) {
            @SuppressWarnings("unchecked")
            List<BodyDeclaration> bodyDecls = td.bodyDeclarations();

            int lastConstantEnd = -1;
            for (BodyDeclaration decl : bodyDecls) {
                if (decl instanceof FieldDeclaration fd) {
                    int modifiers = fd.getModifiers();
                    // Check if it's a static final field (constant)
                    if ((modifiers & org.eclipse.jdt.core.dom.Modifier.STATIC) != 0 &&
                        (modifiers & org.eclipse.jdt.core.dom.Modifier.FINAL) != 0) {
                        lastConstantEnd = fd.getStartPosition() + fd.getLength();
                    }
                }
            }

            if (lastConstantEnd > 0) {
                return lastConstantEnd + 1; // Insert after last constant
            }

            // No existing constants - insert at the beginning of the type body
            // Find the opening brace
            try {
                String source = cu.getSource();
                if (source != null) {
                    int typeStart = type.getStartPosition();
                    int bracePos = source.indexOf('{', typeStart);
                    if (bracePos > 0) {
                        return bracePos + 1; // Insert after opening brace
                    }
                }
            } catch (JavaModelException e) {
                log.debug("Error finding insertion point: {}", e.getMessage());
            }
        }

        // Fallback: insert at the beginning of type body
        return type.getStartPosition() + type.getLength();
    }

    private String getIndentation(ICompilationUnit cu, ASTNode node) {
        try {
            String source = cu.getSource();
            if (source == null) return "";

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
            return "    ";
        }
    }

    private boolean isValidConstantName(String name) {
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
        return !RESERVED_WORDS.contains(name.toLowerCase());
    }
}
