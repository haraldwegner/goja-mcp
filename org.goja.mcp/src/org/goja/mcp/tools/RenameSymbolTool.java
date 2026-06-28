package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.ltk.core.refactoring.Change;
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
 * Rename a symbol across the project.
 *
 * <p>Sprint 14b: auto-applies by default (contract base
 * {@link AbstractApplyingRefactoringTool}) — returns
 * {@code { filesModified, diff, undoChangeId, summary }}; with
 * {@code auto_apply: false} stages the change and returns
 * {@code { changeId, diff, summary }}.</p>
 */
public class RenameSymbolTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(RenameSymbolTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null", "var", "yield",
        "record", "sealed", "permits", "non-sealed"
    );

    public RenameSymbolTool(Supplier<IJdtService> serviceSupplier,
                            RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "rename_symbol";
    }

    @Override
    public String getDescription() {
        return """
            Rename a symbol (variable, method, field, class, etc.) across the project.

            Applies the rename directly (default) and returns
            { filesModified, diff, undoChangeId, summary }. Verify with
            compile_workspace; revert with undo_refactoring(undoChangeId) if needed.
            Pass auto_apply: false to stage instead — returns { changeId, diff } for
            inspect_refactoring / apply_refactoring.

            USAGE: Position on symbol, provide new name
            OUTPUT: Modified files + unified diff + undo handle

            IMPORTANT: Uses ZERO-BASED coordinates.
            NOTE: Renaming a public top-level class does NOT rename its file —
            compile_workspace will flag the mismatch; use move_class or undo.

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
                "description", "Path to source file containing the symbol"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
            ),
            "newName", Map.of(
                "type", "string",
                "description", "New name for the symbol"
            )
        ));
        schema.put("required", List.of("filePath", "line", "column", "newName"));
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
        String newName = getStringParam(arguments, "newName");

        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column", "Must be >= 0"));
        }

        if (newName == null || newName.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("newName", "Required"));
        }

        if (!isValidJavaIdentifier(newName)) {
            return Preparation.fail(
                ToolResponse.invalidParameter("newName", "Not a valid Java identifier"));
        }

        Path path = Path.of(filePath);

        // Use getElementAtPosition - the same reliable approach other tools use
        IJavaElement element = service.getElementAtPosition(path, line, column);
        if (element == null) {
            return Preparation.fail(ToolResponse.symbolNotFound("No symbol at position"));
        }

        String oldName = element.getElementName();
        String symbolKind = getElementKind(element);

        if (oldName.equals(newName)) {
            return Preparation.fail(
                ToolResponse.invalidParameter("newName", "Same as current name"));
        }

        // Get the binding key by parsing the AST at the element's location
        ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (cu == null) {
            return Preparation.fail(
                ToolResponse.symbolNotFound("Cannot find compilation unit for element"));
        }

        // Parse AST to get binding key
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        // Find the binding key using the element's source range
        String targetKey = findBindingKey(element, ast, oldName);
        if (targetKey == null) {
            // Fallback to handle identifier matching
            targetKey = element.getHandleIdentifier();
            log.debug("Using handle identifier as fallback: {}", targetKey);
        }

        // Sprint 14 (bugs.md #13): when renaming a TYPE, constructor
        // declarations bear the old type name as their SimpleName but
        // resolve to a constructor (IMethodBinding), not the type
        // binding. The standard rename walker (binding-key match against
        // the renamed symbol) therefore misses them. We pass this flag
        // into findRenameEdits to enable a second match branch that
        // emits an edit when the SimpleName resolves to a constructor
        // whose declaring class matches the renamed type.
        boolean renamingAType = (element instanceof IType);

        // Find all references across the project; collect real JDT edits
        // per IFile so the contract base can apply them with undo support.
        Map<IFile, List<TextEdit>> editsByFile = new LinkedHashMap<>();
        int totalEdits = 0;

        for (Path sourceFile : service.getAllJavaFiles()) {
            try {
                ICompilationUnit sourceCu = service.getCompilationUnit(sourceFile);
                if (sourceCu == null) continue;

                ASTParser sourceParser = ASTParser.newParser(AST.getJLSLatest());
                sourceParser.setSource(sourceCu);
                sourceParser.setResolveBindings(true);
                sourceParser.setBindingsRecovery(true);
                CompilationUnit sourceAst = (CompilationUnit) sourceParser.createAST(null);

                List<TextEdit> fileEdits = findRenameEdits(
                    sourceAst, targetKey, oldName, newName, renamingAType
                );

                if (!fileEdits.isEmpty() && sourceCu.getResource() instanceof IFile file) {
                    editsByFile.put(file, fileEdits);
                    totalEdits += fileEdits.size();
                }
            } catch (Exception e) {
                log.debug("Error finding rename edits in file: {}", e.getMessage());
            }
        }

        if (editsByFile.isEmpty()) {
            return Preparation.fail(ToolResponse.symbolNotFound(
                "No occurrences of '" + oldName + "' resolved for renaming"));
        }

        String summary = "rename " + symbolKind + " '" + oldName + "' -> '" + newName
            + "' (" + totalEdits + " occurrences in " + editsByFile.size() + " files)";
        Change change = ChangeEngine.fromFileEdits("rename " + oldName, editsByFile);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("oldName", oldName);
        extras.put("newName", newName);
        extras.put("symbolKind", symbolKind);
        extras.put("totalEdits", totalEdits);
        extras.put("filesAffected", editsByFile.size());
        if (element instanceof IType) {
            extras.put("note", "Renaming a type does not rename its file — verify with compile_workspace");
        }
        return Preparation.of(change, summary, extras);
    }

    private List<TextEdit> findRenameEdits(CompilationUnit ast, String targetKey,
                                           String oldName, String newName,
                                           boolean renamingAType) {
        List<TextEdit> edits = new ArrayList<>();

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (!oldName.equals(node.getIdentifier())) {
                    return true;
                }

                IBinding nodeBinding = node.resolveBinding();
                if (nodeBinding == null) {
                    return true;
                }

                boolean matches = targetKey.equals(nodeBinding.getKey());

                // Sprint 14 (bugs.md #13): constructor declaration post-pass.
                // When renaming a type, constructor SimpleNames resolve to the
                // constructor (an IMethodBinding), NOT to the type binding —
                // so the targetKey match above misses them. Match here when:
                //   - we're renaming a type, AND
                //   - the resolved binding is a constructor, AND
                //   - the constructor's declaring class matches the renamed type.
                if (!matches && renamingAType
                        && nodeBinding instanceof IMethodBinding methodBinding
                        && methodBinding.isConstructor()) {
                    ITypeBinding declaringClass = methodBinding.getDeclaringClass();
                    if (declaringClass != null
                            && targetKey.equals(declaringClass.getKey())) {
                        matches = true;
                    }
                }

                if (matches) {
                    edits.add(new ReplaceEdit(node.getStartPosition(), node.getLength(), newName));
                }
                return true;
            }
        });

        return edits;
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

    private String getElementKind(IJavaElement element) {
        if (element instanceof IType type) {
            try {
                if (type.isInterface()) return "Interface";
                if (type.isEnum()) return "Enum";
                if (type.isAnnotation()) return "Annotation";
            } catch (JavaModelException e) {
                log.debug("Error checking type kind: {}", e.getMessage());
            }
            return "Class";
        }
        if (element instanceof IMethod method) {
            try {
                return method.isConstructor() ? "Constructor" : "Method";
            } catch (JavaModelException e) {
                return "Method";
            }
        }
        if (element instanceof IField) return "Field";
        if (element instanceof ILocalVariable) return "LocalVariable";
        if (element instanceof ITypeParameter) return "TypeParameter";
        return "Unknown";
    }

    private String findBindingKey(IJavaElement element, CompilationUnit ast, String name) {
        try {
            ISourceRange range = null;
            if (element instanceof IMember member) {
                range = member.getNameRange();
                if (range == null) {
                    range = member.getSourceRange();
                }
            } else if (element instanceof ILocalVariable local) {
                range = local.getNameRange();
            }

            if (range == null || range.getOffset() < 0) {
                return null;
            }

            // Find SimpleName at the element's name location
            final int offset = range.getOffset();
            final String[] foundKey = {null};

            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    if (node.getStartPosition() == offset && name.equals(node.getIdentifier())) {
                        IBinding binding = node.resolveBinding();
                        if (binding != null) {
                            foundKey[0] = binding.getKey();
                        }
                        return false;  // Stop visiting
                    }
                    return true;
                }
            });

            return foundKey[0];
        } catch (JavaModelException e) {
            log.debug("Error finding binding key: {}", e.getMessage());
            return null;
        }
    }
}
