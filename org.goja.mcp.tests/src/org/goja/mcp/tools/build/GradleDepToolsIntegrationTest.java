package org.goja.mcp.tools.build;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 14 Phase C — end-to-end checks that {@code add_dependency},
 * {@code update_dependency}, and {@code find_unused_dependencies}
 * dispatch to the Gradle path (their Maven path is covered by the
 * existing per-tool tests in this package). Uses synthesized minimal
 * Gradle fixtures via @TempDir.
 */
class GradleDepToolsIntegrationTest {

    @Test
    @DisplayName("add_dependency dispatches to Gradle when only build.gradle is present")
    void addDependency_appendsToBuildGradle(@TempDir Path tempDir) throws Exception {
        Path projectRoot = synthesizeGradleProject(tempDir, "single-project",
            """
            plugins { id 'java' }

            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.14.0'
            }
            """, false);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        AddDependencyTool tool = new AddDependencyTool(() -> service);
        ObjectMapper om = new ObjectMapper();

        ObjectNode args = om.createObjectNode();
        args.put("groupId", "com.google.guava");
        args.put("artifactId", "guava");
        args.put("version", "33.0.0-jre");
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "add_dependency must succeed on Gradle project; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("buildFilePath"), "response must reference buildFilePath, not pomPath");

        String newText = Files.readString(projectRoot.resolve("build.gradle"), StandardCharsets.UTF_8);
        assertTrue(newText.contains("commons-lang3"), "existing dep preserved");
        assertTrue(newText.contains("guava"), "new dep written; got:\n" + newText);
    }

    @Test
    @DisplayName("update_dependency dispatches to Gradle and bumps the version in-place")
    void updateDependency_bumpsVersionInBuildGradle(@TempDir Path tempDir) throws Exception {
        Path projectRoot = synthesizeGradleProject(tempDir, "single-project",
            """
            plugins { id 'java' }

            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.10.0'
                testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.0'
            }
            """, false);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        UpdateDependencyTool tool = new UpdateDependencyTool(() -> service);
        ObjectMapper om = new ObjectMapper();

        ObjectNode args = om.createObjectNode();
        args.put("groupId", "org.apache.commons");
        args.put("artifactId", "commons-lang3");
        args.put("newVersion", "3.14.0");
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        String newText = Files.readString(projectRoot.resolve("build.gradle"), StandardCharsets.UTF_8);
        assertTrue(newText.contains("commons-lang3:3.14.0"),
            "version bump applied; got:\n" + newText);
        assertTrue(newText.contains("junit-jupiter-api:5.10.0"),
            "other deps untouched; got:\n" + newText);
    }

    @Test
    @DisplayName("find_unused_dependencies returns Gradle-shape result with projectKind=gradle")
    void findUnused_returnsGradleShape(@TempDir Path tempDir) throws Exception {
        // Use deps whose package names match groupId or artifactId so the
        // heuristic detects usage correctly (Guava is a known
        // false-negative because com.google.guava artifacts ship under
        // com.google.common.* packages — not testable here).
        Path projectRoot = synthesizeGradleProject(tempDir, "single-project",
            """
            plugins { id 'java' }

            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.14.0'
                implementation 'com.example.unused:not-imported:1.0'
            }
            """, false);
        // Source file imports commons-lang3 (used) but NOT com.example.unused.
        Files.writeString(projectRoot.resolve("src/main/java/Sample.java"),
            "import org.apache.commons.lang3.StringUtils;\n"
                + "public class Sample {\n"
                + "    public String get() { return StringUtils.EMPTY; }\n"
                + "}\n");

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        FindUnusedDependenciesTool tool = new FindUnusedDependenciesTool(() -> service);
        ObjectMapper om = new ObjectMapper();

        ToolResponse r = tool.execute(om.createObjectNode());
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("gradle", data.get("projectKind"),
            "projectKind must be 'gradle' when build.gradle is present");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> unused = (List<Map<String, Object>>) data.get("unusedDependencies");
        boolean flaggedUnused = unused.stream()
            .anyMatch(d -> "not-imported".equals(d.get("artifactId")));
        boolean falselyFlaggedLang3 = unused.stream()
            .anyMatch(d -> "commons-lang3".equals(d.get("artifactId")));
        assertTrue(flaggedUnused,
            "com.example.unused:not-imported must be flagged; got: " + unused);
        assertFalse(falselyFlaggedLang3,
            "commons-lang3 is imported and must NOT be flagged; got: " + unused);
    }

    @Test
    @DisplayName("add_dependency dispatches to Gradle for Kotlin DSL (build.gradle.kts)")
    void addDependency_kotlinDsl(@TempDir Path tempDir) throws Exception {
        Path projectRoot = synthesizeGradleProject(tempDir, "single-project",
            """
            plugins { java }

            dependencies {
                implementation("org.apache.commons:commons-lang3:3.14.0")
            }
            """, true);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);
        AddDependencyTool tool = new AddDependencyTool(() -> service);
        ObjectMapper om = new ObjectMapper();

        ObjectNode args = om.createObjectNode();
        args.put("groupId", "com.google.guava");
        args.put("artifactId", "guava");
        args.put("version", "33.0.0-jre");
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        String newText = Files.readString(projectRoot.resolve("build.gradle.kts"), StandardCharsets.UTF_8);
        assertTrue(newText.contains("implementation(\"com.google.guava:guava:33.0.0-jre\")"),
            "must use Kotlin parens form; got:\n" + newText);
    }

    /**
     * Synthesize a minimal Gradle project: build.gradle(.kts) at root,
     * src/main/java/ dir exists. Sufficient for
     * {@link JdtServiceImpl#loadProject(Path)} to recognise it as Gradle.
     */
    private static Path synthesizeGradleProject(Path tempDir, String name,
                                                  String buildScript,
                                                  boolean kotlinDsl) throws Exception {
        Path root = tempDir.resolve(name);
        Files.createDirectories(root.resolve("src/main/java"));
        Path buildFile = root.resolve(kotlinDsl ? "build.gradle.kts" : "build.gradle");
        Files.writeString(buildFile, buildScript);
        return root;
    }
}
