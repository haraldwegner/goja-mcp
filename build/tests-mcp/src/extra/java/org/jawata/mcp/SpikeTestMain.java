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
        List<DiscoverySelector> selectors = new ArrayList<>();
        List<String> unloadable = new ArrayList<>();
        for (String cn : classNames) {
            try {
                selectors.add(DiscoverySelectors.selectClass(Class.forName(cn)));
            } catch (Throwable t) {
                unloadable.add(cn + " (" + t + ")");
            }
        }
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
        final long t0 = System.currentTimeMillis();
        final AtomicInteger done = new AtomicInteger();
        final int totalClasses = selectors.size();
        TestExecutionListener progress = new TestExecutionListener() {
            private boolean isClass(TestIdentifier id) {
                return "class".equals(id.getUniqueIdObject().getLastSegment().getType());
            }
            @Override
            public void executionStarted(TestIdentifier id) {
                if (isClass(id)) {
                    System.out.printf("[%3d/%d %5ds] %s%n", done.get() + 1, totalClasses,
                        (System.currentTimeMillis() - t0) / 1000, id.getDisplayName());
                    System.out.flush();
                }
            }
            @Override
            public void executionFinished(TestIdentifier id, TestExecutionResult result) {
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

    private SpikeTestMain() {}
}
