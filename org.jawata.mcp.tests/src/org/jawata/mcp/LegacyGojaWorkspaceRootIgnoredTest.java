package org.jawata.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Sprint 22d Stage A (carried obligation D6), workspace-root half: the
 * {@code goja.workspace.root} property fallback is GONE — a root supplied
 * only under the legacy name is ignored by
 * {@link JawataApplication#resolveWorkspaceRoot}.
 */
class LegacyGojaWorkspaceRootIgnoredTest {

    private static final String JAWATA_ROOT = "jawata.workspace.root";
    private static final String GOJA_ROOT = "goja.workspace.root";

    private String savedJawataRoot;
    private String savedGojaRoot;

    @BeforeEach
    void saveProperties() {
        savedJawataRoot = System.getProperty(JAWATA_ROOT);
        savedGojaRoot = System.getProperty(GOJA_ROOT);
        System.clearProperty(JAWATA_ROOT);
        System.clearProperty(GOJA_ROOT);
    }

    @AfterEach
    void restoreProperties() {
        restore(JAWATA_ROOT, savedJawataRoot);
        restore(GOJA_ROOT, savedGojaRoot);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    @DisplayName("goja.workspace.root is ignored — resolution falls through, never the legacy value")
    void legacyWorkspaceRootPropertyIsIgnored() {
        System.setProperty(GOJA_ROOT, "/tmp/legacy-goja-root");
        assertNull(JawataApplication.resolveWorkspaceRoot(null),
            "with no jawata.workspace.root and no data dir, resolution must yield null — "
                + "never the legacy goja value");
    }

    @Test
    @DisplayName("jawata.workspace.root still wins when set")
    void jawataWorkspaceRootStillWorks() {
        System.setProperty(JAWATA_ROOT, "/tmp/jawata-root");
        System.setProperty(GOJA_ROOT, "/tmp/legacy-goja-root");
        assertEquals(Path.of("/tmp/jawata-root"),
            JawataApplication.resolveWorkspaceRoot(null),
            "the jawata name is the only accepted property");
    }
}
