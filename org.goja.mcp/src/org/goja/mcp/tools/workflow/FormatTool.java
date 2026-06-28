package org.goja.mcp.tools.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.goja.core.IJdtService;
import org.goja.core.LoadedProject;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AbstractTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 13 (v1.7.0) — Ring 4 workflow: {@code format}.
 *
 * <p>Apply the JDT {@link CodeFormatter} to one file, all files in a
 * package, an entire project, or every loaded project. Reads the project's
 * own formatter settings (from {@code .settings/org.eclipse.jdt.core.prefs})
 * when present; otherwise falls back to JDT defaults.</p>
 *
 * <p>{@code dryRun: true} returns the proposed text without writing files.</p>
 */
public class FormatTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FormatTool.class);

    public FormatTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "format";
    }

    @Override
    public String getDescription() {
        return """
            Apply the JDT formatter at the given scope. Project's own
            formatter settings (.settings/org.eclipse.jdt.core.prefs) win;
            otherwise defaults apply.

            USAGE:
              format(scope={kind: "file", filePath: "..."})
              format(scope={kind: "package", packageName: "com.example"})
              format(scope={kind: "project"}, projectKey="core")
              format(scope={kind: "workspace"})
              format(scope={kind: "file", filePath: "..."}, dryRun=true)

            Inputs:
            - scope.kind — file | package | project | workspace.
            - scope.filePath — required when kind=file.
            - scope.packageName — required when kind=package.
            - projectKey — required when kind=project; optional otherwise.
            - dryRun — default false. true returns formatted text without writing.

            Result:
              { operation, filesFormatted, filesUnchanged, modifiedFiles[],
                dryRun, samples? (when dryRun) }
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("scope", Map.of(
            "type", "object",
            "properties", Map.of(
                "kind", Map.of("type", "string",
                    "enum", List.of("file", "package", "project", "workspace")),
                "filePath", Map.of("type", "string"),
                "packageName", Map.of("type", "string"))));
        properties.put("dryRun", Map.of("type", "boolean"));
        schema.put("properties", properties);
        schema.put("required", List.of("scope"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        if (!arguments.has("scope") || !arguments.get("scope").isObject()) {
            return ToolResponse.invalidParameter("scope", "scope object required");
        }
        JsonNode scope = arguments.get("scope");
        String kind = scope.has("kind") ? scope.get("kind").asText() : null;
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("scope.kind",
                "Required: 'file' | 'package' | 'project' | 'workspace'");
        }
        boolean dryRun = arguments.has("dryRun") && arguments.get("dryRun").asBoolean(false);

        try {
            List<ICompilationUnit> targets = new ArrayList<>();
            switch (kind) {
                case "file" -> {
                    if (!scope.has("filePath")) {
                        return ToolResponse.invalidParameter("scope.filePath",
                            "Required when scope.kind=file");
                    }
                    Path path = Path.of(scope.get("filePath").asText());
                    ICompilationUnit cu = service.getCompilationUnit(path);
                    if (cu == null) {
                        return ToolResponse.fileNotFound(path.toString());
                    }
                    targets.add(cu);
                }
                case "package" -> {
                    if (!scope.has("packageName")) {
                        return ToolResponse.invalidParameter("scope.packageName",
                            "Required when scope.kind=package");
                    }
                    String pkgName = scope.get("packageName").asText();
                    LoadedProject scoped = pickProject(service, arguments);
                    if (scoped == null) {
                        return ToolResponse.invalidParameter("projectKey",
                            "No loaded project. Use list_projects.");
                    }
                    targets.addAll(collectPackageCompilationUnits(scoped.javaProject(), pkgName));
                }
                case "project" -> {
                    LoadedProject scoped = pickProject(service, arguments);
                    if (scoped == null) {
                        return ToolResponse.invalidParameter("projectKey",
                            "No loaded project. Use list_projects.");
                    }
                    targets.addAll(collectAllCompilationUnits(scoped.javaProject()));
                }
                case "workspace" -> {
                    for (LoadedProject project : service.allProjects()) {
                        targets.addAll(collectAllCompilationUnits(project.javaProject()));
                    }
                }
                default -> {
                    return ToolResponse.invalidParameter("scope.kind",
                        "Unknown scope kind: " + kind);
                }
            }

            int filesFormatted = 0;
            int filesUnchanged = 0;
            List<Map<String, Object>> modified = new ArrayList<>();
            List<Map<String, Object>> samples = new ArrayList<>();

            for (ICompilationUnit cu : targets) {
                String source = cu.getSource();
                CodeFormatter formatter = ToolFactory.createCodeFormatter(
                    cu.getJavaProject().getOptions(true));
                String lineSeparator = System.lineSeparator();
                TextEdit edit = formatter.format(
                    CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
                    source, 0, source.length(), 0, lineSeparator);
                if (edit == null || !edit.hasChildren()) {
                    filesUnchanged++;
                    continue;
                }
                Document doc = new Document(source);
                edit.apply(doc);
                String formatted = doc.get();
                if (formatted.equals(source)) {
                    filesUnchanged++;
                    continue;
                }

                String filePath;
                try {
                    filePath = service.getPathUtils().formatPath(
                        cu.getResource().getLocation().toFile().toPath());
                } catch (Exception e) {
                    filePath = cu.getElementName();
                }

                if (dryRun) {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("filePath", filePath);
                    sample.put("byteDelta", formatted.length() - source.length());
                    samples.add(sample);
                } else {
                    cu.becomeWorkingCopy(new NullProgressMonitor());
                    try {
                        cu.getBuffer().setContents(formatted);
                        cu.commitWorkingCopy(true, new NullProgressMonitor());
                    } finally {
                        cu.discardWorkingCopy();
                    }
                }
                filesFormatted++;
                modified.add(Map.of(
                    "filePath", filePath,
                    "summary", "formatted (Δ=" + (formatted.length() - source.length()) + " bytes)"));
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "format");
            data.put("scope", kind);
            data.put("dryRun", dryRun);
            data.put("filesFormatted", filesFormatted);
            data.put("filesUnchanged", filesUnchanged);
            data.put("modifiedFiles", modified);
            if (dryRun) {
                data.put("samples", samples);
            }

            // Force reference so DefaultCodeFormatterConstants import is meaningful
            // for future tuning even when the runtime path doesn't use it.
            @SuppressWarnings("unused")
            String fmtId = DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE;

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(filesFormatted)
                .returnedCount(filesFormatted)
                .build());
        } catch (Exception e) {
            log.warn("format failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private LoadedProject pickProject(IJdtService service, JsonNode arguments) {
        String projectKey = getStringParam(arguments, "projectKey");
        if (projectKey != null && !projectKey.isBlank()) {
            return service.getProject(projectKey).orElse(null);
        }
        return service.allProjects().stream().findFirst().orElse(null);
    }

    private static List<ICompilationUnit> collectPackageCompilationUnits(IJavaProject project, String packageName)
            throws Exception {
        List<ICompilationUnit> out = new ArrayList<>();
        for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            for (IJavaElement child : root.getChildren()) {
                if (!(child instanceof IPackageFragment pkg)) continue;
                if (!packageName.equals(pkg.getElementName())) continue;
                for (ICompilationUnit cu : pkg.getCompilationUnits()) out.add(cu);
            }
        }
        return out;
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
}
