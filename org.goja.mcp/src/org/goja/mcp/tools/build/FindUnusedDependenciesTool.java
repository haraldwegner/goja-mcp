package org.goja.mcp.tools.build;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 13 (v1.7.0) — Ring 3 build/dep: {@code find_unused_dependencies}.
 *
 * <p>Heuristic: a declared {@code <dependency>} is "used" when at least one
 * source-code {@code import} matches:
 * <ul>
 *   <li>the dep's {@code groupId} as a prefix, or</li>
 *   <li>the {@code artifactId} (hyphens replaced by dots) appears as a
 *       substring of any import — handles cases like
 *       {@code com.fasterxml.jackson.core:jackson-databind} where the
 *       provided package is {@code com.fasterxml.jackson.databind} (i.e.
 *       not under the {@code groupId}).</li>
 * </ul>
 *
 * <p>This is read-only and best-effort; rich classpath inspection via the
 * M2E-resolved JAR's actual provided packages is a v1.8.x enhancement.
 * Document false positives in upgrade-checklist; an agent should
 * sanity-check before deleting.</p>
 */
public class FindUnusedDependenciesTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindUnusedDependenciesTool.class);

    public FindUnusedDependenciesTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_unused_dependencies";
    }

    @Override
    public String getDescription() {
        return """
            List declared <dependency> entries in pom.xml that don't appear
            to be used by any source-code import. Read-only.

            Heuristic: a declared dep is "used" when at least one source
            import or qualified reference matches the groupId, the groupId
            without its trailing segment, or the artifactId (hyphens → dots).
            False positives possible; treat results as suggestions, not gospel.

            USAGE:
              find_unused_dependencies()
              find_unused_dependencies(projectKey="core")

            Result:
              { operation, projectKey, projectKind: "maven",
                unusedDependencies: [{groupId, artifactId, version, scope}],
                warnings }

            Detection: pom.xml (Maven) takes precedence; falls back to
            build.gradle / build.gradle.kts (Gradle) when no pom.xml is present.
            For hybrid projects with both, Maven wins.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        try {
            LoadedProject project = pickProject(service, arguments);
            if (project == null) {
                return ToolResponse.invalidParameter("projectKey",
                    "No loaded project. Use list_projects.");
            }

            Path pom = MavenPomSupport.locatePom(project);
            if (pom == null) {
                // Sprint 14 Phase C (v1.8.0): try Gradle path.
                Path buildFile = GradleBuildSupport.locateBuildFile(project);
                if (buildFile != null) {
                    return findUnusedInGradle(project, buildFile);
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", "find_unused_dependencies");
                data.put("projectKey", project.projectKey());
                data.put("projectKind", "unknown");
                data.put("unusedDependencies", List.of());
                data.put("warnings", List.of(
                    "Project '" + project.projectKey() + "' is neither Maven "
                        + "(no pom.xml) nor Gradle (no build.gradle / build.gradle.kts)."));
                return ToolResponse.success(data, ResponseMeta.builder()
                    .totalCount(0).returnedCount(0).build());
            }

            List<MavenPomSupport.DeclaredDep> declared = MavenPomSupport.readDependencies(pom);
            Set<String> imports = collectImports(project.javaProject());

            List<Map<String, Object>> unused = new ArrayList<>();
            for (MavenPomSupport.DeclaredDep dep : declared) {
                if (isUsed(dep, imports)) continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("groupId", dep.groupId());
                entry.put("artifactId", dep.artifactId());
                entry.put("version", dep.version());
                entry.put("scope", dep.scope());
                unused.add(entry);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "find_unused_dependencies");
            data.put("projectKey", project.projectKey());
            data.put("projectKind", "maven");
            data.put("totalDeclared", declared.size());
            data.put("unusedDependencies", unused);
            data.put("warnings", List.of(
                "Heuristic match: false positives possible. "
                    + "Sanity-check before deleting."));

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(unused.size())
                .returnedCount(unused.size())
                .build());
        } catch (Exception e) {
            log.warn("find_unused_dependencies failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private static boolean isUsed(MavenPomSupport.DeclaredDep dep, Set<String> imports) {
        String groupId = dep.groupId();
        String artifactDots = dep.artifactId().replace('-', '.');

        for (String imp : imports) {
            if (imp.startsWith(groupId + ".") || imp.equals(groupId)) return true;
            if (imp.contains(artifactDots)) return true;
        }
        return false;
    }

    private static Set<String> collectImports(IJavaProject javaProject) throws Exception {
        Set<String> out = new LinkedHashSet<>();
        for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            for (IJavaElement child : root.getChildren()) {
                if (!(child instanceof IPackageFragment pkg)) continue;
                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    for (IImportDeclaration imp : cu.getImports()) {
                        String name = imp.getElementName();
                        if (name != null && !name.isBlank()) out.add(name);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Sprint 14 Phase C (v1.8.0): text-level Gradle unused-dependency
     * heuristic. Same group/artifact-vs-import comparison as the Maven
     * path; only the dependency parser differs.
     */
    private ToolResponse findUnusedInGradle(LoadedProject project, Path buildFile)
            throws Exception {
        List<GradleBuildSupport.DeclaredGradleDep> declared =
            GradleBuildSupport.readDependencies(buildFile);
        Set<String> imports = collectImports(project.javaProject());

        List<Map<String, Object>> unused = new ArrayList<>();
        for (GradleBuildSupport.DeclaredGradleDep dep : declared) {
            if (isUsedGradle(dep, imports)) continue;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("groupId", dep.groupId());
            entry.put("artifactId", dep.artifactId());
            entry.put("version", dep.version());
            entry.put("configuration", dep.configuration());
            unused.add(entry);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "find_unused_dependencies");
        data.put("projectKey", project.projectKey());
        data.put("projectKind", "gradle");
        data.put("totalDeclared", declared.size());
        data.put("unusedDependencies", unused);
        data.put("warnings", List.of(
            "Heuristic match: false positives possible. Sanity-check before deleting. "
                + "Gradle path uses text-level parsing; version-catalog refs "
                + "(libs.xxx) and platform/BOM entries are not detected."));

        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(unused.size()).returnedCount(unused.size()).build());
    }

    private static boolean isUsedGradle(GradleBuildSupport.DeclaredGradleDep dep,
                                         Set<String> imports) {
        String groupId = dep.groupId();
        String artifactDots = dep.artifactId().replace('-', '.');
        for (String imp : imports) {
            if (imp.startsWith(groupId + ".") || imp.equals(groupId)) return true;
            if (imp.contains(artifactDots)) return true;
        }
        return false;
    }

    private LoadedProject pickProject(IJdtService service, JsonNode arguments) {
        String projectKey = getStringParam(arguments, "projectKey");
        if (projectKey != null && !projectKey.isBlank()) {
            return service.getProject(projectKey).orElse(null);
        }
        return service.allProjects().stream().findFirst().orElse(null);
    }
}
