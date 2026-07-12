package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Sprint 22d Stage A (carried obligation D6): the {@code goja.*} property
 * fallbacks — promised removed since v2.7.1, deferred through v2.8.x — are
 * GONE. A configuration supplied only under a legacy name must be IGNORED:
 * the default wins, never the legacy value. This class probes the store-dir
 * seam; the workspace-root seam is probed by
 * {@code org.jawata.mcp.LegacyGojaWorkspaceRootIgnoredTest}.
 */
class LegacyGojaNamesIgnoredTest {

    private static final String JAWATA_DIR = "jawata.experience.shared.dir";
    private static final String GOJA_DIR = "goja.experience.shared.dir";

    private String savedJawataDir;
    private String savedGojaDir;

    @BeforeEach
    void saveProperties() {
        savedJawataDir = System.getProperty(JAWATA_DIR);
        savedGojaDir = System.getProperty(GOJA_DIR);
        System.clearProperty(JAWATA_DIR);
        System.clearProperty(GOJA_DIR);
    }

    @AfterEach
    void restoreProperties() {
        restore(JAWATA_DIR, savedJawataDir);
        restore(GOJA_DIR, savedGojaDir);
    }

    static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    @DisplayName("goja.experience.shared.dir is ignored — the XDG/default resolution wins")
    void legacySharedDirPropertyIsIgnored() {
        System.setProperty(GOJA_DIR, "/tmp/legacy-goja-store-dir");
        Path resolved = H2ExperienceStore.sharedStoreDir();
        assertNotEquals(Path.of("/tmp/legacy-goja-store-dir"), resolved,
            "a value supplied ONLY under the legacy goja name must not be honored");
    }

    @Test
    @DisplayName("jawata.experience.shared.dir still wins when set")
    void jawataSharedDirPropertyStillWorks() {
        System.setProperty(JAWATA_DIR, "/tmp/jawata-store-dir");
        System.setProperty(GOJA_DIR, "/tmp/legacy-goja-store-dir");
        assertEquals(Path.of("/tmp/jawata-store-dir"), H2ExperienceStore.sharedStoreDir(),
            "the jawata name is the only accepted property");
    }
}
