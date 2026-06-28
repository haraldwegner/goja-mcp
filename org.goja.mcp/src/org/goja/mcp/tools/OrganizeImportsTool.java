package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.core.resources.IFile;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Organize imports in a Java file.
 * Removes unused imports and sorts remaining imports.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}. A file whose imports are already
 * organized short-circuits with {@code hasChanges: false} and no edit.</p>
 */
public class OrganizeImportsTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(OrganizeImportsTool.class);

    public OrganizeImportsTool(Supplier<IJdtService> serviceSupplier,
                               RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "organize_imports";
    }

    @Override
    public String getDescription() {
        return """
            Organize imports in a Java file.

            Removes unused imports and sorts remaining imports alphabetically.
            Applies the change directly (default) and returns
            { filesModified, diff, undoChangeId, summary }; when imports are
            already organized, returns hasChanges: false without touching the
            file. Pass auto_apply: false to stage instead.

            USAGE: organize_imports(filePath="path/to/File.java")
            OUTPUT: Modified file + unified diff + undo handle

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
            )
        ));
        schema.put("required", List.of("filePath"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required"));
        }

        {
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

            // Get current imports
            @SuppressWarnings("unchecked")
            List<ImportDeclaration> currentImports = ast.imports();

            // Find all referenced types
            Set<String> referencedTypes = findReferencedTypes(ast);

            // Categorize imports
            List<String> usedImports = new ArrayList<>();
            List<String> unusedImports = new ArrayList<>();
            List<String> staticImports = new ArrayList<>();
            List<String> onDemandImports = new ArrayList<>();

            for (ImportDeclaration imp : currentImports) {
                String importName = imp.getName().getFullyQualifiedName();

                if (imp.isStatic()) {
                    staticImports.add(imp.toString().trim());
                } else if (imp.isOnDemand()) {
                    onDemandImports.add(importName + ".*");
                } else {
                    String simpleName = importName.substring(importName.lastIndexOf('.') + 1);
                    if (referencedTypes.contains(simpleName) || referencedTypes.contains(importName)) {
                        usedImports.add(importName);
                    } else {
                        unusedImports.add(importName);
                    }
                }
            }

            // Sort imports: java.* first, then javax.*, then others
            List<String> sortedImports = sortImports(usedImports);

            // Build organized import block
            StringBuilder organizedImports = new StringBuilder();
            String lastPrefix = "";

            for (String imp : sortedImports) {
                String prefix = getImportPrefix(imp);
                if (!lastPrefix.isEmpty() && !prefix.equals(lastPrefix)) {
                    organizedImports.append("\n");
                }
                organizedImports.append("import ").append(imp).append(";\n");
                lastPrefix = prefix;
            }

            // Add on-demand imports
            if (!onDemandImports.isEmpty()) {
                if (organizedImports.length() > 0) {
                    organizedImports.append("\n");
                }
                for (String imp : onDemandImports.stream().sorted().collect(Collectors.toList())) {
                    organizedImports.append("import ").append(imp).append(";\n");
                }
            }

            // Add static imports at the end
            if (!staticImports.isEmpty()) {
                if (organizedImports.length() > 0) {
                    organizedImports.append("\n");
                }
                for (String imp : staticImports.stream().sorted().collect(Collectors.toList())) {
                    if (!imp.endsWith(";")) {
                        organizedImports.append(imp).append("\n");
                    } else {
                        organizedImports.append(imp.replace(";", ";\n"));
                    }
                }
            }

            // Calculate import range
            int importStart = -1;
            int importEnd = -1;
            if (!currentImports.isEmpty()) {
                importStart = currentImports.get(0).getStartPosition();
                ImportDeclaration lastImport = currentImports.get(currentImports.size() - 1);
                importEnd = lastImport.getStartPosition() + lastImport.getLength();
            }

            boolean hasChanges = !unusedImports.isEmpty() || needsSorting(currentImports, sortedImports);

            if (!hasChanges || importStart < 0) {
                // Already organized (or no import block) — success no-op.
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", getName());
                data.put("applied", false);
                data.put("hasChanges", false);
                data.put("filePath", service.getPathUtils().formatPath(path));
                data.put("totalImports", currentImports.size());
                data.put("unusedImports", unusedImports);
                return Preparation.fail(ToolResponse.success(data, ResponseMeta.builder()
                    .suggestedNextTools(List.of(
                        "get_diagnostics to check for remaining issues"))
                    .build()));
            }

            List<TextEdit> edits = new ArrayList<>();
            edits.add(new ReplaceEdit(importStart, importEnd - importStart,
                organizedImports.toString().trim()));

            IFile file = (IFile) cu.getResource();
            Change change = ChangeEngine.fromFileEdits("organize imports", Map.of(file, edits));

            Map<String, Object> extras = new LinkedHashMap<>();
            extras.put("filePath", service.getPathUtils().formatPath(path));
            extras.put("hasChanges", true);
            extras.put("totalImports", currentImports.size());
            extras.put("usedImports", usedImports.size());
            extras.put("unusedImports", unusedImports);
            extras.put("organizedImportBlock", organizedImports.toString().trim());

            String summary = "organize imports (" + unusedImports.size() + " unused removed)";
            return Preparation.of(change, summary, extras);
        }
    }

    private Set<String> findReferencedTypes(CompilationUnit ast) {
        Set<String> types = new HashSet<>();

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleType node) {
                types.add(node.getName().getFullyQualifiedName());
                if (node.getName() instanceof SimpleName sn) {
                    types.add(sn.getIdentifier());
                }
                return true;
            }

            @Override
            public boolean visit(SimpleName node) {
                IBinding binding = node.resolveBinding();
                if (binding instanceof ITypeBinding) {
                    types.add(node.getIdentifier());
                }
                return true;
            }

            @Override
            public boolean visit(QualifiedName node) {
                types.add(node.getFullyQualifiedName());
                return true;
            }

            @Override
            public boolean visit(MarkerAnnotation node) {
                types.add(node.getTypeName().getFullyQualifiedName());
                return true;
            }

            @Override
            public boolean visit(NormalAnnotation node) {
                types.add(node.getTypeName().getFullyQualifiedName());
                return true;
            }

            @Override
            public boolean visit(SingleMemberAnnotation node) {
                types.add(node.getTypeName().getFullyQualifiedName());
                return true;
            }
        });

        return types;
    }

    private List<String> sortImports(List<String> imports) {
        return imports.stream()
            .sorted((a, b) -> {
                int prefixCompare = getImportOrder(a) - getImportOrder(b);
                if (prefixCompare != 0) return prefixCompare;
                return a.compareTo(b);
            })
            .collect(Collectors.toList());
    }

    private int getImportOrder(String importName) {
        if (importName.startsWith("java.")) return 0;
        if (importName.startsWith("javax.")) return 1;
        return 2;
    }

    private String getImportPrefix(String importName) {
        if (importName.startsWith("java.")) return "java";
        if (importName.startsWith("javax.")) return "javax";
        int dot = importName.indexOf('.');
        return dot > 0 ? importName.substring(0, dot) : importName;
    }

    private boolean needsSorting(List<ImportDeclaration> current, List<String> sorted) {
        if (current.size() != sorted.size()) return true;

        int sortedIndex = 0;
        for (ImportDeclaration imp : current) {
            if (imp.isStatic() || imp.isOnDemand()) continue;

            String currentName = imp.getName().getFullyQualifiedName();
            if (sortedIndex >= sorted.size() || !currentName.equals(sorted.get(sortedIndex))) {
                return true;
            }
            sortedIndex++;
        }
        return false;
    }
}
