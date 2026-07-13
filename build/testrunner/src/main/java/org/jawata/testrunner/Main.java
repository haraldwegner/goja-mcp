package org.jawata.testrunner;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sprint 23 (D1) — the forked test-runner entry point. Launched by the
 * jawata server in a separate JVM on the USER PROJECT's classpath (plus the
 * junit-platform-console-standalone jar, which supplies launcher + Jupiter +
 * Vintage engines).
 *
 * <p>Contract with the parent process:</p>
 * <ul>
 *   <li>One argument: the path to an ARGFILE — {@code key=value} lines,
 *       repeated keys allowed. Keys: {@code event-file} (required),
 *       {@code select-class}, {@code select-method}
 *       ({@code com.example.Foo#bar}), {@code select-package}.</li>
 *   <li>Results are streamed as JSON lines to the EVENT FILE, flushed per
 *       line — never to stdout (test code owns stdout/stderr; the parent
 *       captures those separately, bounded). Event kinds: {@code run-start},
 *       {@code class-start}, {@code class-finish}, {@code test-finish}
 *       (status PASSED/FAILED/ABORTED/SKIPPED), {@code run-finish} with
 *       totals.</li>
 *   <li>Exit 0 = the run completed (test failures live in the events, not
 *       the exit code). Exit 2 = infrastructure/discovery error. A missing
 *       {@code run-finish} event tells the parent the evidence was never
 *       finalized (crash, kill, timeout).</li>
 * </ul>
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("usage: Main <argfile>");
            System.exit(2);
        }
        try {
            run(Path.of(args[0]));
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(2);
        }
    }

    private static void run(Path argFile) throws IOException {
        List<DiscoverySelector> selectors = new ArrayList<>();
        Path eventFile = null;
        Path attributionDir = null;
        for (String line : Files.readAllLines(argFile, StandardCharsets.UTF_8)) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);
            switch (key) {
                case "event-file" -> eventFile = Path.of(value);
                case "attribution-dir" -> attributionDir = Path.of(value);
                case "select-class" -> selectors.add(DiscoverySelectors.selectClass(value));
                case "select-method" -> selectors.add(DiscoverySelectors.selectMethod(value));
                case "select-package" -> selectors.add(DiscoverySelectors.selectPackage(value));
                default -> System.err.println("unknown argfile key ignored: " + key);
            }
        }
        if (eventFile == null) {
            System.err.println("argfile has no event-file");
            System.exit(2);
        }
        if (selectors.isEmpty()) {
            System.err.println("argfile has no selectors");
            System.exit(2);
        }

        try (BufferedWriter out = Files.newBufferedWriter(eventFile, StandardCharsets.UTF_8)) {
            EventSink sink = new EventSink(out);
            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                .selectors(selectors)
                .build();
            Launcher launcher = LauncherFactory.create();
            EmittingListener emitting = new EmittingListener(sink);
            if (attributionDir != null) {
                launcher.execute(request, emitting,
                    new AttributionListener(attributionDir));
            } else {
                launcher.execute(request, emitting);
            }
        }
    }

    /**
     * Sprint 23 (D4) — per-test coverage segments: at every test boundary the
     * JaCoCo agent's in-process API ({@code org.jacoco.agent.rt.RT},
     * accessed reflectively so the runner stays dependency-free) is dumped
     * AND reset, yielding one .exec segment per test. An index file maps
     * segment file → test id.
     */
    private static final class AttributionListener implements TestExecutionListener {
        private final Path dir;
        private Object agent;                 // org.jacoco.agent.rt.IAgent
        private java.lang.reflect.Method getExecutionData;
        private BufferedWriter index;
        private int counter;

        AttributionListener(Path dir) {
            this.dir = dir;
        }

        @Override
        public void testPlanExecutionStarted(TestPlan testPlan) {
            try {
                Files.createDirectories(dir);
                Class<?> rt = Class.forName("org.jacoco.agent.rt.RT");
                agent = rt.getMethod("getAgent").invoke(null);
                getExecutionData = agent.getClass()
                    .getMethod("getExecutionData", boolean.class);
                getExecutionData.setAccessible(true);
                index = Files.newBufferedWriter(dir.resolve("index.tsv"),
                    StandardCharsets.UTF_8);
                // Discard everything executed before the first test (JUnit
                // bootstrap, static init of shared infra).
                getExecutionData.invoke(agent, true);
            } catch (Exception e) {
                System.err.println("attribution unavailable (no jacoco agent?): " + e);
                agent = null;
            }
        }

        @Override
        public void executionFinished(TestIdentifier id, TestExecutionResult result) {
            if (agent == null || !id.isTest()) return;
            try {
                byte[] segment = (byte[]) getExecutionData.invoke(agent, true);
                String fileName = "seg-" + (++counter) + ".exec";
                Files.write(dir.resolve(fileName), segment);
                String testId = id.getSource().map(s -> {
                    if (s instanceof org.junit.platform.engine.support.descriptor.MethodSource m) {
                        return m.getClassName() + "#" + m.getMethodName();
                    }
                    return id.getDisplayName();
                }).orElse(id.getDisplayName());
                index.write(fileName + "\t" + testId + "\n");
                index.flush();
            } catch (Exception e) {
                System.err.println("attribution segment failed for "
                    + id.getDisplayName() + ": " + e);
            }
        }

        @Override
        public void testPlanExecutionFinished(TestPlan testPlan) {
            try {
                if (index != null) index.close();
            } catch (IOException ignored) { }
        }
    }

    /** Serialized single-writer over the event file; one JSON object per line. */
    private static final class EventSink {
        private final BufferedWriter out;

        EventSink(BufferedWriter out) {
            this.out = out;
        }

        synchronized void emit(String json) {
            try {
                out.write(json);
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                // The parent owns the file; if it vanished mid-run there is
                // nothing useful left to do but keep executing tests.
                System.err.println("event write failed: " + e.getMessage());
            }
        }
    }

    private static final class EmittingListener implements TestExecutionListener {
        private final EventSink sink;
        private final AtomicInteger passed = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final AtomicInteger aborted = new AtomicInteger();
        private final AtomicInteger skipped = new AtomicInteger();
        private volatile long startMillis;

        EmittingListener(EventSink sink) {
            this.sink = sink;
        }

        @Override
        public void testPlanExecutionStarted(TestPlan testPlan) {
            startMillis = System.currentTimeMillis();
            sink.emit("{\"e\":\"run-start\",\"plannedTests\":"
                + testPlan.countTestIdentifiers(TestIdentifier::isTest) + "}");
        }

        @Override
        public void executionStarted(TestIdentifier id) {
            if (isClassContainer(id)) {
                sink.emit("{\"e\":\"class-start\",\"class\":" + q(className(id)) + "}");
            }
        }

        @Override
        public void executionSkipped(TestIdentifier id, String reason) {
            if (id.isTest()) {
                skipped.incrementAndGet();
                sink.emit("{\"e\":\"test-finish\",\"status\":\"SKIPPED\""
                    + ",\"class\":" + q(className(id))
                    + ",\"test\":" + q(testName(id))
                    + ",\"durationMs\":0"
                    + (reason == null ? "" : ",\"message\":" + q(reason))
                    + "}");
            }
        }

        @Override
        public void executionFinished(TestIdentifier id, TestExecutionResult result) {
            if (id.isTest()) {
                String status = switch (result.getStatus()) {
                    case SUCCESSFUL -> "PASSED";
                    case FAILED -> "FAILED";
                    case ABORTED -> "ABORTED";
                };
                switch (result.getStatus()) {
                    case SUCCESSFUL -> passed.incrementAndGet();
                    case FAILED -> failed.incrementAndGet();
                    case ABORTED -> aborted.incrementAndGet();
                }
                sink.emit("{\"e\":\"test-finish\",\"status\":\"" + status + "\""
                    + ",\"class\":" + q(className(id))
                    + ",\"test\":" + q(testName(id))
                    + failureFields(result)
                    + "}");
            } else if (isClassContainer(id)) {
                // A container failure (e.g. @BeforeAll threw) is real evidence:
                // report it so the parent never mistakes it for a clean class.
                if (result.getStatus() == TestExecutionResult.Status.FAILED) {
                    failed.incrementAndGet();
                    sink.emit("{\"e\":\"test-finish\",\"status\":\"FAILED\""
                        + ",\"class\":" + q(className(id))
                        + ",\"test\":\"<container>\""
                        + failureFields(result)
                        + "}");
                }
                sink.emit("{\"e\":\"class-finish\",\"class\":" + q(className(id)) + "}");
            }
        }

        @Override
        public void testPlanExecutionFinished(TestPlan testPlan) {
            long wall = System.currentTimeMillis() - startMillis;
            int total = passed.get() + failed.get() + aborted.get() + skipped.get();
            sink.emit("{\"e\":\"run-finish\",\"total\":" + total
                + ",\"passed\":" + passed.get()
                + ",\"failed\":" + failed.get()
                + ",\"aborted\":" + aborted.get()
                + ",\"skipped\":" + skipped.get()
                + ",\"timeMs\":" + wall + "}");
        }

        private static String failureFields(TestExecutionResult result) {
            Optional<Throwable> t = result.getThrowable();
            if (t.isEmpty()) return "";
            Throwable thrown = t.get();
            String message = thrown.getClass().getName()
                + (thrown.getMessage() == null ? "" : ": " + thrown.getMessage());
            StringWriter sw = new StringWriter();
            thrown.printStackTrace(new PrintWriter(sw));
            String trace = sw.toString();
            if (trace.length() > 16_384) {
                trace = trace.substring(0, 16_384) + "\n[…trace truncated…]";
            }
            return ",\"message\":" + q(message) + ",\"trace\":" + q(trace);
        }

        private static boolean isClassContainer(TestIdentifier id) {
            return id.isContainer() && id.getSource()
                .filter(s -> s instanceof org.junit.platform.engine.support.descriptor.ClassSource)
                .isPresent();
        }

        private static String className(TestIdentifier id) {
            return id.getSource().map(s -> {
                if (s instanceof org.junit.platform.engine.support.descriptor.MethodSource m) {
                    return m.getClassName();
                }
                if (s instanceof org.junit.platform.engine.support.descriptor.ClassSource c) {
                    return c.getClassName();
                }
                return id.getDisplayName();
            }).orElse(id.getDisplayName());
        }

        private static String testName(TestIdentifier id) {
            return id.getSource().map(s -> {
                if (s instanceof org.junit.platform.engine.support.descriptor.MethodSource m) {
                    return m.getMethodName();
                }
                return id.getDisplayName();
            }).orElse(id.getDisplayName());
        }
    }

    /** Minimal JSON string quoting — the runner deliberately has no libraries. */
    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private Main() {}
}
