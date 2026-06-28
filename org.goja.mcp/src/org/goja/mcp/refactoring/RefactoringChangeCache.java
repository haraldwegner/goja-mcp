package org.goja.mcp.refactoring;

import org.eclipse.ltk.core.refactoring.Change;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Sprint 14b — session-scoped cache for refactoring {@link Change} objects.
 *
 * <p>Two kinds of entries share one id space:</p>
 * <ul>
 *   <li>{@link Kind#STAGED} — a Change built with {@code auto_apply: false},
 *       waiting for {@code apply_refactoring(changeId)}.</li>
 *   <li>{@link Kind#UNDO} — the undo-Change captured from a performed
 *       refactoring, waiting for {@code undo_refactoring(undoChangeId)}.</li>
 * </ul>
 *
 * <p>Entries expire after {@link #DEFAULT_TTL_MILLIS} (lazily, on access) and
 * the cache is capacity-capped with LRU eviction. Evicted/expired Changes are
 * {@link Change#dispose() disposed} so LTK buffers don't leak. All methods are
 * synchronized — the HTTP transport dispatches tool calls concurrently.</p>
 */
public final class RefactoringChangeCache {

    private static final Logger log = LoggerFactory.getLogger(RefactoringChangeCache.class);

    public static final long DEFAULT_TTL_MILLIS = 60L * 60L * 1000L;
    public static final int DEFAULT_CAPACITY = 50;

    public enum Kind { STAGED, UNDO }

    /**
     * Immutable cache entry. {@code diff} and {@code filePaths} are captured
     * at put-time so {@code inspect_refactoring} never has to touch the
     * (possibly already invalid) Change to answer.
     */
    public record Entry(
        String id,
        Kind kind,
        Change change,
        String summary,
        String diff,
        List<String> filePaths,
        long insertedAtMillis
    ) {}

    /** Access-ordered for LRU semantics: get() refreshes recency. */
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final long ttlMillis;
    private final int capacity;
    private final LongSupplier clock;

    public RefactoringChangeCache() {
        this(DEFAULT_TTL_MILLIS, DEFAULT_CAPACITY, System::currentTimeMillis);
    }

    /** Test seam: injectable TTL, capacity, and clock. */
    public RefactoringChangeCache(long ttlMillis, int capacity, LongSupplier clock) {
        this.ttlMillis = ttlMillis;
        this.capacity = capacity;
        this.clock = clock;
    }

    /** Stores the change and returns its freshly minted id. */
    public synchronized String put(Kind kind, Change change, String summary,
                                   String diff, List<String> filePaths) {
        evictExpired();
        String id = UUID.randomUUID().toString();
        entries.put(id, new Entry(id, kind, change, summary,
            diff == null ? "" : diff,
            filePaths == null ? List.of() : List.copyOf(filePaths),
            clock.getAsLong()));
        evictOverflow();
        return id;
    }

    /** Non-consuming lookup (any kind) — used by inspect_refactoring. */
    public synchronized Optional<Entry> peek(String id) {
        evictExpired();
        return Optional.ofNullable(entries.get(id));
    }

    /**
     * Consuming lookup. Returns empty when the id is unknown, expired,
     * already consumed, or held by an entry of a different kind — apply and
     * undo are one-shot operations.
     */
    public synchronized Optional<Entry> take(String id, Kind expectedKind) {
        evictExpired();
        Entry entry = entries.get(id);
        if (entry == null || entry.kind() != expectedKind) {
            return Optional.empty();
        }
        entries.remove(id);
        return Optional.of(entry);
    }

    public synchronized int size() {
        evictExpired();
        return entries.size();
    }

    private void evictExpired() {
        long now = clock.getAsLong();
        Iterator<Entry> it = entries.values().iterator();
        List<Entry> evicted = new ArrayList<>();
        while (it.hasNext()) {
            Entry entry = it.next();
            if (now - entry.insertedAtMillis() > ttlMillis) {
                evicted.add(entry);
                it.remove();
            }
        }
        evicted.forEach(RefactoringChangeCache::dispose);
    }

    private void evictOverflow() {
        Iterator<Entry> it = entries.values().iterator();
        while (entries.size() > capacity && it.hasNext()) {
            Entry eldest = it.next();
            it.remove();
            dispose(eldest);
            log.debug("Change cache over capacity — evicted {} ({})", eldest.id(), eldest.summary());
        }
    }

    private static void dispose(Entry entry) {
        try {
            entry.change().dispose();
        } catch (RuntimeException e) {
            log.debug("Change.dispose() failed for {}: {}", entry.id(), e.getMessage());
        }
    }
}
