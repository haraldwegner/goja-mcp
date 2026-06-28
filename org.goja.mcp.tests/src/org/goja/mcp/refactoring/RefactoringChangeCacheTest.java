package org.goja.mcp.refactoring;

import org.eclipse.ltk.core.refactoring.NullChange;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RefactoringChangeCacheTest {

    /** Mutable fake clock — index 0 holds "now" in millis. */
    private final long[] now = {0L};

    private RefactoringChangeCache cache(long ttlMillis, int capacity) {
        return new RefactoringChangeCache(ttlMillis, capacity, () -> now[0]);
    }

    @Test
    @DisplayName("put then peek round-trips the entry")
    void putThenPeek_roundTrips() {
        RefactoringChangeCache cache = cache(1000, 10);
        String id = cache.put(RefactoringChangeCache.Kind.STAGED,
            new NullChange("c1"), "summary-1", "diff-1", List.of("/tmp/A.java"));

        Optional<RefactoringChangeCache.Entry> entry = cache.peek(id);
        assertTrue(entry.isPresent());
        assertEquals("summary-1", entry.get().summary());
        assertEquals("diff-1", entry.get().diff());
        assertEquals(List.of("/tmp/A.java"), entry.get().filePaths());
        assertEquals(RefactoringChangeCache.Kind.STAGED, entry.get().kind());
        assertEquals(1, cache.size());
    }

    @Test
    @DisplayName("take consumes the entry — second take is empty")
    void take_consumes() {
        RefactoringChangeCache cache = cache(1000, 10);
        String id = cache.put(RefactoringChangeCache.Kind.UNDO,
            new NullChange("c1"), "s", "", List.of());

        assertTrue(cache.take(id, RefactoringChangeCache.Kind.UNDO).isPresent());
        assertTrue(cache.take(id, RefactoringChangeCache.Kind.UNDO).isEmpty());
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("take with the wrong kind is empty and does not consume")
    void take_wrongKind_isEmptyAndNonConsuming() {
        RefactoringChangeCache cache = cache(1000, 10);
        String id = cache.put(RefactoringChangeCache.Kind.STAGED,
            new NullChange("c1"), "s", "", List.of());

        assertTrue(cache.take(id, RefactoringChangeCache.Kind.UNDO).isEmpty());
        assertTrue(cache.peek(id).isPresent(), "wrong-kind take must not consume the entry");
    }

    @Test
    @DisplayName("entries expire after the TTL")
    void entries_expireAfterTtl() {
        RefactoringChangeCache cache = cache(1000, 10);
        String id = cache.put(RefactoringChangeCache.Kind.STAGED,
            new NullChange("c1"), "s", "", List.of());

        now[0] = 1000;
        assertTrue(cache.peek(id).isPresent(), "at exactly TTL the entry is still alive");

        now[0] = 1001;
        assertTrue(cache.peek(id).isEmpty(), "past TTL the entry is evicted");
        assertEquals(0, cache.size());
    }

    @Test
    @DisplayName("capacity overflow evicts the least recently used entry")
    void overflow_evictsLeastRecentlyUsed() {
        RefactoringChangeCache cache = cache(10_000, 2);
        String first = cache.put(RefactoringChangeCache.Kind.STAGED,
            new NullChange("c1"), "first", "", List.of());
        String second = cache.put(RefactoringChangeCache.Kind.STAGED,
            new NullChange("c2"), "second", "", List.of());

        // Touch `first` so `second` becomes the LRU victim.
        assertTrue(cache.peek(first).isPresent());

        String third = cache.put(RefactoringChangeCache.Kind.STAGED,
            new NullChange("c3"), "third", "", List.of());

        assertEquals(2, cache.size());
        assertTrue(cache.peek(first).isPresent(), "recently touched entry survives");
        assertTrue(cache.peek(second).isEmpty(), "LRU entry is evicted");
        assertTrue(cache.peek(third).isPresent());
    }

    @Test
    @DisplayName("unknown id is empty for both peek and take")
    void unknownId_isEmpty() {
        RefactoringChangeCache cache = cache(1000, 10);
        assertTrue(cache.peek("no-such-id").isEmpty());
        assertTrue(cache.take("no-such-id", RefactoringChangeCache.Kind.STAGED).isEmpty());
    }
}
