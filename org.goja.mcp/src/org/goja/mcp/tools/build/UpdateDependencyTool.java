package org.goja.mcp.tools.build;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.goja.core.IJdtService;
import org.goja.core.LoadedProject;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AbstractTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 13 (v1.7.0) — Ring 3 build/dep: {@code update_dependency}.
 *
 * <p>Bumps the {@code <version>} of an existing dependency in
 * {@code pom.xml}. Maven-only for v1.7.0.</p>
 */
public class UpdateDependencyTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(UpdateDependencyTool.class);

    public UpdateDependencyTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "update_dependency";
    }

    @Override
    public String getDescription() {
        return """
            Update the <version> of an existing dependency in pom.xml. The
            tool refuses if the named dependency isn't declared (use
            add_dependency to introduce it).

            USAGE:
              update_dependency(groupId="org.apache.commons",
                                artifactId="commons-lang3",
                                newVersion="3.15.0")

            Inputs:
            - projectKey — optional. Defaults to the workspace's primary project.
            - groupId / artifactId — required. Identify the existing entry.
            - newVersion — required.

            Result:
              { operation, projectKey, pomPath,
                updated: { groupId, artifactId, oldVersion, newVersion },
                modifiedFiles }

            Detection: pom.xml (Maven) takes precedence; falls back to
            build.gradle / build.gradle.kts (Gradle) when no pom.xml is present.
            For hybrid projects with both, Maven wins.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("groupId", Map.of("type", "string"));
        properties.put("artifactId", Map.of("type", "string"));
        properties.put("newVersion", Map.of("type", "string"));
        schema.put("properties", properties);
        schema.put("required", List.of("groupId", "artifactId", "newVersion"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse missing = requireParam(arguments, "groupId");
        if (missing != null) return missing;
        missing = requireParam(arguments, "artifactId");
        if (missing != null) return missing;
        missing = requireParam(arguments, "newVersion");
        if (missing != null) return missing;

        String groupId = getStringParam(arguments, "groupId");
        String artifactId = getStringParam(arguments, "artifactId");
        String newVersion = getStringParam(arguments, "newVersion");
        if (isBlank(groupId) || isBlank(artifactId) || isBlank(newVersion)) {
            return ToolResponse.invalidParameter("groupId/artifactId/newVersion",
                "All three must be non-empty.");
        }

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
                    return updateInGradle(service, project, buildFile, groupId, artifactId, newVersion);
                }
                return ToolResponse.invalidParameter("projectKey",
                    "Project '" + project.projectKey() + "' is neither Maven "
                        + "(no pom.xml at root) nor Gradle (no build.gradle / build.gradle.kts).");
            }

            String original = Files.readString(pom, StandardCharsets.UTF_8);
            UpdateResult result = updateVersion(original, groupId, artifactId, newVersion);
            if (result == null) {
                return ToolResponse.invalidParameter("groupId/artifactId",
                    "No <dependency> matching " + groupId + ":" + artifactId
                        + " found in " + pom + ". Use add_dependency to introduce it.");
            }
            Files.writeString(pom, result.updatedPom, StandardCharsets.UTF_8);

            try {
                project.javaProject().getProject().refreshLocal(
                    IResource.DEPTH_INFINITE, new NullProgressMonitor());
            } catch (Exception ignore) {}

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "update_dependency");
            data.put("projectKey", project.projectKey());
            data.put("pomPath", service.getPathUtils().formatPath(pom));
            Map<String, String> updated = new LinkedHashMap<>();
            updated.put("groupId", groupId);
            updated.put("artifactId", artifactId);
            updated.put("oldVersion", result.oldVersion);
            updated.put("newVersion", newVersion);
            data.put("updated", updated);

            data.put("modifiedFiles", List.of(Map.of(
                "filePath", data.get("pomPath"),
                "summary", "bumped " + groupId + ":" + artifactId
                    + " from " + result.oldVersion + " to " + newVersion)));

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(1)
                .returnedCount(1)
                .build());
        } catch (Exception e) {
            log.warn("update_dependency failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Find a {@code <dependency>} block matching {@code groupId}+{@code artifactId}
     * and replace its {@code <version>} text. Preserves the surrounding
     * formatting; only the version's text content is rewritten. Returns
     * {@code null} when no matching dependency or no version element is
     * found.
     */
    static UpdateResult updateVersion(String pom, String groupId, String artifactId, String newVersion) {
        Pattern depBlock = Pattern.compile(
            "<dependency>(.*?)</dependency>", Pattern.DOTALL);
        Matcher m = depBlock.matcher(pom);
        while (m.find()) {
            String inside = m.group(1);
            if (!matchesCoordinate(inside, "groupId", groupId)) continue;
            if (!matchesCoordinate(inside, "artifactId", artifactId)) continue;

            Pattern versionPattern = Pattern.compile(
                "(<version>)([^<]*)(</version>)", Pattern.DOTALL);
            Matcher vm = versionPattern.matcher(inside);
            if (!vm.find()) {
                // No <version> element to update.
                return null;
            }
            String oldVersion = vm.group(2).trim();
            String newInside = vm.replaceFirst(Matcher.quoteReplacement(
                vm.group(1) + newVersion + vm.group(3)));
            String updated = pom.substring(0, m.start())
                + "<dependency>" + newInside + "</dependency>"
                + pom.substring(m.end());
            return new UpdateResult(updated, oldVersion);
        }
        return null;
    }

    private static boolean matchesCoordinate(String depBlockInside, String tag, String value) {
        Pattern p = Pattern.compile("<" + tag + ">\\s*(.*?)\\s*</" + tag + ">", Pattern.DOTALL);
        Matcher m = p.matcher(depBlockInside);
        return m.find() && value.equals(m.group(1).trim());
    }

    /**
     * Sprint 14 Phase C (v1.8.0): text-level Gradle version-bump. Same
     * Buildship-deferred caveat as add_dependency — caller runs
     * refresh_workspace for classpath sync.
     */
    private ToolResponse updateInGradle(IJdtService service, LoadedProject project,
                                         Path buildFile, String groupId, String artifactId,
                                         String newVersion) throws Exception {
        String original = Files.readString(buildFile, StandardCharsets.UTF_8);
        GradleBuildSupport.UpdateGradleResult result =
            GradleBuildSupport.updateVersion(original, groupId, artifactId, newVersion);
        if (result == null) {
            return ToolResponse.invalidParameter("groupId/artifactId",
                "No Gradle dependency matching " + groupId + ":" + artifactId
                    + " found in " + buildFile + ". Use add_dependency to introduce it.");
        }
        Files.writeString(buildFile, result.updatedText(), StandardCharsets.UTF_8);

        try {
            project.javaProject().getProject().refreshLocal(
                IResource.DEPTH_INFINITE, new NullProgressMonitor());
        } catch (Exception ignore) {}

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "update_dependency");
        data.put("projectKey", project.projectKey());
        data.put("buildFilePath", service.getPathUtils().formatPath(buildFile));
        Map<String, String> updated = new LinkedHashMap<>();
        updated.put("groupId", groupId);
        updated.put("artifactId", artifactId);
        updated.put("oldVersion", result.oldVersion());
        updated.put("newVersion", newVersion);
        data.put("updated", updated);
        data.put("modifiedFiles", List.of(Map.of(
            "filePath", data.get("buildFilePath"),
            "summary", "bumped " + groupId + ":" + artifactId
                + " from " + result.oldVersion() + " to " + newVersion)));
        data.put("warnings", List.of(
            "Buildship classpath sync NOT performed; call refresh_workspace to refresh classpath."));

        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(1).returnedCount(1).build());
    }

    private LoadedProject pickProject(IJdtService service, JsonNode arguments) {
        String projectKey = getStringParam(arguments, "projectKey");
        if (projectKey != null && !projectKey.isBlank()) {
            return service.getProject(projectKey).orElse(null);
        }
        return service.allProjects().stream().findFirst().orElse(null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    record UpdateResult(String updatedPom, String oldVersion) {}
}
