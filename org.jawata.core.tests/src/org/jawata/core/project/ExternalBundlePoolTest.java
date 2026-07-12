package org.jawata.core.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 23 (D7) — the external bundle pool's parsing + indexing seams:
 * quoted-comma header splitting, OSGi-ish version preference, symbolic-name
 * and Export-Package indexing over generated jars.
 */
class ExternalBundlePoolTest {

    @Test
    @DisplayName("splitTopLevel honours quoted commas (uses/version-range attributes)")
    void splitTopLevel_quotedCommas() {
        List<String> parts = ExternalBundlePool.splitTopLevel(
            "org.foo;uses:=\"a.b,c.d\";version=\"1.0\",org.bar;version=\"[1.0,2.0)\",org.baz");
        assertEquals(3, parts.size(), "got: " + parts);
        assertTrue(parts.get(0).startsWith("org.foo"), "got: " + parts);
        assertTrue(parts.get(1).startsWith("org.bar"), "got: " + parts);
        assertEquals("org.baz", parts.get(2).trim(), "got: " + parts);
    }

    @Test
    @DisplayName("version compare: numeric segments beat lexicographic")
    void versionCompare_numeric() {
        assertTrue(ExternalBundlePool.compareVersions("3.10.0", "3.9.5") > 0);
        assertTrue(ExternalBundlePool.compareVersions("1.0.0.qualifier", "1.0.0") > 0);
        assertEquals(0, ExternalBundlePool.compareVersions("2.1", "2.1.0"));
    }

    @Test
    @DisplayName("indexing maps symbolic name (highest version) + exported packages to jars")
    void indexing_symbolicNamesAndExports(@TempDir Path pool) throws IOException {
        writeBundleJar(pool.resolve("libx-1.0.0.jar"), "org.example.libx", "1.0.0",
            "org.example.libx.api");
        writeBundleJar(pool.resolve("libx-2.3.0.jar"), "org.example.libx", "2.3.0",
            "org.example.libx.api");
        writeBundleJar(pool.resolve("liby-1.0.0.jar"), "org.example.liby", "1.0.0",
            "org.example.liby.spi;version=\"1.0\";uses:=\"org.example.libx.api,java.util\"");

        ExternalBundlePool indexed = ExternalBundlePool.index(List.of(pool));

        assertEquals("libx-2.3.0.jar",
            indexed.bundleJar("org.example.libx").orElseThrow().getFileName().toString(),
            "highest version must win");
        assertEquals("liby-1.0.0.jar",
            indexed.packageProvider("org.example.liby.spi").orElseThrow().getFileName().toString(),
            "exported package (with quoted uses) must map to its jar");
        assertTrue(indexed.bundleJar("org.example.absent").isEmpty());
        assertTrue(indexed.packageProvider("no.such.pkg").isEmpty());
    }

    private static void writeBundleJar(Path jar, String symbolicName, String version,
                                       String exportPackage) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Bundle-ManifestVersion", "2");
        manifest.getMainAttributes().putValue("Bundle-SymbolicName", symbolicName);
        manifest.getMainAttributes().putValue("Bundle-Version", version);
        manifest.getMainAttributes().putValue("Export-Package", exportPackage);
        try (OutputStream out = Files.newOutputStream(jar);
             JarOutputStream jos = new JarOutputStream(out, manifest)) {
            jos.putNextEntry(new JarEntry("placeholder.txt"));
            jos.write("x".getBytes());
            jos.closeEntry();
        }
    }
}
