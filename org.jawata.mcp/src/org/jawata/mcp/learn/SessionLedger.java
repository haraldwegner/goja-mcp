package org.jawata.mcp.learn;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-session call ledger (Sprint 26): the resident's memory of what each MCP
 * session has done recently — the substrate for the server-side checks (a seat
 * outcome arriving without the seat's gate calls; an ask-shaped pattern about
 * to be summarized upward). Bounded both ways: at most {@link #MAX_SESSIONS}
 * sessions (least-recently-used evicted) and {@link #MAX_CALLS} calls per
 * session (oldest dropped) — a ledger, not a log.
 */
public final class SessionLedger {

    /** One recorded call: what ran, whether it succeeded, how many files it modified. */
    public record CallRecord(String tool, boolean ok, int filesModified, long ts) {
    }

    /** Sprint 26 D4: notified when a session is LRU-evicted (its "end"). */
    public interface EvictionListener {
        void onEvicted(String sessionId, List<CallRecord> calls);
    }

    private EvictionListener evictionListener;

    public void setEvictionListener(EvictionListener listener) {
        this.evictionListener = listener;
    }

    static final int MAX_SESSIONS = 64;
    static final int MAX_CALLS = 200;

    private final LinkedHashMap<String, Deque<CallRecord>> sessions =
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<CallRecord>> eldest) {
                boolean evict = size() > MAX_SESSIONS;
                if (evict && evictionListener != null) {
                    try {
                        evictionListener.onEvicted(eldest.getKey(),
                            new ArrayList<>(eldest.getValue()));
                    } catch (Exception e) {
                        // an eviction listener must never break the ledger
                    }
                }
                return evict;
            }
        };

    public synchronized void record(String sessionId, CallRecord record) {
        Deque<CallRecord> calls = sessions.computeIfAbsent(
            sessionId == null ? "local" : sessionId, k -> new ArrayDeque<>());
        calls.addLast(record);
        while (calls.size() > MAX_CALLS) {
            calls.removeFirst();
        }
    }

    /** The session's recorded calls, oldest first (a copy — safe to iterate). */
    public synchronized List<CallRecord> calls(String sessionId) {
        Deque<CallRecord> calls = sessions.get(sessionId == null ? "local" : sessionId);
        return calls == null ? List.of() : new ArrayList<>(calls);
    }

    public synchronized int sessionCount() {
        return sessions.size();
    }
}
