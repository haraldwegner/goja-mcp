package org.goja.mcp.tools.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
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
import org.goja.core.IJdtService;
import org.goja.core.LoadedProject;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AbstractTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 13 (v1.7.0) — Ring 4 workflow: {@code optimize_imports_workspace}.
 *
 * <p>Per-file fan-out of import optimisation: walks every loaded project's
 * source compilation units, removes unused imports, sorts the rest by
 * the same convention {@code organize_imports} uses (java.* → javax.* →
 * other → static), and writes the file in place.</p>
 *
 * <p>Differs from {@code organize_imports} (Sprint 11), which is single-file
 * and returns a textEdit suggestion the agent applies. This tool actually
 * mutates files; idempotent (a second run returns zero changes).</p>
 */
public class OptimizeImportsWorkspaceTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(OptimizeImportsWorkspaceTool.class);

    public OptimizeImportsWorkspaceTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "optimize_imports_workspace";
    }

    @Override
    public String getDescription() {
        return """
            Optimise imports across every source file in the scope: remove
            unused imports, sort the rest. Mutates files in place. Idempotent.

            USAGE:
              optimize_imports_workspace()
              optimize_imports_workspace(scope="project", projectKey="core")

            Inputs:
            - scope — "workspace" (default) | "project".
            - projectKey — required when scope=project; optional otherwise.

            Result:
              { operation, scope, filesProcessed, filesChanged,
                importsRemoved, modifiedFiles[] }
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("scope", Map.of(
            "type", "string",
            "enum", List.of("workspace", "project")));
        schema.put("properties", properties);
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String scope = getStringParam(arguments, "scope", "workspace");
        boolean isProjectScope = "project".equalsIgnoreCase(scope);

        try {
            List<ICompilationUnit> targets = new ArrayList<>();
            if (isProjectScope) {
                LoadedProject project = pickProject(service, arguments);
                if (project == null) {
                    return ToolResponse.invalidParameter("projectKey",
                        "Required when scope=project. Use list_projects.");
                }
                targets.addAll(collectAllCompilationUnits(project.javaProject()));
            } else {
                for (LoadedProject project : service.allProjects()) {
                    targets.addAll(collectAllCompilationUnits(project.javaProject()));
                }
            }

            int filesProcessed = 0;
            int filesChanged = 0;
            int importsRemoved = 0;
            List<Map<String, Object>> modified = new ArrayList<>();

            for (ICompilationUnit cu : targets) {
                filesProcessed++;
                OptimizeResult res = optimizeOne(cu);
                if (res == null || res.removedCount == 0 && !res.reordered) continue;

                filesChanged++;
                importsRemoved += res.removedCount;
                String filePath;
                try {
                    filePath = service.getPathUtils().formatPath(
                        cu.getResource().getLocation().toFile().toPath());
                } catch (Exception e) {
                    filePath = cu.getElementName();
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("filePath", filePath);
                entry.put("importsRemoved", res.removedCount);
                entry.put("reordered", res.reordered);
                modified.add(entry);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "optimize_imports_workspace");
            data.put("scope", isProjectScope ? "project" : "workspace");
            data.put("filesProcessed", filesProcessed);
            data.put("filesChanged", filesChanged);
            data.put("importsRemoved", importsRemoved);
            data.put("modifiedFiles", modified);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(filesChanged)
                .returnedCount(filesChanged)
                .build());
        } catch (Exception e) {
            log.warn("optimize_imports_workspace failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Compute and apply optimised imports for one CU. Returns {@code null}
     * if the file has no imports at all (nothing to do).
     */
    private static OptimizeResult optimizeOne(ICompilationUnit cu) throws Exception {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(new NullProgressMonitor());

        @SuppressWarnings("unchecked")
        List<ImportDeclaration> imports = ast.imports();
        if (imports.isEmpty()) return null;

        Set<String> referenced = collectReferencedTypes(ast);

        // Categorise each import. Static & on-demand are kept as-is (we
        // don't track their use precisely; safe default).
        List<String> usedRegular = new ArrayList<>();
        List<String> staticImports = new ArrayList<>();
        List<String> onDemandImports = new ArrayList<>();
        int removed = 0;

        for (ImportDeclaration imp : imports) {
            String name = imp.getName().getFullyQualifiedName();
            if (imp.isStatic()) {
                staticImports.add((imp.isOnDemand() ? "import static " + name + ".*"
                                                   : "import static " + name) + ";");
                continue;
            }
            if (imp.isOnDemand()) {
                onDemandImports.add("import " + name + ".*;");
                continue;
            }
            String simple = name.substring(name.lastIndexOf('.') + 1);
            if (referenced.contains(simple) || referenced.contains(name)) {
                usedRegular.add(name);
            } else {
                removed++;
            }
        }

        // Sort: java.* → javax.* → others → static at the end.
        usedRegular.sort(Comparator.comparingInt((String s) -> importGroup(s)).thenComparing(Comparator.naturalOrder()));

        StringBuilder block = new StringBuilder();
        int lastGroup = -1;
        for (String name : usedRegular) {
            int group = importGroup(name);
            if (lastGroup >= 0 && group != lastGroup) block.append("\n");
            block.append("import ").append(name).append(";\n");
            lastGroup = group;
        }
        if (!onDemandImports.isEmpty()) {
            if (block.length() > 0) block.append("\n");
            for (String s : onDemandImports.stream().sorted().toList()) {
                block.append(s).append("\n");
            }
        }
        if (!staticImports.isEmpty()) {
            if (block.length() > 0) block.append("\n");
            for (String s : staticImports.stream().sorted().toList()) {
                block.append(s).append("\n");
            }
        }

        // Compute current import-block range [start, end) in source.
        int start = imports.get(0).getStartPosition();
        ImportDeclaration last = imports.get(imports.size() - 1);
        int end = last.getStartPosition() + last.getLength();
        // Include the trailing newline if present so we don't end up with
        // a stray blank line.
        String source = cu.getSource();
        if (end < source.length() && source.charAt(end) == '\n') end++;

        String newImports = block.toString().stripTrailing() + "\n";
        String oldImports = source.substring(start, end);
        // For idempotence, treat content-equivalent as no-op (the leading/trailing
        // newline normalisation can produce trivially-different strings).
        boolean reordered = !oldImports.replaceAll("\\s+", " ").trim()
            .equals(newImports.replaceAll("\\s+", " ").trim());

        if (removed == 0 && !reordered) {
            return new OptimizeResult(0, false);
        }

        String newSource = source.substring(0, start) + newImports + source.substring(end);
        cu.becomeWorkingCopy(new NullProgressMonitor());
        try {
            cu.getBuffer().setContents(newSource);
            cu.commitWorkingCopy(true, new NullProgressMonitor());
        } finally {
            cu.discardWorkingCopy();
        }
        return new OptimizeResult(removed, reordered);
    }

    private static int importGroup(String importName) {
        if (importName.startsWith("java.")) return 0;
        if (importName.startsWith("javax.")) return 1;
        return 2;
    }

    private static Set<String> collectReferencedTypes(CompilationUnit ast) {
        Set<String> types = new HashSet<>();
        ASTVisitor visitor = new ASTVisitor() {
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
        };
        // Visit only the type declarations and (for the package-statement
        // edge case) the package node — never the import declarations,
        // whose own qualified names would otherwise bind their imported
        // type and falsely mark the import as used.
        for (Object t : ast.types()) {
            if (t instanceof org.eclipse.jdt.core.dom.ASTNode node) {
                node.accept(visitor);
            }
        }
        if (ast.getPackage() != null) ast.getPackage().accept(visitor);
        return types;
    }

    private static List<ICompilationUnit> collectAllCompilationUnits(IJavaProject project) throws Exception {
        List<ICompilationUnit> out = new ArrayList<>();
        for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            for (IJavaElement child : root.getChildren()) {
                if (!(child instanceof IPackageFragment pkg)) continue;
                for (ICompilationUnit cu : pkg.getCompilationUnits()) out.add(cu);
            }
        }
        return out;
    }

    private LoadedProject pickProject(IJdtService service, JsonNode arguments) {
        String projectKey = getStringParam(arguments, "projectKey");
        if (projectKey != null && !projectKey.isBlank()) {
            return service.getProject(projectKey).orElse(null);
        }
        return service.allProjects().stream().findFirst().orElse(null);
    }

    private record OptimizeResult(int removedCount, boolean reordered) {}
}
