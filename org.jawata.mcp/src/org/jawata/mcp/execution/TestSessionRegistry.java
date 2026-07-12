package org.jawata.mcp.execution;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sprint 23 (Stage 2) — bookkeeping for async test sessions: id → live
 * handle + progress counters + a bounded ring of recent runner events. The
 * poll surface behind {@code run_tests action=start/status/cancel}.
 *
 * <p>Progress is fed straight from the runner's event stream (the
 * {@link ForkedTestRunner.Spec#eventConsumer} seam); the final result stays
 * available after completion until the session is evicted (bounded count,
 * oldest-finished first).</p>
 */
public final class TestSessionRegistry {

    private static final int MAX_RECENT_EVENTS = 200;
    private static final int MAX_RETAINED_SESSIONS = 32;

    public static final class Session {
        public final String id;
        public final long startedMillis = System.currentTimeMillis();
        /** Attached right after the fork; the event consumer must exist first. */
        volatile ForkedTestRunner.RunningSession handle;
        /** Display label ("junit5", …) captured at start for status responses. */
        public volatile String frameworkLabel = "";
        public final AtomicInteger classesStarted = new AtomicInteger();
        public final AtomicInteger classesFinished = new AtomicInteger();
        public final AtomicInteger testsFinished = new AtomicInteger();
        public volatile int plannedTests = -1;
        private final ArrayDeque<Map<String, Object>> recentEvents = new ArrayDeque<>();

        Session(String id) {
            this.id = id;
        }

        public void attach(ForkedTestRunner.RunningSession handle) {
            this.handle = handle;
        }

        public void onEvent(JsonNode event) {
            switch (event.path("e").asText()) {
                case "run-start" -> plannedTests = event.path("plannedTests").asInt(-1);
                case "class-start" -> classesStarted.incrementAndGet();
                case "class-finish" -> classesFinished.incrementAndGet();
                case "test-finish" -> testsFinished.incrementAndGet();
                default -> { }
            }
            Map<String, Object> flat = new LinkedHashMap<>();
            event.properties().forEach(en -> flat.put(en.getKey(),
                en.getValue().isNumber() ? en.getValue().numberValue() : en.getValue().asText()));
            synchronized (recentEvents) {
                recentEvents.addLast(flat);
                if (recentEvents.size() > MAX_RECENT_EVENTS) recentEvents.removeFirst();
            }
        }

        public List<Map<String, Object>> recentEvents() {
            synchronized (recentEvents) {
                return new ArrayList<>(recentEvents);
            }
        }

        public String state() {
            ForkedTestRunner.RunningSession h = handle;
            if (h == null || !h.isDone()) return "RUNNING";
            return h.isCancelRequested() ? "CANCELLED" : "FINISHED";
        }

        public ForkedTestRunner.Result resultNow() {
            ForkedTestRunner.RunningSession h = handle;
            return h == null ? null : h.resultNow();
        }

        public void cancel() {
            ForkedTestRunner.RunningSession h = handle;
            if (h != null) h.cancel();
        }

        boolean isDone() {
            ForkedTestRunner.RunningSession h = handle;
            return h != null && h.isDone();
        }
    }

    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public Session create() {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Session s = new Session(id);
        sessions.put(id, s);
        evictIfCrowded();
        return s;
    }

    public void remove(String id) {
        sessions.remove(id);
    }

    public Session get(String id) {
        return id == null ? null : sessions.get(id);
    }

    /** Keep the retained set bounded: drop the oldest FINISHED sessions first. */
    private void evictIfCrowded() {
        if (sessions.size() <= MAX_RETAINED_SESSIONS) return;
        sessions.values().stream()
            .filter(Session::isDone)
            .sorted((a, b) -> Long.compare(a.startedMillis, b.startedMillis))
            .limit(Math.max(0, sessions.size() - MAX_RETAINED_SESSIONS))
            .forEach(s -> sessions.remove(s.id));
    }
}
