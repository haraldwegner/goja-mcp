package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.execution.ForkedTestRunner;
import org.jawata.mcp.execution.RunnerClasspath;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.junit.JUnitLaunchHelper;
import org.jawata.mcp.tools.shared.FrameworkDetection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 12 (v1.6.0) — {@code run_tests}: launch JUnit tests via
 * JDT-LTK's launching delegate and return parsed results.
 *
 * <p>Headless. Scope can be one method, one class, or a whole package.
 * Framework defaults to auto-detection from the project's resolved
 * classpath (junit-jupiter-api → junit5; org.junit / junit-4.x →
 * junit4; testng → routed via JUnit 4 compat layer).</p>
 *
 * <p>Sprint 23 (D1) — the JDT-LTK launch path (historically: OSGi NPEs on
 * plain Maven, no streaming, no headless fixture support) is replaced by the
 * {@link ForkedTestRunner} execution spine: ONE runner JVM forked on the
 * project's own compiled output + resolved classpath (project-supplied
 * JUnit-platform jars filtered; the dist {@code tools/} console-standalone
 * supplies launcher + engines), structured results streamed over an event
 * file, full safe-execution contract (timeout, process-tree reaping, env
 * allowlist, memory + concurrency bounds, honest
 * {@code evidenceFinalized=false} on crash/kill/timeout). The project is
 * built first and the run is REFUSED while compile errors exist.</p>
 */
public class RunTestsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(RunTestsTool.class);

    private final org.jawata.mcp.execution.TestSessionRegistry sessions =
        new org.jawata.mcp.execution.TestSessionRegistry();

    public RunTestsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "run_tests";
    }

    @Override
    public String getDescription() {
        return """
            Run JUnit / TestNG tests in a forked runner JVM on the project's
            own classpath and return parsed pass/fail/skip results with stack
            traces for failures.

            USAGE:
              run_tests(scope={kind:"method", typeName:"com.example.FooTest", methodName:"testBar"})
              run_tests(scope={kind:"method", filePath:"...", line:42, column:4})
              run_tests(scope={kind:"class",  typeName:"com.example.FooTest"})
              run_tests(scope={kind:"class",  filePath:"...", line:10, column:0})
              run_tests(scope={kind:"package", packageName:"com.example.tests"})

            Async (long suites — progressive results):
              run_tests(action="start", scope=...)        → { sessionId, state }
              run_tests(action="status", sessionId="...") → live per-class progress
                (plannedTests, classesStarted/Finished, testsFinished, recentEvents)
                + the full summary once FINISHED; the summary stays retrievable
                after completion.
              run_tests(action="cancel", sessionId="...") → reaps the runner
                process tree, returns the honest partial result (cancelled=true,
                evidenceFinalized=false).
              action="run" (default) behaves synchronously as above.

            Inputs:
            - scope.kind — "method" | "class" | "package".
            - For kind="method": pass EXACTLY ONE of these combinations
                {typeName, methodName}              (find method by FQN + name)
                OR {filePath, line, column}         (find method at cursor position)
              {filePath, methodName} is NOT a valid combination — methodName must pair
              with typeName, never with filePath.
            - For kind="class": pass EXACTLY ONE of
                {typeName}                          (FQN)
                OR {filePath, line, column}         (find class at cursor position)
            - For kind="package": pass {packageName}.
            - framework — "junit4" | "junit5" | "testng" | "auto" (default).
            - timeoutSeconds — default 120, hard-cap 600.
            - vmArgs — optional list of JVM flags (e.g. ["-Xmx512m"]).
            - projectKey — optional. Restrict to a single loaded project.

            Result: { framework, projectsTested, summary{total, passed, failed,
              skipped, aborted?, timeMs, timedOut?, evidenceFinalized,
              evidenceNote?}, failures[{testClass, testMethod, status, message,
              stackTrace, durationMs}], stdoutTail, stderrTail }
            evidenceFinalized=false means the runner JVM died, was killed, or
            timed out — the counts are the partial events seen, never a
            fabricated total.

            Failure modes:
            - INVALID_PARAMETER — bad scope (no @Test method at position,
              empty package, no test framework on classpath, an unsupported
              scope-field combination such as {filePath, methodName}, compile
              errors in the project, or the concurrent-session limit.
            - INTERNAL_ERROR — the runner toolchain could not be launched.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("type", "object");
        Map<String, Object> scopeProps = new LinkedHashMap<>();
        scopeProps.put("kind", Map.of("type", "string",
            "enum", List.of("method", "class", "package"),
            "description", "Scope of the test run."));
        scopeProps.put("filePath", Map.of("type", "string",
            "description", "Source file. For kind=method or kind=class; must be paired with {line, column}, never with methodName."));
        scopeProps.put("line", Map.of("type", "integer",
            "description", "Zero-based line. Paired with filePath + column for cursor-position lookup (kind=method or kind=class)."));
        scopeProps.put("column", Map.of("type", "integer",
            "description", "Zero-based column. Paired with filePath + line for cursor-position lookup (kind=method or kind=class)."));
        scopeProps.put("typeName", Map.of("type", "string",
            "description", "Fully-qualified class name. For kind=class: pass alone. For kind=method: pair with methodName."));
        scopeProps.put("methodName", Map.of("type", "string",
            "description", "Test method name. Must be paired with typeName for kind=method. NOT valid with filePath — use {filePath, line, column} for cursor-position lookups instead."));
        scopeProps.put("packageName", Map.of("type", "string",
            "description", "Package FQN (kind=package)."));
        scope.put("properties", scopeProps);
        scope.put("required", List.of("kind"));
        properties.put("scope", scope);
        properties.put("framework", Map.of("type", "string",
            "enum", List.of("junit4", "junit5", "testng", "auto"),
            "description", "Default 'auto' — detected from project classpath."));
        properties.put("timeoutSeconds", Map.of("type", "integer",
            "description", "Default 120; hard-capped at 600."));
        properties.put("vmArgs", Map.of("type", "array",
            "items", Map.of("type", "string"),
            "description", "Optional JVM flags for the forked test runner."));
        properties.put("action", Map.of("type", "string",
            "enum", List.of("run", "start", "status", "cancel"),
            "description", "run (default) = synchronous. start = async, returns a sessionId "
                + "immediately. status/cancel operate on a sessionId (scope not needed)."));
        properties.put("sessionId", Map.of("type", "string",
            "description", "Session handle from action=start; required for status/cancel."));
        properties.put("extraClasspath", Map.of("type", "array",
            "items", Map.of("type", "string"),
            "description", "Launch descriptor: extra jar/dir paths appended to the runner "
                + "classpath at RUNTIME only (e.g. plain-Java projects whose build model "
                + "cannot declare them)."));
        schema.put("properties", properties);
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        if (arguments == null) {
            return ToolResponse.invalidParameter("scope", "Required object 'scope' is missing.");
        }
        String action = getStringParam(arguments, "action", "run");
        switch (action) {
            case "status", "cancel" -> {
                return handleSessionAction(action, arguments);
            }
            case "run", "start" -> { /* fall through to launch */ }
            default -> {
                return ToolResponse.invalidParameter("action",
                    "Must be 'run', 'start', 'status', or 'cancel'; got '" + action + "'.");
            }
        }
        if (arguments == null || !arguments.has("scope") || !arguments.get("scope").isObject()) {
            return ToolResponse.invalidParameter("scope", "Required object 'scope' is missing.");
        }
        JsonNode scope = arguments.get("scope");
        String kind = scope.has("kind") ? scope.get("kind").asText() : null;
        if (kind == null) {
            return ToolResponse.invalidParameter("scope.kind", "Required field missing.");
        }

        String projectKey = getStringParam(arguments, "projectKey");
        LoadedProject loaded;
        if (projectKey != null && !projectKey.isBlank()) {
            Optional<LoadedProject> scoped = service.getProject(projectKey);
            if (scoped.isEmpty()) {
                Optional<Long> dropped = service.wasRecentlyDropped(projectKey);
                if (dropped.isPresent()) {
                    return ToolResponse.projectKeyDropped(projectKey, dropped.get());
                }
                return ToolResponse.invalidParameter("projectKey",
                    "Unknown projectKey '" + projectKey + "'. Use list_projects.");
            }
            loaded = scoped.get();
        } else {
            loaded = service.allProjects().stream().findFirst().orElse(null);
            if (loaded == null) {
                return ToolResponse.projectNotLoaded();
            }
        }
        IJavaProject javaProject = loaded.javaProject();

        try {
            String frameworkRaw = getStringParam(arguments, "framework", "auto");
            JUnitLaunchHelper.TestRunnerKind runner = FrameworkDetection.detect(frameworkRaw, javaProject);
            if (runner == null) {
                return ToolResponse.invalidParameter("framework",
                    "Could not detect a JUnit/TestNG framework on the project's classpath. "
                        + "Set framework explicitly or add a test dependency.");
            }

            ForkedTestRunner.Spec spec = new ForkedTestRunner.Spec();
            int timeout = getIntParam(arguments, "timeoutSeconds", 120);
            spec.timeoutSeconds = Math.min(600, Math.max(1, timeout));
            if (arguments.has("vmArgs") && arguments.get("vmArgs").isArray()) {
                arguments.get("vmArgs").forEach(n -> spec.vmArgs.add(n.asText()));
            }

            switch (kind) {
                case "method" -> {
                    ToolResponse error = configureMethodScope(spec, scope, service);
                    if (error != null) return error;
                }
                case "class" -> {
                    ToolResponse error = configureClassScope(spec, scope, service);
                    if (error != null) return error;
                }
                case "package" -> {
                    if (!scope.has("packageName") || scope.get("packageName").asText().isBlank()) {
                        return ToolResponse.invalidParameter("scope.packageName",
                            "packageName is required for kind='package'.");
                    }
                    spec.selectPackages.add(scope.get("packageName").asText());
                }
                default -> {
                    return ToolResponse.invalidParameter("scope.kind",
                        "Must be 'method', 'class', or 'package'; got '" + kind + "'.");
                }
            }

            // Evidence needs compiled classes: build, and refuse honestly on
            // compile errors instead of running tests against stale bytes.
            String buildProblem = RunnerClasspath.buildAndCheck(javaProject, new NullProgressMonitor());
            if (buildProblem != null) {
                return ToolResponse.invalidParameter("project", buildProblem);
            }

            boolean testng = "testng".equalsIgnoreCase(frameworkRaw);
            RunnerClasspath.Assembled assembled = RunnerClasspath.assemble(javaProject, testng);
            if (assembled.isRefused()) {
                return ToolResponse.invalidParameter("project", assembled.refusalReason);
            }
            spec.classpath = assembled.classpath;
            // Launch descriptor (Stage 3): caller-supplied runtime-only
            // classpath additions — the plain-Java shape's escape hatch.
            if (arguments.has("extraClasspath") && arguments.get("extraClasspath").isArray()) {
                arguments.get("extraClasspath").forEach(n ->
                    spec.classpath.add(Path.of(n.asText())));
            }
            org.eclipse.core.runtime.IPath loc = javaProject.getProject().getLocation();
            if (loc != null) {
                spec.workingDirectory = loc.toFile().toPath();
            }

            String frameworkLabel = frameworkRaw.equals("auto")
                ? runnerKindToShortName(runner) : frameworkRaw;

            if ("start".equals(action)) {
                org.jawata.mcp.execution.TestSessionRegistry.Session session = sessions.create();
                session.frameworkLabel = frameworkLabel;
                spec.eventConsumer = session::onEvent;
                try {
                    session.attach(new ForkedTestRunner().start(spec));
                } catch (ForkedTestRunner.SessionLimitException limit) {
                    sessions.remove(session.id);
                    return ToolResponse.invalidParameter("concurrency", limit.getMessage());
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", "run_tests");
                data.put("action", "start");
                data.put("sessionId", session.id);
                data.put("state", session.state());
                data.put("framework", frameworkLabel);
                data.put("hint", "Poll run_tests(action=status, sessionId=…) for per-class "
                    + "progress; abort with action=cancel.");
                return ToolResponse.success(data, ResponseMeta.builder().build());
            }

            ForkedTestRunner.Result r;
            try {
                r = new ForkedTestRunner().run(spec);
            } catch (ForkedTestRunner.SessionLimitException limit) {
                return ToolResponse.invalidParameter("concurrency", limit.getMessage());
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "run_tests");
            data.put("framework", frameworkLabel);
            data.put("projectsTested", 1);
            appendResult(data, r);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(r.total)
                .returnedCount(r.failures.size())
                .build());
        } catch (Exception e) {
            log.warn("run_tests threw unexpectedly: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /** status/cancel on a previously started async session. */
    private ToolResponse handleSessionAction(String action, JsonNode arguments) {
        String sid = getStringParam(arguments, "sessionId");
        if (sid == null || sid.isBlank()) {
            return ToolResponse.invalidParameter("sessionId",
                "sessionId is required for action='" + action + "'.");
        }
        org.jawata.mcp.execution.TestSessionRegistry.Session session = sessions.get(sid);
        if (session == null) {
            return ToolResponse.invalidParameter("sessionId",
                "Unknown or expired session '" + sid + "'.");
        }
        if ("cancel".equals(action)) {
            session.cancel();
            // Give the reap a moment so the response can carry the final
            // (honestly partial) result instead of a still-RUNNING state.
            long deadline = System.currentTimeMillis() + 5000;
            while ("RUNNING".equals(session.state()) && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "run_tests");
        data.put("action", action);
        data.put("sessionId", sid);
        data.put("state", session.state());
        data.put("framework", session.frameworkLabel);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("plannedTests", session.plannedTests);
        progress.put("classesStarted", session.classesStarted.get());
        progress.put("classesFinished", session.classesFinished.get());
        progress.put("testsFinished", session.testsFinished.get());
        data.put("progress", progress);
        List<Map<String, Object>> events = session.recentEvents();
        data.put("recentEvents",
            events.size() > 25 ? events.subList(events.size() - 25, events.size()) : events);
        ForkedTestRunner.Result r = session.resultNow();
        if (r != null) {
            appendResult(data, r);
        }
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    /** Map a runner result onto the response shape (shared by run/status/cancel). */
    private static void appendResult(Map<String, Object> data, ForkedTestRunner.Result r) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", r.total);
        summary.put("passed", r.passed);
        summary.put("failed", r.failed);
        summary.put("skipped", r.skipped);
        if (r.aborted > 0) summary.put("aborted", r.aborted);
        summary.put("timeMs", r.timeMs);
        if (r.timedOut) summary.put("timedOut", true);
        if (r.cancelled) summary.put("cancelled", true);
        summary.put("evidenceFinalized", r.evidenceFinalized);
        if (!r.evidenceFinalized) {
            String note;
            if (r.cancelled) {
                note = "The run was CANCELLED and the runner reaped — counts are the "
                    + "partial events seen before the kill; evidence was NOT finalized.";
            } else if (r.timedOut) {
                note = "The run TIMED OUT and was reaped — counts below are the partial "
                    + "events seen before the kill; evidence was NOT finalized.";
            } else {
                note = "The runner JVM ended abnormally (exit " + r.exitCode + ") — counts "
                    + "below are partial; evidence was NOT finalized.";
            }
            summary.put("evidenceNote", note);
        }
        data.put("summary", summary);

        List<Map<String, Object>> failures = new ArrayList<>();
        for (ForkedTestRunner.CaseResult c : r.failures) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("testClass", c.testClass);
            f.put("testMethod", c.testMethod);
            f.put("status", c.status);
            if (c.message != null) f.put("message", c.message);
            if (c.stackTrace != null) f.put("stackTrace", c.stackTrace);
            f.put("durationMs", c.durationMs);
            failures.add(f);
        }
        data.put("failures", failures);
        data.put("stdoutTail", r.stdoutTail == null ? "" : r.stdoutTail);
        data.put("stderrTail", r.stderrTail == null ? "" : r.stderrTail);
    }

    private ToolResponse configureMethodScope(ForkedTestRunner.Spec spec,
                                              JsonNode scope, IJdtService service)
            throws Exception {
        if (scope.has("typeName") && scope.has("methodName")) {
            spec.selectMethods.add(scope.get("typeName").asText()
                + "#" + scope.get("methodName").asText());
            return null;
        }
        if (!scope.has("filePath") || !scope.has("line") || !scope.has("column")) {
            return ToolResponse.invalidParameter("scope",
                "kind='method' requires either {typeName, methodName} or {filePath, line, column}.");
        }
        Path filePath = service.getPathUtils().resolve(scope.get("filePath").asText());
        int line = scope.get("line").asInt();
        int column = scope.get("column").asInt();
        IJavaElement element = service.getElementAtPosition(filePath, line, column);
        IMethod method = null;
        // Walk up if the position landed on a body element rather than the
        // declaration itself.
        for (IJavaElement walk = element; walk != null; walk = walk.getParent()) {
            if (walk instanceof IMethod m) {
                method = m;
                break;
            }
        }
        if (method == null) {
            return ToolResponse.symbolNotFound(
                "No method at " + filePath + ":" + line + ":" + column);
        }
        IType declaring = method.getDeclaringType();
        if (declaring == null) {
            return ToolResponse.invalidParameter("scope",
                "Method has no declaring type (synthetic?).");
        }
        spec.selectMethods.add(declaring.getFullyQualifiedName()
            + "#" + method.getElementName());
        return null;
    }

    private ToolResponse configureClassScope(ForkedTestRunner.Spec spec,
                                             JsonNode scope, IJdtService service)
            throws Exception {
        if (scope.has("typeName") && !scope.get("typeName").asText().isBlank()) {
            spec.selectClasses.add(scope.get("typeName").asText());
            return null;
        }
        if (!scope.has("filePath") || !scope.has("line") || !scope.has("column")) {
            return ToolResponse.invalidParameter("scope",
                "kind='class' requires either typeName or {filePath, line, column}.");
        }
        Path filePath = service.getPathUtils().resolve(scope.get("filePath").asText());
        int line = scope.get("line").asInt();
        int column = scope.get("column").asInt();
        IType type = service.getTypeAtPosition(filePath, line, column);
        if (type == null) {
            return ToolResponse.symbolNotFound(
                "No type at " + filePath + ":" + line + ":" + column);
        }
        spec.selectClasses.add(type.getFullyQualifiedName());
        return null;
    }

    private static String runnerKindToShortName(JUnitLaunchHelper.TestRunnerKind k) {
        return switch (k) {
            case JUNIT3 -> "junit3";
            case JUNIT4 -> "junit4";
            case JUNIT5 -> "junit5";
        };
    }

}
