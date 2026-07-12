package org.jawata.mcp;

import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 22d — the in-framework test runner (tycho-surefire's essence, ~60 lines we
 * own): lives in the org.jawata.mcp.tests FRAGMENT, so it and every test class
 * load through the real host bundle classloaders with the platform running.
 * Invoked reflectively by the boot module's {@code -runTests} mode with the
 * explicit test-class list (the boot side enumerates the fragment jars).
 */
public final class SpikeTestMain {

    /** Returns the failed+error count; prints a machine-readable summary line. */
    public static int run(String[] classNames) {
        final long t0 = System.currentTimeMillis();
        final AtomicInteger done = new AtomicInteger();
        final long[] lastEvent = { System.currentTimeMillis() };
        final String[] currentClass = { "(class loading)" };
        startWatchdog(lastEvent, currentClass);

        // CI-hang root cause (run 3, 2026-07-12): Class.forName(cn) RUNS STATIC
        // INITIALIZERS — one of them blocked GitHub-side for 40+ min, before any
        // progress line and before the watchdog existed. Therefore: (a) the
        // watchdog is armed FIRST (above), (b) classes load NON-initializing
        // here — static init runs later, inside the class's own execution slot,
        // where the progress line + watchdog can name it.
        ClassLoader loader = SpikeTestMain.class.getClassLoader();
        List<DiscoverySelector> selectors = new ArrayList<>();
        List<String> unloadable = new ArrayList<>();
        int loaded = 0;
        for (String cn : classNames) {
            try {
                currentClass[0] = "(loading) " + cn;
                selectors.add(DiscoverySelectors.selectClass(Class.forName(cn, false, loader)));
                lastEvent[0] = System.currentTimeMillis();
                if (++loaded % 10 == 0 || loaded == 1) {
                    System.out.printf("loaded %d/%d test classes (%ds)%n", loaded,
                        classNames.length, (System.currentTimeMillis() - t0) / 1000);
                    System.out.flush();
                }
            } catch (Throwable t) {
                unloadable.add(cn + " (" + t + ")");
            }
        }
        System.out.printf("class loading complete: %d/%d (%ds)%n", loaded,
            classNames.length, (System.currentTimeMillis() - t0) / 1000);
        System.out.flush();

        LauncherConfig config = LauncherConfig.builder()
            .enableTestEngineAutoRegistration(false)
            .addTestEngines(new JupiterTestEngine())
            .build();
        Launcher launcher = LauncherFactory.create(config);
        LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
            .selectors(selectors).build();
        SummaryGeneratingListener listener = new SummaryGeneratingListener();
        // Progress listener: one line per test CLASS, flushed immediately — a CI run
        // must never be silent between discovery and summary (2026-07-12 lesson,
        // learned three blind waits deep).
        final int totalClasses = selectors.size();
        TestExecutionListener progress = new TestExecutionListener() {
            private boolean isClass(TestIdentifier id) {
                return "class".equals(id.getUniqueIdObject().getLastSegment().getType());
            }
            @Override
            public void executionStarted(TestIdentifier id) {
                lastEvent[0] = System.currentTimeMillis();
                if (isClass(id)) {
                    currentClass[0] = id.getDisplayName();
                    System.out.printf("[%3d/%d %5ds] %s%n", done.get() + 1, totalClasses,
                        (System.currentTimeMillis() - t0) / 1000, id.getDisplayName());
                    System.out.flush();
                }
            }
            @Override
            public void executionFinished(TestIdentifier id, TestExecutionResult result) {
                lastEvent[0] = System.currentTimeMillis();
                if (isClass(id)) {
                    done.incrementAndGet();
                    if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
                        System.out.printf("        ^^ %s: %s%n", result.getStatus(),
                            result.getThrowable().map(Throwable::toString).orElse(""));
                        System.out.flush();
                    }
                }
            }
        };
        launcher.execute(request, listener, progress);
        TestExecutionSummary s = listener.getSummary();
        PrintWriter out = new PrintWriter(System.out, true);
        s.printFailuresTo(out, 5);
        if (!unloadable.isEmpty()) {
            out.println("UNLOADABLE test classes (" + unloadable.size() + "):");
            unloadable.forEach(u -> out.println("  " + u));
        }
        out.printf("SPIKE-TESTS total=%d succeeded=%d failed=%d aborted=%d skipped=%d unloadable=%d%n",
            s.getTestsFoundCount(), s.getTestsSucceededCount(), s.getTestsFailedCount(),
            s.getTestsAbortedCount(), s.getTestsSkippedCount(), unloadable.size());
        return (int) Math.min(s.getTestsFailedCount() + unloadable.size(), 250);
    }

    /**
     * Stall watchdog (2026-07-12, after two blind CI runs — GitHub DISCARDS a
     * cancelled step's log, so the runner must self-diagnose): no event for
     * 5 minutes → print the stuck location + a full thread dump and halt with
     * 124. Armed BEFORE class loading — run 3 proved the pre-execution window
     * is where hangs hide.
     */
    private static void startWatchdog(long[] lastEvent, String[] currentClass) {
        Thread watchdog = new Thread(() -> {
            while (true) {
                try { Thread.sleep(30_000); } catch (InterruptedException e) { return; }
                long idle = System.currentTimeMillis() - lastEvent[0];
                if (idle > 300_000) {
                    System.out.printf("%n=== STALL: no event for %ds — stuck in %s ===%n",
                        idle / 1000, currentClass[0]);
                    Thread.getAllStackTraces().forEach((t, st) -> {
                        System.out.println("--- thread: " + t.getName() + " (" + t.getState() + ")");
                        for (StackTraceElement e : st) System.out.println("    at " + e);
                    });
                    System.out.flush();
                    Runtime.getRuntime().halt(124);
                }
            }
        }, "spike-test-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private SpikeTestMain() {}
}
