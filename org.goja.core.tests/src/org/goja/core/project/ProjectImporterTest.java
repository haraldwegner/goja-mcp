package org.goja.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.goja.core.fixtures.TestProjectHelper;
import org.goja.core.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ProjectImporter.
 * Tests build system detection, source file counting, and classpath configuration.
 */
class ProjectImporterTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ProjectImporter importer;
    private WorkspaceManager workspaceManager;
    private Path mavenFixturePath;

    @BeforeEach
    void setUp() throws Exception {
        importer = new ProjectImporter();
        workspaceManager = new WorkspaceManager();
        workspaceManager.initialize();
        mavenFixturePath = helper.getFixturePath("simple-maven");
    }

    // ========== Build System Detection Tests ==========

    @Test
    @DisplayName("detectBuildSystem should detect Maven project")
    void detectBuildSystem_detectsMaven() {
        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(mavenFixturePath);

        assertEquals(ProjectImporter.BuildSystem.MAVEN, buildSystem,
            "Should detect Maven project from pom.xml");
    }

    @Test
    @DisplayName("detectBuildSystem should detect Gradle project with build.gradle")
    void detectBuildSystem_detectsGradle(@TempDir Path tempDir) throws IOException {
        // Create a fake Gradle project
        Files.writeString(tempDir.resolve("build.gradle"), "// Gradle build file");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.GRADLE, buildSystem,
            "Should detect Gradle project from build.gradle");
    }

    @Test
    @DisplayName("detectBuildSystem should detect Gradle project with build.gradle.kts")
    void detectBuildSystem_detectsGradleKts(@TempDir Path tempDir) throws IOException {
        // Create a fake Kotlin Gradle project
        Files.writeString(tempDir.resolve("build.gradle.kts"), "// Kotlin Gradle build file");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.GRADLE, buildSystem,
            "Should detect Gradle project from build.gradle.kts");
    }

    @Test
    @DisplayName("detectBuildSystem should return UNKNOWN for plain project")
    void detectBuildSystem_returnsUnknownForPlainProject(@TempDir Path tempDir) throws IOException {
        // Create a plain Java project with no build file
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), "public class Main {}");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.UNKNOWN, buildSystem,
            "Should return UNKNOWN for project without build file");
    }

    @Test
    @DisplayName("detectBuildSystem should prefer Maven over Gradle when both exist")
    void detectBuildSystem_prefersMavenOverGradle(@TempDir Path tempDir) throws IOException {
        // Create both build files
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        Files.writeString(tempDir.resolve("build.gradle"), "// Gradle");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.MAVEN, buildSystem,
            "Should prefer Maven when both pom.xml and build.gradle exist");
    }

    // ========== Bazel Build System Tests ==========

    @Test
    @DisplayName("detectBuildSystem should detect Bazel project with WORKSPACE")
    void detectBuildSystem_detectsBazelWorkspace(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("WORKSPACE"), "# Bazel workspace");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.BAZEL, buildSystem,
            "Should detect Bazel project from WORKSPACE");
    }

    @Test
    @DisplayName("detectBuildSystem should detect Bazel project with WORKSPACE.bazel")
    void detectBuildSystem_detectsBazelWorkspaceDotBazel(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("WORKSPACE.bazel"), "# Bazel workspace");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.BAZEL, buildSystem,
            "Should detect Bazel project from WORKSPACE.bazel");
    }

    @Test
    @DisplayName("detectBuildSystem should detect Bazel project with MODULE.bazel")
    void detectBuildSystem_detectsBazelModule(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("MODULE.bazel"), "module(name = \"test\")");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.BAZEL, buildSystem,
            "Should detect Bazel project from MODULE.bazel");
    }

    @Test
    @DisplayName("detectBuildSystem should prefer Maven over Bazel when both exist")
    void detectBuildSystem_prefersMavenOverBazel(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        Files.writeString(tempDir.resolve("WORKSPACE"), "# Bazel workspace");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.MAVEN, buildSystem,
            "Should prefer Maven when both pom.xml and WORKSPACE exist");
    }

    @Test
    @DisplayName("detectBuildSystem should prefer Gradle over Bazel when both exist")
    void detectBuildSystem_prefersGradleOverBazel(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), "// Gradle");
        Files.writeString(tempDir.resolve("WORKSPACE"), "# Bazel workspace");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.GRADLE, buildSystem,
            "Should prefer Gradle when both build.gradle and WORKSPACE exist");
    }

    // ========== Eclipse PDE / Eclipse Detection (v1.7.1 / bug #4) ==========

    @Test
    @DisplayName("detectBuildSystem returns ECLIPSE_PDE for project with MANIFEST.MF (Bundle-SymbolicName) + .classpath")
    void detectBuildSystem_eclipsePde_returnsEclipsePde(@TempDir Path tempDir) throws IOException {
        Path metaInf = tempDir.resolve("META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("MANIFEST.MF"),
            "Manifest-Version: 1.0\n"
          + "Bundle-ManifestVersion: 2\n"
          + "Bundle-SymbolicName: com.example.test;singleton:=true\n"
          + "Bundle-Version: 1.0.0.qualifier\n");
        Files.writeString(tempDir.resolve(".classpath"),
            "<?xml version=\"1.0\"?><classpath><classpathentry kind=\"src\" path=\"src\"/></classpath>");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.ECLIPSE_PDE, buildSystem,
            "MANIFEST.MF + Bundle-SymbolicName should classify as Eclipse PDE");
    }

    @Test
    @DisplayName("detectBuildSystem returns ECLIPSE for project with .classpath only (no PDE manifest)")
    void detectBuildSystem_eclipseOnly_returnsEclipse(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(".classpath"),
            "<?xml version=\"1.0\"?><classpath><classpathentry kind=\"src\" path=\"src\"/></classpath>");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.ECLIPSE, buildSystem,
            ".classpath without PDE manifest should classify as plain Eclipse");
    }

    @Test
    @DisplayName("detectBuildSystem prefers Maven over Eclipse when both pom.xml and .classpath exist (hybrid project)")
    void detectBuildSystem_pomAndClasspath_prefersMaven(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        Files.writeString(tempDir.resolve(".classpath"),
            "<?xml version=\"1.0\"?><classpath/>");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.MAVEN, buildSystem,
            "Maven wins over Eclipse for hybrid projects to keep Maven dependency resolution");
    }

    @Test
    @DisplayName("detectBuildSystem prefers Maven over ECLIPSE_PDE when MANIFEST.MF + pom both exist (Tycho-style)")
    void detectBuildSystem_pdeAndPom_prefersMaven(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");
        Path metaInf = tempDir.resolve("META-INF");
        Files.createDirectories(metaInf);
        Files.writeString(metaInf.resolve("MANIFEST.MF"),
            "Manifest-Version: 1.0\n"
          + "Bundle-SymbolicName: com.example.tycho\n");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.MAVEN, buildSystem,
            "Tycho-style PDE+Maven hybrid must classify as MAVEN so dep resolution uses the Maven path");
    }

    @Test
    @DisplayName("detectBuildSystem returns UNKNOWN when MANIFEST.MF exists but has no Bundle-SymbolicName line")
    void detectBuildSystem_manifestWithoutSymbolicName_returnsUnknown(@TempDir Path tempDir) throws IOException {
        Path metaInf = tempDir.resolve("META-INF");
        Files.createDirectories(metaInf);
        // A bare manifest (e.g. from a fat JAR) without Bundle-SymbolicName is NOT a PDE bundle.
        Files.writeString(metaInf.resolve("MANIFEST.MF"),
            "Manifest-Version: 1.0\nMain-Class: com.example.App\n");

        ProjectImporter.BuildSystem buildSystem = importer.detectBuildSystem(tempDir);

        assertEquals(ProjectImporter.BuildSystem.UNKNOWN, buildSystem,
            "MANIFEST.MF without Bundle-SymbolicName is not a PDE bundle");
    }

    @Test
    @DisplayName("countSourceFiles should count Java files in Bazel project with standard layout")
    void countSourceFiles_countsBazelStandardLayout(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("WORKSPACE"), "");
        Path srcDir = tempDir.resolve("src/main/java/com/example");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("App.java"), "package com.example; public class App {}");
        Files.writeString(srcDir.resolve("Lib.java"), "package com.example; public class Lib {}");

        int count = importer.countSourceFiles(tempDir);

        assertEquals(2, count, "Should find 2 Java files in Bazel standard layout");
    }

    // ========== Source File Counting Tests ==========

    @Test
    @DisplayName("countSourceFiles should count Java files in Maven layout")
    void countSourceFiles_countsJavaFiles() {
        int count = importer.countSourceFiles(mavenFixturePath);

        assertTrue(count >= 3,
            "Should find at least 3 Java files (Calculator, HelloWorld, UserService): " + count);
    }

    @Test
    @DisplayName("countSourceFiles should return 0 for empty project")
    void countSourceFiles_returnsZeroForEmpty(@TempDir Path tempDir) {
        int count = importer.countSourceFiles(tempDir);

        assertEquals(0, count, "Should return 0 for project with no Java files");
    }

    @Test
    @DisplayName("countSourceFiles should count files in src layout")
    void countSourceFiles_countsSrcLayout(@TempDir Path tempDir) throws IOException {
        // Create simple src layout
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), "public class Main {}");
        Files.writeString(srcDir.resolve("Helper.java"), "public class Helper {}");

        int count = importer.countSourceFiles(tempDir);

        assertEquals(2, count, "Should find 2 Java files in src directory");
    }

    // ========== Package Finding Tests ==========

    @Test
    @DisplayName("findPackages should find all packages in project")
    void findPackages_findsAllPackages() {
        List<String> packages = importer.findPackages(mavenFixturePath);

        assertFalse(packages.isEmpty(), "Should find at least one package");
        assertTrue(packages.stream().anyMatch(p -> p.contains("com.example")),
            "Should find com.example package");
    }

    @Test
    @DisplayName("findPackages should find nested packages")
    void findPackages_findsNestedPackages() {
        List<String> packages = importer.findPackages(mavenFixturePath);

        // Should find both com.example and com.example.service
        assertTrue(packages.stream().anyMatch(p -> p.contains("service")),
            "Should find service subpackage");
    }

    @Test
    @DisplayName("findPackages should return empty for no packages")
    void findPackages_returnsEmptyForNoPackages(@TempDir Path tempDir) {
        List<String> packages = importer.findPackages(tempDir);

        assertTrue(packages.isEmpty(), "Should return empty list for empty project");
    }

    // ========== Java Project Configuration Tests ==========

    @Test
    @DisplayName("configureJavaProject should add JRE container to classpath")
    void configureJavaProject_addsJreContainer() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("jre-test", mavenFixturePath);
        IJavaProject javaProject = importer.configureJavaProject(project, mavenFixturePath, workspaceManager);

        IClasspathEntry[] classpath = javaProject.getRawClasspath();
        boolean hasJre = Arrays.stream(classpath)
            .anyMatch(entry -> entry.getEntryKind() == IClasspathEntry.CPE_CONTAINER
                && entry.getPath().toString().contains("JRE"));

        assertTrue(hasJre, "Classpath should contain JRE container");
    }

    @Test
    @DisplayName("configureJavaProject should add source folders")
    void configureJavaProject_addsSourceFolders() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("src-test", mavenFixturePath);
        IJavaProject javaProject = importer.configureJavaProject(project, mavenFixturePath, workspaceManager);

        IClasspathEntry[] classpath = javaProject.getRawClasspath();
        boolean hasSource = Arrays.stream(classpath)
            .anyMatch(entry -> entry.getEntryKind() == IClasspathEntry.CPE_SOURCE);

        assertTrue(hasSource, "Classpath should contain source entries");
    }

    @Test
    @DisplayName("configureJavaProject should set output location")
    void configureJavaProject_setsOutputLocation() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("output-test", mavenFixturePath);
        IJavaProject javaProject = importer.configureJavaProject(project, mavenFixturePath, workspaceManager);

        assertNotNull(javaProject.getOutputLocation(), "Output location should be set");
        assertTrue(javaProject.getOutputLocation().toString().contains("bin"),
            "Output location should be bin folder");
    }

    @Test
    @DisplayName("configureJavaProject should return valid Java project")
    void configureJavaProject_returnsValidProject() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("valid-test", mavenFixturePath);
        IJavaProject javaProject = importer.configureJavaProject(project, mavenFixturePath, workspaceManager);

        assertNotNull(javaProject, "Should return a Java project");
        assertTrue(javaProject.exists(), "Java project should exist");
        assertEquals(project, javaProject.getProject(), "Should wrap the same IProject");
    }

    // ========== ADR 0001: pom.xml <sourceDirectory> + .classpath precedence ==========

    @Test
    @DisplayName("readPomSourceDirs returns <sourceDirectory> override resolved against pom dir")
    void readPomSourceDirs_returnsOverride(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
            <?xml version="1.0"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>g</groupId><artifactId>a</artifactId><version>1</version>
                <build>
                    <sourceDirectory>strategies/src</sourceDirectory>
                    <testSourceDirectory>src/test/java</testSourceDirectory>
                </build>
            </project>
            """);

        ProjectImporter.SourceDirs dirs = ProjectImporter.readPomSourceDirs(tempDir.resolve("pom.xml"));

        assertTrue(dirs.srcMain().isPresent(), "<sourceDirectory> should be detected");
        assertEquals(tempDir.resolve("strategies/src").normalize(), dirs.srcMain().get(),
            "Source override should resolve relative to pom directory");
        assertTrue(dirs.srcTest().isPresent(), "<testSourceDirectory> should be detected");
        assertEquals(tempDir.resolve("src/test/java").normalize(), dirs.srcTest().get());
    }

    @Test
    @DisplayName("readPomSourceDirs returns empty when pom has no <build> section")
    void readPomSourceDirs_absentReturnsEmpty(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
            <?xml version="1.0"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>g</groupId><artifactId>a</artifactId><version>1</version>
            </project>
            """);

        ProjectImporter.SourceDirs dirs = ProjectImporter.readPomSourceDirs(tempDir.resolve("pom.xml"));

        assertTrue(dirs.srcMain().isEmpty(), "No override should yield empty srcMain");
        assertTrue(dirs.srcTest().isEmpty(), "No override should yield empty srcTest");
    }

    @Test
    @DisplayName("readPomSourceDirs returns empty when pom.xml is missing")
    void readPomSourceDirs_missingPomReturnsEmpty(@TempDir Path tempDir) {
        ProjectImporter.SourceDirs dirs = ProjectImporter.readPomSourceDirs(tempDir.resolve("pom.xml"));

        assertTrue(dirs.srcMain().isEmpty());
        assertTrue(dirs.srcTest().isEmpty());
    }

    @Test
    @DisplayName("readEclipseClasspath returns src, lib, output entries")
    void readEclipseClasspath_returnsAllKinds(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(".classpath"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <classpath>
                <classpathentry kind="src" path="src"/>
                <classpathentry kind="lib" path="lib/foo.jar"/>
                <classpathentry kind="output" path="bin"/>
                <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
            </classpath>
            """);

        ProjectImporter.ClasspathInfo info = ProjectImporter.readEclipseClasspath(tempDir);

        assertEquals(1, info.srcPaths().size());
        assertEquals(tempDir.resolve("src").normalize(), info.srcPaths().get(0));
        assertEquals(1, info.libPaths().size());
        assertEquals(tempDir.resolve("lib/foo.jar").normalize(), info.libPaths().get(0));
        assertTrue(info.outputPath().isPresent());
        assertEquals(tempDir.resolve("bin").normalize(), info.outputPath().get());
    }

    @Test
    @DisplayName("readEclipseClasspath resolves '..' refs against project parent")
    void readEclipseClasspath_resolvesParentRefs(@TempDir Path tempDir) throws IOException {
        // Mirrors a layout with lib jars in a sibling dir.
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(projectRoot);
        Files.createDirectories(tempDir.resolve("lib"));
        Files.writeString(projectRoot.resolve(".classpath"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <classpath>
                <classpathentry kind="src" path="src"/>
                <classpathentry kind="lib" path="../lib/com.example.model_1.0.0.jar"/>
            </classpath>
            """);

        ProjectImporter.ClasspathInfo info = ProjectImporter.readEclipseClasspath(projectRoot);

        assertEquals(1, info.libPaths().size());
        assertEquals(tempDir.resolve("lib/com.example.model_1.0.0.jar").normalize(),
            info.libPaths().get(0),
            "'..' should be resolved against the project parent directory");
    }

    @Test
    @DisplayName("readEclipseClasspath returns empty when .classpath absent")
    void readEclipseClasspath_missingReturnsEmpty(@TempDir Path tempDir) {
        ProjectImporter.ClasspathInfo info = ProjectImporter.readEclipseClasspath(tempDir);

        assertTrue(info.srcPaths().isEmpty());
        assertTrue(info.libPaths().isEmpty());
        assertTrue(info.outputPath().isEmpty());
    }

    @Test
    @DisplayName("Maven <sourceDirectory> override is honored in countSourceFiles")
    void countSourceFiles_honorsPomSourceDirectoryOverride(@TempDir Path tempDir) throws IOException {
        // Hybrid layout: pom.xml at root declares <sourceDirectory>strategies/src</sourceDirectory>,
        // production code lives there. src/test/java exists but should NOT be the only source seen.
        Files.writeString(tempDir.resolve("pom.xml"), """
            <?xml version="1.0"?>
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>g</groupId><artifactId>a</artifactId><version>1</version>
                <build>
                    <sourceDirectory>strategies/src</sourceDirectory>
                    <testSourceDirectory>src/test/java</testSourceDirectory>
                </build>
            </project>
            """);
        Path prodSrc = tempDir.resolve("strategies/src/com/example");
        Files.createDirectories(prodSrc);
        Files.writeString(prodSrc.resolve("Production.java"), "package com.example; class Production {}");
        Path testSrc = tempDir.resolve("src/test/java/com/example");
        Files.createDirectories(testSrc);
        Files.writeString(testSrc.resolve("Tests.java"), "package com.example; class Tests {}");

        int count = importer.countSourceFiles(tempDir);

        assertEquals(2, count,
            "Both <sourceDirectory> production code and <testSourceDirectory> tests should be counted");
    }

    @Test
    @DisplayName(".classpath src entries are honored when no pom override")
    void countSourceFiles_honorsClasspathSrcEntries(@TempDir Path tempDir) throws IOException {
        // No pom; .classpath declares non-conventional source root.
        Files.writeString(tempDir.resolve(".classpath"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <classpath>
                <classpathentry kind="src" path="custom-src"/>
            </classpath>
            """);
        Path src = tempDir.resolve("custom-src/com/example");
        Files.createDirectories(src);
        Files.writeString(src.resolve("A.java"), "package com.example; class A {}");
        Files.writeString(src.resolve("B.java"), "package com.example; class B {}");

        int count = importer.countSourceFiles(tempDir);

        assertEquals(2, count, ".classpath src=custom-src should be the source root");
    }
}
