package org.jawata.mcp.execution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Sprint 23 (D1) — the execution spine: forks ONE runner JVM per test
 * session on the USER PROJECT's classpath and collects structured results
 * from the runner's event file. Replaces the JDT-LTK launch path (which
 * historically NPE'd on Maven projects and could not stream) for
 * {@code run_tests}; Stage 7 mounts the JaCoCo agent on this same
 * lifecycle.
 *
 * <p>Safe-execution contract (spec §13):</p>
 * <ul>
 *   <li><b>Timeout + cancellation</b> — hard deadline; on expiry the whole
 *       process TREE is reaped ({@code descendants().destroyForcibly()}
 *       then the root).</li>
 *   <li><b>Bounded output</b> — stdout/stderr captured to
 *       {@value #STREAM_CAP_BYTES}-byte caps, surfaced as 100-line tails.</li>
 *   <li><b>Environment allowlist</b> — the child env is CLEARED and only
 *       {@link #ENV_ALLOWLIST} entries are copied from the server process;
 *       no secret inheritance. No PATH: the JVM binary is invoked by
 *       absolute path.</li>
 *   <li><b>Memory bound</b> — {@code -Xmx512m} default unless the caller
 *       supplies an explicit {@code -Xmx}.</li>
 *   <li><b>Concurrency bound</b> — at most {@value #MAX_CONCURRENT_SESSIONS}
 *       live runner JVMs; excess callers fail fast with an honest error.</li>
 *   <li><b>Declared filesystem policy</b> — the child's {@code java.io.tmpdir}
 *       is the session directory (deleted afterwards); the working directory
 *       is the project root.</li>
 *   <li><b>Honest evidence</b> — a run whose {@code run-finish} event never
 *       arrived (crash, kill, timeout) reports
 *       {@code evidenceFinalized=false} with whatever partial events exist;
 *       counts are never fabricated.</li>
 * </ul>
 */
public final class ForkedTestRunner {

    private static final Logger log = LoggerFactory.getLogger(ForkedTestRunner.class);

    private static final int STREAM_CAP_BYTES = 1_000_000;
    private static final int MAX_CONCURRENT_SESSIONS = 4;
    private static final Semaphore SESSIONS = new Semaphore(MAX_CONCURRENT_SESSIONS);
    private static final List<String> ENV_ALLOWLIST = List.of("LANG", "LC_ALL", "TZ");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** What to run and under which bounds. */
    public static final class Spec {
        public List<Path> classpath = new ArrayList<>();
        public List<String> selectClasses = new ArrayList<>();
        public List<String> selectMethods = new ArrayList<>();   // com.example.Foo#bar
        public List<String> selectPackages = new ArrayList<>();
        public List<String> vmArgs = new ArrayList<>();
        public int timeoutSeconds = 120;
        public Path workingDirectory;
        /** Progress callback for streaming (Stage 2); may be null. */
        public java.util.function.Consumer<JsonNode> eventConsumer;
    }

    public static final class CaseResult {
        public String testClass;
        public String testMethod;
        public String status;
        public String message;
        public String stackTrace;
        public long durationMs;
    }

    public static final class Result {
        public int total;
        public int passed;
        public int failed;
        public int aborted;
        public int skipped;
        public long timeMs;
        public boolean timedOut;
        /** True when the caller cancelled the session mid-run (Stage 2). */
        public boolean cancelled;
        /** True iff the runner's run-finish event arrived AND the JVM exited 0. */
        public boolean evidenceFinalized;
        public int exitCode = Integer.MIN_VALUE;
        public List<CaseResult> failures = new ArrayList<>();
        public String stdoutTail = "";
        public String stderrTail = "";
        /** Package-private: run-finish event observed in the event stream. */
        boolean runFinishSeen;
    }

    /** Thrown when the session limit is reached — an honest bound, not a queue. */
    public static final class SessionLimitException extends Exception {
        SessionLimitException(String msg) { super(msg); }
    }

    /** Handle on a live forked run (Sprint 23 Stage 2 — the async seam). */
    public static final class RunningSession {
        private final java.util.concurrent.Future<Result> future;
        private final java.util.concurrent.atomic.AtomicReference<Process> processRef;
        private volatile boolean cancelRequested;

        RunningSession(java.util.concurrent.Future<Result> future,
                       java.util.concurrent.atomic.AtomicReference<Process> processRef) {
            this.future = future;
            this.processRef = processRef;
        }

        public boolean isDone() { return future.isDone(); }

        public boolean isCancelRequested() { return cancelRequested; }

        /** Reap the live process tree; the run completes with honest partial evidence. */
        public void cancel() {
            cancelRequested = true;
            Process p = processRef.get();
            if (p != null) reap(p);
        }

        /** Block until the run completes and return its (possibly partial) result. */
        public Result await() throws InterruptedException {
            try {
                return future.get();
            } catch (java.util.concurrent.ExecutionException e) {
                Result crashed = new Result();
                crashed.stderrTail = "runner infrastructure failed: " + e.getCause();
                return crashed;
            }
        }

        /** The result if finished, else null. */
        public Result resultNow() {
            if (!future.isDone()) return null;
            try {
                return await();
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    private static final java.util.concurrent.ExecutorService RUNNER_POOL =
        java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "jawata-testrun-session");
            t.setDaemon(true);
            return t;
        });

    public Result run(Spec spec) throws IOException, InterruptedException, SessionLimitException {
        return start(spec).await();
    }

    /** Fork the run asynchronously; the session limit is checked here, synchronously. */
    public RunningSession start(Spec spec) throws InterruptedException, SessionLimitException {
        if (!SESSIONS.tryAcquire(5, TimeUnit.SECONDS)) {
            throw new SessionLimitException("Concurrent test-session limit ("
                + MAX_CONCURRENT_SESSIONS + ") reached; retry after a running session finishes.");
        }
        var processRef = new java.util.concurrent.atomic.AtomicReference<Process>();
        RunningSession[] holder = new RunningSession[1];
        java.util.concurrent.Future<Result> future = RUNNER_POOL.submit(() -> {
            try {
                Result r = launch(spec, processRef);
                if (holder[0] != null && holder[0].cancelRequested) {
                    r.cancelled = true;
                    r.evidenceFinalized = false;
                }
                return r;
            } finally {
                SESSIONS.release();
            }
        });
        RunningSession session = new RunningSession(future, processRef);
        holder[0] = session;
        return session;
    }

    private Result launch(Spec spec, java.util.concurrent.atomic.AtomicReference<Process> processRef)
            throws IOException, InterruptedException {
        Path session = Files.createTempDirectory("jawata-testrun-");
        Path eventFile = session.resolve("events.jsonl");
        Path argFile = session.resolve("run.args");
        Path childTmp = Files.createDirectories(session.resolve("tmp"));

        StringBuilder args = new StringBuilder();
        args.append("event-file=").append(eventFile).append('\n');
        spec.selectClasses.forEach(c -> args.append("select-class=").append(c).append('\n'));
        spec.selectMethods.forEach(m -> args.append("select-method=").append(m).append('\n'));
        spec.selectPackages.forEach(p -> args.append("select-package=").append(p).append('\n'));
        Files.writeString(argFile, args.toString(), StandardCharsets.UTF_8);

        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-ea");
        boolean callerXmx = spec.vmArgs.stream().anyMatch(a -> a.startsWith("-Xmx"));
        if (!callerXmx) cmd.add("-Xmx512m");
        cmd.addAll(spec.vmArgs);
        cmd.add("-Djava.io.tmpdir=" + childTmp);
        cmd.add("-cp");
        cmd.add(String.join(java.io.File.pathSeparator,
            spec.classpath.stream().map(Path::toString).toList()));
        cmd.add("org.jawata.testrunner.Main");
        cmd.add(argFile.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        for (String key : ENV_ALLOWLIST) {
            String v = System.getenv(key);
            if (v != null) pb.environment().put(key, v);
        }
        if (spec.workingDirectory != null) {
            pb.directory(spec.workingDirectory.toFile());
        }

        Result result = new Result();
        long startNanos = System.nanoTime();
        Process process = pb.start();
        processRef.set(process);
        StreamTail stdout = StreamTail.consume(process.getInputStream());
        StreamTail stderr = StreamTail.consume(process.getErrorStream());
        EventTailer tailer = spec.eventConsumer == null ? null
            : EventTailer.start(eventFile, spec.eventConsumer);
        try {
            boolean finished = process.waitFor(Math.max(1, spec.timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                result.timedOut = true;
                reap(process);
            }
            result.exitCode = process.isAlive() ? Integer.MIN_VALUE : process.exitValue();
        } finally {
            if (process.isAlive()) reap(process);
            if (tailer != null) tailer.stop();
            result.timeMs = (System.nanoTime() - startNanos) / 1_000_000L;
            result.stdoutTail = stdout.tail();
            result.stderrTail = stderr.tail();
            parseEvents(eventFile, result);
            result.evidenceFinalized = result.runFinishSeen && result.exitCode == 0 && !result.timedOut;
            deleteRecursively(session);
        }
        return result;
    }

    /** Kill the whole tree, children first, then the root — never orphan a JVM. */
    private static void reap(Process process) {
        try {
            process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            log.warn("reaping forked test runner failed: {}", t.getMessage(), t);
        }
    }

    private static void parseEvents(Path eventFile, Result result) {
        if (!Files.isRegularFile(eventFile)) return;
        try (Stream<String> lines = Files.lines(eventFile, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isBlank()) return;
                JsonNode n;
                try {
                    n = JSON.readTree(line);
                } catch (IOException e) {
                    return; // torn last line after a kill — expected, partial evidence
                }
                switch (n.path("e").asText()) {
                    case "test-finish" -> {
                        String status = n.path("status").asText();
                        switch (status) {
                            case "PASSED" -> result.passed++;
                            case "FAILED" -> result.failed++;
                            case "ABORTED" -> result.aborted++;
                            case "SKIPPED" -> result.skipped++;
                            default -> { }
                        }
                        if ("FAILED".equals(status) || "ABORTED".equals(status)) {
                            CaseResult c = new CaseResult();
                            c.testClass = n.path("class").asText();
                            c.testMethod = n.path("test").asText();
                            c.status = status;
                            c.message = n.hasNonNull("message") ? n.get("message").asText() : null;
                            c.stackTrace = n.hasNonNull("trace") ? n.get("trace").asText() : null;
                            c.durationMs = n.path("durationMs").asLong();
                            result.failures.add(c);
                        }
                    }
                    case "run-finish" -> {
                        result.runFinishSeen = true;
                        result.total = n.path("total").asInt();
                        result.passed = n.path("passed").asInt();
                        result.failed = n.path("failed").asInt();
                        result.aborted = n.path("aborted").asInt();
                        result.skipped = n.path("skipped").asInt();
                    }
                    default -> { }
                }
            });
        } catch (IOException e) {
            log.warn("could not read runner event file {}: {}", eventFile, e.getMessage());
        }
        if (!result.runFinishSeen) {
            result.total = result.passed + result.failed + result.aborted + result.skipped;
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /** Bounded async reader; keeps the head up to the cap, returns a 100-line tail. */
    private static final class StreamTail {
        private final StringBuilder buf = new StringBuilder();
        private boolean truncated;
        private final Thread thread;

        private StreamTail(InputStream in) {
            this.thread = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    char[] chunk = new char[8192];
                    int n;
                    while ((n = r.read(chunk)) >= 0) {
                        synchronized (this) {
                            if (buf.length() + n > STREAM_CAP_BYTES) {
                                int allowed = STREAM_CAP_BYTES - buf.length();
                                if (allowed > 0) buf.append(chunk, 0, allowed);
                                truncated = true;
                            } else {
                                buf.append(chunk, 0, n);
                            }
                        }
                    }
                } catch (IOException ignored) { }
            }, "jawata-testrun-gobbler");
            thread.setDaemon(true);
        }

        static StreamTail consume(InputStream in) {
            StreamTail t = new StreamTail(in);
            t.thread.start();
            return t;
        }

        synchronized String tail() {
            try {
                thread.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            String[] lines = buf.toString().split("\n");
            int from = Math.max(0, lines.length - 100);
            StringBuilder out = new StringBuilder();
            if (truncated) out.append("[…stream truncated…]\n");
            for (int i = from; i < lines.length; i++) {
                out.append(lines[i]).append('\n');
            }
            return out.toString();
        }
    }

    /** Follows the event file while the run is live (Stage 2 streaming seam). */
    private static final class EventTailer {
        private final Thread thread;
        private volatile boolean stopped;

        private EventTailer(Path file, java.util.function.Consumer<JsonNode> consumer) {
            this.thread = new Thread(() -> {
                long offset = 0;
                while (!stopped) {
                    offset = drain(file, offset, consumer);
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ie) {
                        break; // fall through to the FINAL drain — the tail
                               // events (class-finish, run-finish) arrive
                               // exactly when the parent stops the tailer
                    }
                }
                drain(file, offset, consumer);
            }, "jawata-testrun-events");
            thread.setDaemon(true);
        }

        static EventTailer start(Path file, java.util.function.Consumer<JsonNode> consumer) {
            EventTailer t = new EventTailer(file, consumer);
            t.thread.start();
            return t;
        }

        void stop() {
            stopped = true;
            thread.interrupt();
            try {
                thread.join(1000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        private static long drain(Path file, long fromByte,
                                  java.util.function.Consumer<JsonNode> consumer) {
            if (!Files.isRegularFile(file)) return fromByte;
            try {
                byte[] all = Files.readAllBytes(file);
                if (all.length <= fromByte) return fromByte;
                String fresh = new String(all, (int) fromByte, (int) (all.length - fromByte),
                    StandardCharsets.UTF_8);
                int lastNewline = fresh.lastIndexOf('\n');
                if (lastNewline < 0) return fromByte;   // no complete new line yet
                for (String line : fresh.substring(0, lastNewline).split("\n")) {
                    if (line.isBlank()) continue;
                    try {
                        consumer.accept(JSON.readTree(line));
                    } catch (IOException ignored) { }
                }
                return fromByte + fresh.substring(0, lastNewline + 1)
                    .getBytes(StandardCharsets.UTF_8).length;
            } catch (IOException e) {
                return fromByte;
            }
        }
    }
}
