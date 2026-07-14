package org.jawata.mcp.runtime.profile;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 24 (D11) — turns a JFR recording into ranked, symbol-named hotspots.
 * Same honesty rule as the rest of the profiling floor: totals are reported
 * alongside a capped, paginated list, so a page never reads as the whole
 * recording.
 *
 * <p>Four dimensions, three of them per-method rankings built from a stack-
 * sampling JFR event (top frame = the method actually running/allocating/
 * locking); the fourth ({@code gc}) has no Java stack to rank BY method — a
 * GC pause is a fact about the collector, not a call site — so it reports a
 * summary instead of inventing an attribution JFR does not provide.</p>
 *
 * <p>The symbol format ({@code ClassName#methodName}) matches the convention
 * used across the rest of jawata (breakpoint hits, coverage) — the same key
 * a caller can hand straight to {@code get_call_hierarchy} or {@code
 * find_references}, no search in between.</p>
 */
public final class JfrParser {

    /** Dimension → the JFR event type it ranks by. */
    public static final Map<String, String> DIMENSION_EVENTS = Map.of(
        "cpu", "jdk.ExecutionSample",
        "alloc", "jdk.ObjectAllocationSample",
        "lock", "jdk.JavaMonitorEnter");

    private JfrParser() {
    }

    /**
     * Rank methods for one dimension (cpu | alloc | lock), capped at {@code limit}
     * starting at {@code offset} — with the TRUE total sample and method counts
     * reported alongside, same discipline as the class histogram.
     */
    public static Map<String, Object> hotspots(Path jfrFile, String dimension,
                                               int offset, int limit) throws IOException {
        String eventType = DIMENSION_EVENTS.get(dimension);
        if (eventType == null) {
            throw new IllegalArgumentException("Unknown dimension '" + dimension
                + "'. One of " + DIMENSION_EVENTS.keySet());
        }

        Map<String, Long> counts = new LinkedHashMap<>();
        long totalEvents = 0;
        long eventsWithoutStack = 0;

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                if (!eventType.equals(event.getEventType().getName())) {
                    continue;
                }
                totalEvents++;
                String symbol = topFrameSymbol(event);
                if (symbol == null) {
                    eventsWithoutStack++;
                    continue;
                }
                counts.merge(symbol, 1L, Long::sum);
            }
        }

        List<Map.Entry<String, Long>> ranked = new ArrayList<>(counts.entrySet());
        ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = offset; i < ranked.size() && rows.size() < limit; i++) {
            Map.Entry<String, Long> entry = ranked.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("symbol", entry.getKey());
            row.put("samples", entry.getValue());
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", dimension);
        result.put("rows", rows);
        result.put("returnedRows", rows.size());
        result.put("totalMethods", ranked.size());
        result.put("totalSamples", totalEvents);
        result.put("truncated", offset + rows.size() < ranked.size());
        if (eventsWithoutStack > 0) {
            result.put("samplesWithoutStack", eventsWithoutStack);
        }
        return result;
    }

    /** Total GC pause count and duration — a fact about the collector, not a call site. */
    public static Map<String, Object> gcPauses(Path jfrFile) throws IOException {
        long count = 0;
        long totalNanos = 0;
        long maxNanos = 0;
        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                if (!"jdk.GCPhasePause".equals(event.getEventType().getName())) {
                    continue;
                }
                count++;
                long nanos = event.getDuration().toNanos();
                totalNanos += nanos;
                maxNanos = Math.max(maxNanos, nanos);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pauseCount", count);
        result.put("totalPauseMillis", totalNanos / 1_000_000);
        result.put("maxPauseMillis", maxNanos / 1_000_000);
        return result;
    }

    private static String topFrameSymbol(RecordedEvent event) {
        if (!event.hasField("stackTrace")) {
            return null;
        }
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null || stack.getFrames().isEmpty()) {
            return null;
        }
        RecordedFrame frame = stack.getFrames().get(0);
        RecordedMethod method = frame.getMethod();
        if (method == null || method.getType() == null) {
            return null;
        }
        return method.getType().getName() + "#" + method.getName();
    }
}
