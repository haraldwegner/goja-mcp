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

    /** Count-based dimensions → the JFR event type each ranks by. `wall` is duration-based (see below). */
    public static final Map<String, String> DIMENSION_EVENTS = Map.of(
        "cpu", "jdk.ExecutionSample",
        "alloc", "jdk.ObjectAllocationSample",
        "lock", "jdk.JavaMonitorEnter");

    /**
     * The duration-bearing JFR events that make up WALL-CLOCK time OFF the CPU — time the
     * program was elapsing but not running: blocked on a monitor, parked, waiting, or in a
     * blocking I/O call. Each carries a real {@code getDuration()}. On-CPU wall time is added
     * from execution samples (see {@link #wallHotspots}). Together they are total elapsed
     * time attributed per method — which is what "wall time" means, and what D11 lists as a
     * ranking dimension beside cpu/alloc/lock/gc.
     */
    public static final List<String> WALL_BLOCKING_EVENTS = List.of(
        "jdk.JavaMonitorEnter", "jdk.JavaMonitorWait", "jdk.ThreadPark",
        "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite");

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

    /**
     * Rank methods by total WALL-CLOCK time — elapsed time attributed per method whether the
     * method was on the CPU or blocked. On-CPU time comes from execution samples, each worth
     * one measured sampling interval; off-CPU time comes from the real durations of blocking
     * and I/O events ({@link #WALL_BLOCKING_EVENTS}). This is a genuine wall-time profile, not
     * lock-time renamed: a method that spends its wall clock parked, in a socket read, or
     * simply running all show up, ranked by the milliseconds each accounts for.
     */
    public static Map<String, Object> wallHotspots(Path jfrFile, int offset, int limit)
            throws IOException {
        Map<String, Long> wallNanos = new LinkedHashMap<>();
        List<Long> executionSampleTimesNanos = new ArrayList<>();
        List<String> executionSampleSymbols = new ArrayList<>();
        long blockingEvents = 0;
        long eventsWithoutStack = 0;

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                String type = event.getEventType().getName();
                String symbol = topFrameSymbol(event);

                if ("jdk.ExecutionSample".equals(type)) {
                    // On-CPU: defer — attributed one MEASURED sampling interval each, once we
                    // know the interval from the samples themselves (below).
                    if (symbol == null) {
                        eventsWithoutStack++;
                        continue;
                    }
                    executionSampleTimesNanos.add(event.getStartTime().toEpochMilli() * 1_000_000L);
                    executionSampleSymbols.add(symbol);
                } else if (WALL_BLOCKING_EVENTS.contains(type)) {
                    if (symbol == null) {
                        eventsWithoutStack++;
                        continue;
                    }
                    blockingEvents++;
                    wallNanos.merge(symbol, event.getDuration().toNanos(), Long::sum);
                }
            }
        }

        // Each execution sample represents one sampling interval of on-CPU wall time. Measure
        // the interval from the samples rather than assuming JFR's default (which the recording
        // may have overridden): the median gap between consecutive sample timestamps.
        long sampleIntervalNanos = medianGapNanos(executionSampleTimesNanos);
        for (String symbol : executionSampleSymbols) {
            wallNanos.merge(symbol, sampleIntervalNanos, Long::sum);
        }

        List<Map.Entry<String, Long>> ranked = new ArrayList<>(wallNanos.entrySet());
        ranked.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = offset; i < ranked.size() && rows.size() < limit; i++) {
            Map.Entry<String, Long> entry = ranked.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("rank", i + 1);
            row.put("symbol", entry.getKey());
            row.put("wallMillis", entry.getValue() / 1_000_000);
            rows.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dimension", "wall");
        result.put("rows", rows);
        result.put("returnedRows", rows.size());
        result.put("totalMethods", ranked.size());
        result.put("totalWallMillis", wallNanos.values().stream().mapToLong(Long::longValue).sum() / 1_000_000);
        result.put("onCpuSamples", executionSampleSymbols.size());
        result.put("samplingIntervalMillis", sampleIntervalNanos / 1_000_000);
        result.put("blockingEvents", blockingEvents);
        result.put("truncated", offset + rows.size() < ranked.size());
        if (eventsWithoutStack > 0) {
            result.put("eventsWithoutStack", eventsWithoutStack);
        }
        return result;
    }

    /** The median gap between consecutive (unsorted) timestamps — robust to one hiccup. */
    private static long medianGapNanos(List<Long> timestampsNanos) {
        if (timestampsNanos.size() < 2) {
            return 10_000_000L;   // no basis to measure — JFR's own default profiling period, 10ms
        }
        List<Long> sorted = new ArrayList<>(timestampsNanos);
        sorted.sort(Long::compareTo);
        List<Long> gaps = new ArrayList<>(sorted.size() - 1);
        for (int i = 1; i < sorted.size(); i++) {
            long gap = sorted.get(i) - sorted.get(i - 1);
            if (gap > 0) {
                gaps.add(gap);
            }
        }
        if (gaps.isEmpty()) {
            return 10_000_000L;
        }
        gaps.sort(Long::compareTo);
        return gaps.get(gaps.size() / 2);
    }

    /**
     * The TARGET'S OWN domain events — application-defined {@code jdk.jfr.Event} subclasses it
     * emitted during the recording, as opposed to the JVM's built-in {@code jdk.*} events.
     *
     * <p>A trading sim that commits an {@code OrderPlaced} or {@code RiskBreach} JFR event puts
     * its own domain right into the same recording jawata profiles — D12 asks for exactly this
     * ("plus the target's own domain events where it emits them"), and it was not built. Groups
     * them by event type with a count each, plus a bounded sample of the most recent ones with
     * their fields, so the domain is legible without dumping the whole stream.</p>
     */
    public static Map<String, Object> domainEvents(Path jfrFile, int perTypeSample)
            throws IOException {
        Map<String, Long> countByType = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> recentByType = new LinkedHashMap<>();

        try (RecordingFile rf = new RecordingFile(jfrFile)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                String type = event.getEventType().getName();
                // Built-in JVM/JDK/JFR events are not the application's domain — skip them.
                if (type.startsWith("jdk.") || type.startsWith("jfr.") || type.startsWith("java.")) {
                    continue;
                }
                countByType.merge(type, 1L, Long::sum);
                List<Map<String, Object>> recent =
                    recentByType.computeIfAbsent(type, k -> new ArrayList<>());
                recent.add(fieldsOf(event));
                if (recent.size() > perTypeSample) {
                    recent.remove(0);   // keep only the most recent `perTypeSample`
                }
            }
        }

        List<Map<String, Object>> types = new ArrayList<>();
        countByType.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .forEach(entry -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("eventType", entry.getKey());
                row.put("count", entry.getValue());
                row.put("recent", recentByType.get(entry.getKey()));
                types.add(row);
            });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("domainEventTypes", types);
        result.put("totalTypes", types.size());
        result.put("totalEvents", countByType.values().stream().mapToLong(Long::longValue).sum());
        return result;
    }

    /** An application event's own fields — its domain payload — as plain values. */
    private static Map<String, Object> fieldsOf(RecordedEvent event) {
        Map<String, Object> fields = new LinkedHashMap<>();
        event.getFields().forEach(field -> {
            String name = field.getName();
            // The event's declared payload — skip JFR's own bookkeeping fields.
            if (name.equals("stackTrace") || name.equals("eventThread") || name.startsWith("jdk.")) {
                return;
            }
            try {
                Object value = event.getValue(name);
                fields.put(name, value == null ? null : value.toString());
            } catch (Exception e) {
                // A field we cannot read is skipped, not faked.
                fields.put(name, "(unreadable)");
            }
        });
        return fields;
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
