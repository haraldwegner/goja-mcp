package org.jawata.mcp;

import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

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
        launcher.execute(request, listener);
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
