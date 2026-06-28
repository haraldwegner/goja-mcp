package org.goja.mcp.tools.junit;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.junit.JUnitCore;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.JavaRuntime;
import org.eclipse.jdt.junit.TestRunListener;
import org.eclipse.jdt.junit.model.ITestCaseElement;
import org.eclipse.jdt.junit.model.ITestElement;
import org.eclipse.jdt.junit.model.ITestRunSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sprint 12 (v1.6.0) — wraps JDT-LTK's JUnit launching machinery so
 * {@link org.goja.mcp.tools.RunTestsTool} can drive a JUnit run
 * headless: build a working-copy launch configuration, register a
 * {@link TestRunListener} for programmatic result collection, launch the
 * forked JVM, wait for it to finish, return a structured {@link Result}.
 *
 * <p><b>Why hardcoded constant strings?</b> The typed accessor class
 * {@code org.eclipse.jdt.junit.launcher.JUnitLaunchConfigurationConstants}
 * is in {@code org.eclipse.jdt.junit.core}'s exported API surface, but
 * pulling in the import to use those constants creates an awkward
 * compile-time dependency on an exported package's exact membership across
 * Eclipse releases. The constant *values* are persisted in `.launch`
 * configuration XML files on disk — they are forever-stable across Eclipse
 * versions because changing them would break every existing user's saved
 * launch configurations. We inline the values directly.</p>
 *
 * <p>Same reasoning for the JUnit launch type id and the test-runner-kind
 * values — inlined as documented strings.</p>
 *
 * <p>Result collection: {@link TestRunListener}'s callbacks
 * ({@code sessionStarted} / {@code testCaseStarted} /
 * {@code testCaseFinished} / {@code sessionFinished}) accumulate into a
 * list; we snapshot it when {@code sessionFinished} fires. No XML parsing
 * needed.</p>
 *
 * <p>Stdout/stderr capture: each {@link IProcess}'s {@link IStreamsProxy}
 * exposes monitor objects per stream. Append-listeners buffer up to
 * {@link #STREAM_CAP_BYTES} per stream and surface the last 100 lines as
 * {@code stdoutTail} / {@code stderrTail} — bounded so a chatty test can't
 * blow the manager's heap.</p>
 */
public class JUnitLaunchHelper {

    private static final Logger log = LoggerFactory.getLogger(JUnitLaunchHelper.class);

    /** Eclipse JUnit launch configuration type id. */
    public static final String LAUNCH_CONFIG_TYPE = "org.eclipse.jdt.junit.launchconfig";

    // Inlined launch-config attribute keys (see class Javadoc for rationale).
    // Mirror of org.eclipse.jdt.junit.launcher.JUnitLaunchConfigurationConstants.
    private static final String ATTR_TEST_RUNNER_KIND = "org.eclipse.jdt.junit.TEST_KIND";
    private static final String ATTR_TEST_METHOD_NAME = "org.eclipse.jdt.junit.TESTNAME";
    private static final String ATTR_TEST_CONTAINER = "org.eclipse.jdt.junit.CONTAINER";
    // Mirror of org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants.
    private static final String ATTR_PROJECT_NAME = "org.eclipse.jdt.launching.PROJECT_ATTR";
    private static final String ATTR_MAIN_TYPE_NAME = "org.eclipse.jdt.launching.MAIN_TYPE";
    private static final String ATTR_VM_ARGUMENTS = "org.eclipse.jdt.launching.VM_ARGUMENTS";
    private static final String ATTR_PROGRAM_ARGUMENTS = "org.eclipse.jdt.launching.PROGRAM_ARGUMENTS";
    private static final String ATTR_DEFAULT_CLASSPATH = "org.eclipse.jdt.launching.DEFAULT_CLASSPATH";
    private static final String ATTR_CLASSPATH = "org.eclipse.jdt.launching.CLASSPATH";

    /**
     * PDE plug-in nature id. When the user project has this nature, the JDT
     * JUnit launcher's default classpath resolution path is safe (it walks the
     * OSGi Bundle's headers, which exist for PDE bundles). When the project
     * lacks this nature (plain Maven, Gradle, or generic Java), the default
     * resolution path NPEs on a null Bundle (bugs.md #1) — so we pre-compute
     * the resolved classpath via {@link JavaRuntime} and pin it on the launch
     * config to bypass the PDE-aware code path entirely.
     */
    private static final String PDE_PLUGIN_NATURE = "org.eclipse.pde.PluginNature";

    /** Cap stdout/stderr capture per stream so a chatty test can't blow heap. */
    private static final int STREAM_CAP_BYTES = 1_000_000;

    public enum TestRunnerKind {
        JUNIT3,
        JUNIT4,
        JUNIT5;

        /**
         * Test-kind ids registered by {@code org.eclipse.jdt.junit.testKinds}
         * extension points. Stable across Eclipse versions.
         */
        public String configurationValue() {
            return switch (this) {
                case JUNIT3 -> "org.eclipse.jdt.junit.loader.junit3";
                case JUNIT4 -> "org.eclipse.jdt.junit.loader.junit4";
                case JUNIT5 -> "org.eclipse.jdt.junit.loader.junit5";
            };
        }
    }

    public enum Scope {
        METHOD,
        CLASS,
        PACKAGE
    }

    public static class LaunchRequest {
        public IJavaProject project;
        public Scope scope;
        public String typeName;       // method | class
        public String methodName;     // method
        public String packageName;    // package
        public TestRunnerKind runnerKind;
        public int timeoutSeconds;
        public List<String> vmArgs = new ArrayList<>();
    }

    public static class CaseResult {
        public String testClass;
        public String testMethod;
        public String status;     // PASSED | FAILED | SKIPPED | ERROR
        public String message;
        public String stackTrace;
        public long durationMs;
    }

    public static class Result {
        public int total;
        public int passed;
        public int failed;
        public int skipped;
        public long timeMs;
        public boolean timedOut;
        public List<CaseResult> failures = new ArrayList<>();
        public String stdoutTail;
        public String stderrTail;
    }

    public Result run(LaunchRequest request, IProgressMonitor monitor) throws CoreException {
        if (monitor == null) monitor = new NullProgressMonitor();

        ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType configType =
            launchManager.getLaunchConfigurationType(LAUNCH_CONFIG_TYPE);
        if (configType == null) {
            throw new CoreException(
                org.eclipse.core.runtime.Status.error("JUnit launch configuration type '"
                    + LAUNCH_CONFIG_TYPE + "' not registered. "
                    + "Target platform missing org.eclipse.jdt.junit.core?"));
        }

        ILaunchConfigurationWorkingCopy wc = configType.newInstance(null,
            launchManager.generateLaunchConfigurationName("goja_run_tests"));
        configureLaunch(wc, request);

        Result result = new Result();
        long startNanos = System.nanoTime();
        CollectingListener listener = new CollectingListener(result);
        JUnitCore.addTestRunListener(listener);
        ILaunch launch = null;
        try {
            launch = wc.launch(ILaunchManager.RUN_MODE, monitor);

            StreamCapture stdoutCap = new StreamCapture();
            StreamCapture stderrCap = new StreamCapture();
            attachStreams(launch, stdoutCap, stderrCap);

            long timeoutMillis = Math.max(1, request.timeoutSeconds) * 1000L;
            long deadline = System.currentTimeMillis() + timeoutMillis;
            while (System.currentTimeMillis() < deadline) {
                if (allTerminated(launch)) break;
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (!allTerminated(launch)) {
                result.timedOut = true;
                terminateAll(launch);
            }
            result.stdoutTail = stdoutCap.tail();
            result.stderrTail = stderrCap.tail();
        } finally {
            JUnitCore.removeTestRunListener(listener);
            result.timeMs = (System.nanoTime() - startNanos) / 1_000_000L;
            cleanup(launch);
        }
        return result;
    }

    private static void configureLaunch(ILaunchConfigurationWorkingCopy wc, LaunchRequest req)
            throws CoreException {
        wc.setAttribute(ATTR_PROJECT_NAME, req.project.getProject().getName());
        wc.setAttribute(ATTR_TEST_RUNNER_KIND, req.runnerKind.configurationValue());

        switch (req.scope) {
            case METHOD -> {
                wc.setAttribute(ATTR_MAIN_TYPE_NAME, req.typeName);
                wc.setAttribute(ATTR_TEST_METHOD_NAME, req.methodName);
            }
            case CLASS -> wc.setAttribute(ATTR_MAIN_TYPE_NAME, req.typeName);
            case PACKAGE -> wc.setAttribute(ATTR_TEST_CONTAINER,
                "/" + req.project.getProject().getName() + "/" + req.packageName);
        }

        // bugs.md #1 (Sprint 14): for non-PDE projects (plain Maven / Gradle /
        // generic Java), pre-compute the resolved runtime classpath via JavaRuntime
        // and pin it on the launch config. Without this, JDT's JUnit launcher
        // walks the (non-existent) OSGi Bundle for the project and NPEs on
        // Bundle.getHeaders(). PDE projects still use the default path so
        // Require-Bundle resolution against the workspace bundle pool stays
        // intact.
        if (!hasPdeNature(req.project)) {
            wc.setAttribute(ATTR_DEFAULT_CLASSPATH, false);
            wc.setAttribute(ATTR_CLASSPATH, computeRuntimeClasspathMementos(req.project));
        }

        if (req.vmArgs != null && !req.vmArgs.isEmpty()) {
            wc.setAttribute(ATTR_VM_ARGUMENTS, String.join(" ", req.vmArgs));
        }
        wc.setAttribute(ATTR_PROGRAM_ARGUMENTS, "");
        wc.setAttribute("org.eclipse.debug.core.ATTR_REMOVE_TERMINATED", true);
    }

    private static boolean hasPdeNature(IJavaProject project) {
        try {
            return project.getProject().hasNature(PDE_PLUGIN_NATURE);
        } catch (CoreException e) {
            log.debug("hasNature({}) on '{}' failed; treating as non-PDE: {}",
                PDE_PLUGIN_NATURE, project.getElementName(), e.getMessage());
            return false;
        }
    }

    private static List<String> computeRuntimeClasspathMementos(IJavaProject project)
            throws CoreException {
        IRuntimeClasspathEntry[] entries = JavaRuntime.computeUnresolvedRuntimeClasspath(project);
        List<String> mementos = new ArrayList<>(entries.length);
        for (IRuntimeClasspathEntry entry : entries) {
            mementos.add(entry.getMemento());
        }
        return mementos;
    }

    private static boolean allTerminated(ILaunch launch) {
        if (launch == null) return true;
        IProcess[] processes = launch.getProcesses();
        if (processes.length == 0) return launch.isTerminated();
        for (IProcess p : processes) {
            if (!p.isTerminated()) return false;
        }
        return true;
    }

    private static void terminateAll(ILaunch launch) {
        if (launch == null) return;
        try {
            for (IProcess p : launch.getProcesses()) {
                if (!p.isTerminated()) p.terminate();
            }
            if (!launch.isTerminated()) launch.terminate();
        } catch (Throwable t) {
            log.warn("Failed to terminate timed-out JUnit launch: {}", t.getMessage(), t);
        }
    }

    private static void cleanup(ILaunch launch) {
        if (launch == null) return;
        try {
            DebugPlugin.getDefault().getLaunchManager().removeLaunch(launch);
        } catch (Throwable t) {
            log.debug("Cleanup of launch failed (non-fatal): {}", t.getMessage());
        }
    }

    private static void attachStreams(ILaunch launch,
                                      StreamCapture stdout,
                                      StreamCapture stderr) {
        for (IProcess p : launch.getProcesses()) {
            IStreamsProxy proxy = p.getStreamsProxy();
            if (proxy == null) continue;
            IStreamMonitor outMon = proxy.getOutputStreamMonitor();
            if (outMon != null) {
                stdout.append(outMon.getContents());
                outMon.addListener((text, mon) -> stdout.append(text));
            }
            IStreamMonitor errMon = proxy.getErrorStreamMonitor();
            if (errMon != null) {
                stderr.append(errMon.getContents());
                errMon.addListener((text, mon) -> stderr.append(text));
            }
        }
    }

    private static final class CollectingListener extends TestRunListener {
        private final Result target;
        private final Map<String, Long> caseStartNanos =
            new java.util.concurrent.ConcurrentHashMap<>();

        CollectingListener(Result target) {
            this.target = target;
        }

        @Override
        public void sessionStarted(ITestRunSession session) {
            // no-op; we accumulate per case
        }

        @Override
        public void testCaseStarted(ITestCaseElement element) {
            caseStartNanos.put(caseKey(element), System.nanoTime());
        }

        @Override
        public void testCaseFinished(ITestCaseElement element) {
            target.total++;
            String key = caseKey(element);
            Long startedNs = caseStartNanos.remove(key);
            long durationMs = startedNs == null ? 0
                : (System.nanoTime() - startedNs) / 1_000_000L;

            ITestElement.Result outcome = element.getTestResult(false);
            if (outcome == ITestElement.Result.OK) {
                target.passed++;
            } else if (outcome == ITestElement.Result.IGNORED) {
                target.skipped++;
            } else if (outcome == ITestElement.Result.ERROR) {
                target.failed++;
                target.failures.add(buildFailure(element, "ERROR", durationMs));
            } else if (outcome == ITestElement.Result.FAILURE) {
                target.failed++;
                target.failures.add(buildFailure(element, "FAILED", durationMs));
            }
        }

        @Override
        public void sessionFinished(ITestRunSession session) {
            // no-op
        }

        private static String caseKey(ITestCaseElement e) {
            return e.getTestClassName() + "#" + e.getTestMethodName();
        }

        private static CaseResult buildFailure(ITestCaseElement element,
                                                String status, long durationMs) {
            CaseResult cr = new CaseResult();
            cr.testClass = element.getTestClassName();
            cr.testMethod = element.getTestMethodName();
            cr.status = status;
            cr.durationMs = durationMs;
            try {
                cr.message = element.getFailureTrace() == null ? null
                    : element.getFailureTrace().getActual();
                cr.stackTrace = element.getFailureTrace() == null ? null
                    : element.getFailureTrace().getTrace();
            } catch (Throwable t) {
                cr.message = "(no message available)";
                cr.stackTrace = "";
            }
            return cr;
        }
    }

    private static final class StreamCapture {
        private final StringBuilder buf = new StringBuilder();
        private boolean truncated = false;

        synchronized void append(String text) {
            if (text == null || text.isEmpty()) return;
            if (buf.length() + text.length() > STREAM_CAP_BYTES) {
                int allowed = STREAM_CAP_BYTES - buf.length();
                if (allowed > 0) buf.append(text, 0, allowed);
                truncated = true;
            } else {
                buf.append(text);
            }
        }

        synchronized String tail() {
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
}
