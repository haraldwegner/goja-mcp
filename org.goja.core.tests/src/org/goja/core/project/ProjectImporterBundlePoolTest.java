package org.goja.core.project;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.goja.core.JdtServiceImpl;
import org.goja.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 11 Phase B — workspace bundle pool / Require-Bundle resolution.
 *
 * <p>Two PDE fixture bundles ({@code pde-bundle-a}, {@code pde-bundle-b})
 * live under {@code test-resources/sample-projects/}. Bundle A's
 * {@code MANIFEST.MF} declares {@code Require-Bundle: org.goja.fixture.b}
 * — when both bundles load into the same workspace, A's classpath should
 * gain a project-typed entry for B; when only A loads, the require stays
 * unresolved and the load still succeeds.</p>
 */
class ProjectImporterBundlePoolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("readManifestSymbolicName strips ;singleton:=true and other directives")
    void readManifestSymbolicName_stripsDirectives(@TempDir Path tempDir) throws IOException {
        Path metaInf = Files.createDirectory(tempDir.resolve("META-INF"));
        Files.writeString(metaInf.resolve("MANIFEST.MF"),
            "Manifest-Version: 1.0\r\n" +
            "Bundle-ManifestVersion: 2\r\n" +
            "Bundle-SymbolicName: com.foo;singleton:=true\r\n" +
            "Bundle-Version: 1.0.0\r\n");

        Optional<String> name = ProjectImporter.readManifestSymbolicName(tempDir);

        assertTrue(name.isPresent());
        assertEquals("com.foo", name.get(),
            "Symbolic name must be stripped of trailing ;directive=value pairs");
    }

    @Test
    @DisplayName("readManifestRequireBundle parses the multi-line OSGi header (continuation lines)")
    void readManifestRequireBundle_parsesMultiLineHeader(@TempDir Path tempDir) throws IOException {
        Path metaInf = Files.createDirectory(tempDir.resolve("META-INF"));
        // OSGi continuation: a leading single space joins the line to the
        // previous header value. java.util.jar.Manifest handles this for us;
        // we just have to honour the comma-separated entries afterwards.
        Files.writeString(metaInf.resolve("MANIFEST.MF"),
            "Manifest-Version: 1.0\r\n" +
            "Bundle-ManifestVersion: 2\r\n" +
            "Bundle-SymbolicName: com.foo\r\n" +
            "Require-Bundle: com.example.first;bundle-version=\"1.0.0\",\r\n" +
            " com.example.second;visibility:=reexport,\r\n" +
            " com.example.third\r\n");

        List<String> required = ProjectImporter.readManifestRequireBundle(tempDir);

        assertEquals(List.of("com.example.first", "com.example.second", "com.example.third"), required);
    }

    @Test
    @DisplayName("Require-Bundle resolves to a project entry when the sibling is loaded into the workspace")
    void requireBundle_resolvesWithinWorkspace() throws Exception {
        // Load B FIRST so its symbolic name lands in the bundle pool before
        // A's classpath is configured. Production users are expected to load
        // dependents bottom-up; the workspace.json watcher orders writes from
        // the manager to honour this naturally.
        JdtServiceImpl service = helper.loadProject("pde-bundle-b");
        service.addProject(helper.getFixturePath("pde-bundle-a"));

        IJavaProject aProject = service.getProject("pde-bundle-a")
            .orElseThrow(() -> new AssertionError("pde-bundle-a should be loaded"))
            .javaProject();

        IClasspathEntry[] entries = aProject.getRawClasspath();
        boolean foundProjectEntry = false;
        for (IClasspathEntry entry : entries) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT
                && entry.getPath().lastSegment().contains("pde-bundle-b")) {
                foundProjectEntry = true;
                break;
            }
        }
        assertTrue(foundProjectEntry,
            "Bundle A's classpath should contain a project entry for sibling bundle B "
            + "(resolved via Require-Bundle); raw entries: "
            + classpathEntriesAsString(entries));
    }

    @Test
    @DisplayName("Require-Bundle for a bundle outside the workspace pool is silently skipped (load still succeeds)")
    void requireBundle_outsideWorkspace_logsAndContinues() throws Exception {
        // Load A WITHOUT loading B. A's manifest also declares
        // Require-Bundle: org.eclipse.osgi (a system bundle, never in the
        // workspace pool) plus org.goja.fixture.b (also absent here).
        // Both should be skipped without aborting the load.
        JdtServiceImpl service = helper.loadProject("pde-bundle-a");

        IJavaProject aProject = service.getProject("pde-bundle-a")
            .orElseThrow(() -> new AssertionError("pde-bundle-a should be loaded"))
            .javaProject();

        IClasspathEntry[] entries = aProject.getRawClasspath();
        for (IClasspathEntry entry : entries) {
            if (entry.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                assertFalse(entry.getPath().lastSegment().contains("pde-bundle-b"),
                    "B is not loaded — must not appear as a project entry in A's classpath");
            }
        }
    }

    private static String classpathEntriesAsString(IClasspathEntry[] entries) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append("kind=").append(entries[i].getEntryKind())
              .append(" path=").append(entries[i].getPath());
        }
        return sb.append("]").toString();
    }
}
