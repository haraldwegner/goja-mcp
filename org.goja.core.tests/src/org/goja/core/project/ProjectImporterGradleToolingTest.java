package org.goja.core.project;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sprint 11 Phase C — Gradle Tooling API integration.
 *
 * <p>Each test writes a tiny ad-hoc Gradle project to a {@code @TempDir}
 * and asks {@link ProjectImporter#readGradleProjectModel(Path)} to extract
 * the source roots and resolved classpath via the embedded
 * {@code gradle-tooling-api} jar (loaded into {@code org.goja.core}'s
 * classloader through the Bundle-ClassPath header).</p>
 *
 * <p>The Tooling API needs a Gradle distribution at runtime. On first run
 * it downloads one (~150 MB into {@code ~/.gradle/caches/dists}); subsequent
 * runs use the cache. CI environments without network access can set the
 * system property {@code goja.skip.gradle=true} to skip these tests.</p>
 */
class ProjectImporterGradleToolingTest {

    /**
     * Allow CI / fast iteration to skip the network-dependent Tooling API tests.
     * Set {@code -Dgoja.skip.gradle=true} on the surefire command line.
     */
    private static void assumeGradleAvailable() {
        assumeTrue(
            !"true".equalsIgnoreCase(System.getProperty("goja.skip.gradle", "false")),
            "Gradle Tooling API tests skipped via goja.skip.gradle=true"
        );
    }

    @Test
    @DisplayName("Tooling API extracts the standard sourceSets (src/main/java + src/test/java) from a plain java-plugin project")
    void gradle_returnsActualSourceSets(@TempDir Path tempDir) throws IOException {
        assumeGradleAvailable();

        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'simple-gradle'\n");
        Files.writeString(tempDir.resolve("build.gradle"),
            "plugins { id 'java' }\n");
        Files.createDirectories(tempDir.resolve("src/main/java/com/example"));
        Files.createDirectories(tempDir.resolve("src/test/java/com/example"));
        Files.writeString(tempDir.resolve("src/main/java/com/example/Main.java"),
            "package com.example; public class Main {}\n");

        Optional<ProjectImporter.GradleProjectModel> model =
            ProjectImporter.readGradleProjectModel(tempDir);

        assertTrue(model.isPresent(), "Tooling API should resolve a model for a java-plugin project");
        List<Path> srcs = model.get().srcPaths();
        assertTrue(srcs.contains(tempDir.resolve("src/main/java").toAbsolutePath().normalize()),
            "Standard src/main/java should be reported as a source directory; got " + srcs);
        assertTrue(srcs.contains(tempDir.resolve("src/test/java").toAbsolutePath().normalize()),
            "Standard src/test/java should be reported as a source directory; got " + srcs);
    }

    @Test
    @DisplayName("Tooling API resolves declared file-based dependencies into the classpath")
    void gradle_returnsActualDependencies(@TempDir Path tempDir) throws IOException {
        assumeGradleAvailable();

        // Use a flat-dir repo with a local jar to avoid any network dependency
        // for declared classpath deps. The jar's bytes don't matter — Gradle
        // resolves it as a path-typed classpath entry without inspecting it.
        Files.createDirectories(tempDir.resolve("libs"));
        Files.write(tempDir.resolve("libs/dummy-1.0.0.jar"), new byte[]{});

        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'simple-gradle-deps'\n");
        Files.writeString(tempDir.resolve("build.gradle"),
            "plugins { id 'java' }\n" +
            "repositories { flatDir { dirs 'libs' } }\n" +
            "dependencies { implementation files('libs/dummy-1.0.0.jar') }\n");
        Files.createDirectories(tempDir.resolve("src/main/java"));

        Optional<ProjectImporter.GradleProjectModel> model =
            ProjectImporter.readGradleProjectModel(tempDir);

        assertTrue(model.isPresent());
        List<Path> jars = model.get().classpathJars();
        Path dummyJar = tempDir.resolve("libs/dummy-1.0.0.jar").toAbsolutePath().normalize();
        assertTrue(jars.contains(dummyJar),
            "Declared file-based dependency should appear on the classpath; got " + jars);
    }

    @Test
    @DisplayName("Tooling API honours sourceSets.main.java.srcDirs overrides")
    void gradle_customSrcDir(@TempDir Path tempDir) throws IOException {
        assumeGradleAvailable();

        Files.writeString(tempDir.resolve("settings.gradle"), "rootProject.name = 'simple-gradle-custom'\n");
        Files.writeString(tempDir.resolve("build.gradle"),
            "plugins { id 'java' }\n" +
            "sourceSets {\n" +
            "    main { java { srcDirs = ['custom-src'] } }\n" +
            "    test { java { srcDirs = ['custom-test'] } }\n" +
            "}\n");
        Files.createDirectories(tempDir.resolve("custom-src/com/example"));
        Files.createDirectories(tempDir.resolve("custom-test/com/example"));
        Files.writeString(tempDir.resolve("custom-src/com/example/Main.java"),
            "package com.example; public class Main {}\n");

        Optional<ProjectImporter.GradleProjectModel> model =
            ProjectImporter.readGradleProjectModel(tempDir);

        assertTrue(model.isPresent());
        List<Path> srcs = model.get().srcPaths();
        assertTrue(srcs.contains(tempDir.resolve("custom-src").toAbsolutePath().normalize()),
            "Custom src dir should be reported; got " + srcs);
        assertTrue(srcs.contains(tempDir.resolve("custom-test").toAbsolutePath().normalize()),
            "Custom test dir should be reported; got " + srcs);
        assertFalse(srcs.contains(tempDir.resolve("src/main/java").toAbsolutePath().normalize()),
            "Standard src/main/java was overridden — should NOT appear; got " + srcs);
    }
}
