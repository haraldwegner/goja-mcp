package org.goja.core;

import org.goja.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 14 (bugs.md #11): {@code JdtServiceImpl} must track recently-dropped
 * project keys with a TTL so a stale caller gets a distinct
 * {@code PROJECT_KEY_DROPPED} error (with the drop timestamp) rather than a
 * generic {@code INVALID_PARAMETER}.
 */
class JdtServiceImplDroppedKeyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("removeProject then wasRecentlyDropped: returns drop timestamp")
    void removeProject_thenWasRecentlyDropped_returnsTimestamp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        String key = service.allProjects().iterator().next().projectKey();
        long beforeRemove = System.currentTimeMillis();

        boolean removed = service.removeProject(key);
        assertTrue(removed, "removeProject should report success for the loaded key");

        long afterRemove = System.currentTimeMillis();
        Optional<Long> dropped = service.wasRecentlyDropped(key);
        assertTrue(dropped.isPresent(),
            "wasRecentlyDropped must return the drop timestamp for a key we just removed");
        long ts = dropped.get();
        assertTrue(ts >= beforeRemove && ts <= afterRemove,
            "drop timestamp must be within the remove window; got " + ts);
    }

    @Test
    @DisplayName("never-loaded key returns empty (no false-positive PROJECT_KEY_DROPPED)")
    void neverLoadedKey_returnsEmpty() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        Optional<Long> dropped = service.wasRecentlyDropped("frobnicate-never-existed");
        assertFalse(dropped.isPresent(),
            "key that never existed must fall through to INVALID_PARAMETER, not PROJECT_KEY_DROPPED");
    }

    @Test
    @DisplayName("re-add same key clears the dropped marker")
    void reAddSameKey_clearsDroppedMarker() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        String key = service.allProjects().iterator().next().projectKey();
        java.nio.file.Path projectPath = service.allProjects().iterator().next().projectRoot();

        service.removeProject(key);
        assertTrue(service.wasRecentlyDropped(key).isPresent(),
            "post-remove the dropped marker is present");

        service.addProject(projectPath);
        assertFalse(service.wasRecentlyDropped(key).isPresent(),
            "re-adding the same key must clear the dropped marker so the live project isn't shadowed");
    }

    @Test
    @DisplayName("dropped entry beyond TTL is evicted and reads as empty (INVALID_PARAMETER path)")
    void expiredDropped_evictedAndReturnsEmpty() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        String key = service.allProjects().iterator().next().projectKey();

        service.removeProject(key);
        // Simulate TTL expiry without sleeping for 5 min.
        service.expireDroppedKeyForTest(key);

        Optional<Long> dropped = service.wasRecentlyDropped(key);
        assertFalse(dropped.isPresent(),
            "expired dropped-key entry must fall through to INVALID_PARAMETER (empty)");
    }
}
