package org.goja.core.project;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.goja.core.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 14 Stage 0 — bugs.md #7: {@link ProjectImporter#configureJavaProject}
 * must dedupe duplicate library entries before passing the array to
 * {@code IJavaProject.setRawClasspath()}. JDT throws
 * {@code "Build path contains duplicate entry"} on any dup; the fork's own
 * multi-module repo trips this because the same jar is contributed via
 * {@code .classpath kind="lib"} more than once (or via {@code .classpath}
 * AND a build-system path).
 */
class ProjectImporterClasspathDedupeTest {

    @Test
    @DisplayName("Project with .classpath listing the same lib jar twice loads without DUPLICATE_CLASSPATH_ENTRY")
    void loadProject_withDuplicateClasspathEntries_succeeds(@TempDir Path tempDir) throws Exception {
        Path libDir = Files.createDirectory(tempDir.resolve("lib"));
        Path dupJar = libDir.resolve("dup.jar");
        Files.write(dupJar, new byte[0]);

        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve(".classpath"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <classpath>
              <classpathentry kind="src" path="src"/>
              <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
              <classpathentry kind="lib" path="lib/dup.jar"/>
              <classpathentry kind="lib" path="lib/dup.jar"/>
              <classpathentry kind="output" path="bin"/>
            </classpath>
            """);

        // Before the dedupe fix, JDT's setRawClasspath throws
        // "Build path contains duplicate entry" and the load fails with an
        // INTERNAL_ERROR. After the fix, the duplicate is collapsed and the
        // load succeeds.
        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(tempDir);

        IJavaProject jp = service.allProjects().iterator().next().javaProject();
        IClasspathEntry[] entries = jp.getRawClasspath();

        long dupJarCount = 0;
        for (IClasspathEntry entry : entries) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY
                && entry.getPath().toString().endsWith("dup.jar")) {
                dupJarCount++;
            }
        }
        assertEquals(1, dupJarCount,
            "dup.jar must appear exactly once in the raw classpath; saw "
                + dupJarCount + " entries");
    }

    @Test
    @DisplayName("Distinct .classpath lib entries are preserved (dedupe doesn't over-strip)")
    void loadProject_withDistinctClasspathEntries_keepsAll(@TempDir Path tempDir) throws Exception {
        Path libDir = Files.createDirectory(tempDir.resolve("lib"));
        Files.write(libDir.resolve("a.jar"), new byte[0]);
        Files.write(libDir.resolve("b.jar"), new byte[0]);

        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve(".classpath"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <classpath>
              <classpathentry kind="src" path="src"/>
              <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
              <classpathentry kind="lib" path="lib/a.jar"/>
              <classpathentry kind="lib" path="lib/b.jar"/>
              <classpathentry kind="output" path="bin"/>
            </classpath>
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(tempDir);

        IJavaProject jp = service.allProjects().iterator().next().javaProject();
        IClasspathEntry[] entries = jp.getRawClasspath();

        Set<String> seenLibs = new HashSet<>();
        for (IClasspathEntry entry : entries) {
            if (entry.getEntryKind() != IClasspathEntry.CPE_LIBRARY) continue;
            String path = entry.getPath().toString();
            if (path.endsWith("a.jar")) seenLibs.add("a.jar");
            else if (path.endsWith("b.jar")) seenLibs.add("b.jar");
        }
        assertTrue(seenLibs.contains("a.jar"), "a.jar must be present in raw classpath");
        assertTrue(seenLibs.contains("b.jar"), "b.jar must be present in raw classpath");
    }

    @Test
    @DisplayName("Project with no .classpath libraries loads cleanly (regression guard for src-only project)")
    void loadProject_withNoLibEntries_unaffected(@TempDir Path tempDir) throws Exception {
        Files.createDirectory(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve(".classpath"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <classpath>
              <classpathentry kind="src" path="src"/>
              <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
              <classpathentry kind="output" path="bin"/>
            </classpath>
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(tempDir);

        IJavaProject jp = service.allProjects().iterator().next().javaProject();
        IClasspathEntry[] entries = jp.getRawClasspath();

        for (IClasspathEntry entry : entries) {
            assertFalse(entry.getEntryKind() == IClasspathEntry.CPE_LIBRARY,
                "src-only project should have no library entries in raw classpath; saw "
                    + entry.getPath());
        }
    }
}
