package org.goja.core.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11 Phase A — Tycho-aware Maven detection.
 *
 * <p>Verifies the helpers that drive the {@code addDependencyEntries} gate
 * which prevents {@code mvn dependency:build-classpath} from running on
 * projects whose pom declares a Tycho packaging (where the goal returns
 * wrong/empty results because Tycho deps come from MANIFEST.MF + target
 * platform, not pom &lt;dependencies&gt;).</p>
 */
class ProjectImporterTychoPackagingTest {

    private static final String TYCHO_POM =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
        "  <modelVersion>4.0.0</modelVersion>\n" +
        "  <groupId>org.goja.fixture</groupId>\n" +
        "  <artifactId>tycho-bundle</artifactId>\n" +
        "  <version>1.0.0</version>\n" +
        "  <packaging>eclipse-plugin</packaging>\n" +
        "</project>\n";

    private static final String JAR_POM =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">\n" +
        "  <modelVersion>4.0.0</modelVersion>\n" +
        "  <groupId>org.goja.fixture</groupId>\n" +
        "  <artifactId>plain-jar</artifactId>\n" +
        "  <version>1.0.0</version>\n" +
        "</project>\n";

    @Test
    @DisplayName("readPomPackaging returns 'eclipse-plugin' for a Tycho pom")
    void readPomPackaging_returnsEclipsePlugin(@TempDir Path tempDir) throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, TYCHO_POM);

        Optional<String> packaging = ProjectImporter.readPomPackaging(pom);

        assertTrue(packaging.isPresent(), "<packaging> should be parsed from the Tycho pom");
        assertEquals("eclipse-plugin", packaging.get());
    }

    @Test
    @DisplayName("readPomPackaging returns empty when <packaging> is omitted (Maven default 'jar')")
    void readPomPackaging_returnsEmpty_whenAbsent(@TempDir Path tempDir) throws IOException {
        Path pom = tempDir.resolve("pom.xml");
        Files.writeString(pom, JAR_POM);

        Optional<String> packaging = ProjectImporter.readPomPackaging(pom);

        assertTrue(packaging.isEmpty(),
            "Maven treats absent <packaging> as 'jar' — helper signals that with Optional.empty()");
    }

    @Test
    @DisplayName("isTychoProject gates the Maven shell-out: true for eclipse-plugin pom, false for jar pom")
    void tycho_skipsMavenShellOut(@TempDir Path tempDir) throws IOException {
        Path tychoDir = Files.createDirectory(tempDir.resolve("tycho"));
        Files.writeString(tychoDir.resolve("pom.xml"), TYCHO_POM);

        Path jarDir = Files.createDirectory(tempDir.resolve("jar"));
        Files.writeString(jarDir.resolve("pom.xml"), JAR_POM);

        Path noPomDir = Files.createDirectory(tempDir.resolve("no-pom"));

        assertTrue(ProjectImporter.isTychoProject(tychoDir),
            "Tycho-packaged project must be detected so addDependencyEntries skips mvn dependency:build-classpath");
        assertFalse(ProjectImporter.isTychoProject(jarDir),
            "Plain jar pom must not be flagged as Tycho");
        assertFalse(ProjectImporter.isTychoProject(noPomDir),
            "Project without pom.xml must not be flagged as Tycho");
    }
}
