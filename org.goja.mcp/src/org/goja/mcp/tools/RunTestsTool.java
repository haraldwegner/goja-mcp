package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.goja.core.IJdtService;
import org.goja.core.LoadedProject;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.junit.JUnitLaunchHelper;
import org.goja.mcp.tools.shared.FrameworkDetection;
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
 * <p>Sprint 14 (v1.8.0) — bugs.md #1 full fix: plain Maven / Gradle / generic
 * Java projects (no PDE nature) used to NPE on {@code Bundle.getHeaders()}
 * inside the JDT JUnit launcher; the v1.7.1 workaround short-circuited them
 * with an {@code INVALID_PARAMETER} pointing at {@code mvn test}. The fix
 * lives in {@link JUnitLaunchHelper}: when the project lacks
 * {@code org.eclipse.pde.PluginNature}, the helper pre-computes the resolved
 * runtime classpath via {@code JavaRuntime} and pins it on the launch
 * configuration — JDT's launcher then uses our classpath directly and never
 * touches the (non-existent) Bundle for the project. The dispatch is gone
 * from this tool; Maven / Gradle / PDE all flow through the same launch
 * path.</p>
 *
 * <p>The three happy-path tests in {@link
 * org.goja.mcp.tools.verification.RunTestsToolTest} are still
 * {@code @Disabled} because Tycho-surefire's headless test runtime doesn't
 * compile our sample-project fixtures (the forked test JVM needs the
 * fixture's classes on disk, and Tycho's test stage doesn't run javac on
 * {@code test-resources/sample-projects/.../src/test/java}). Production usage
 * via the manager → real workspace works. Full happy-path coverage lands
 * once the fixture-build pipeline is in. See {@code docs/upgrade-checklist.md}.</p>
 */
public class RunTestsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(RunTestsTool.class);

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
            Run JUnit / TestNG tests via JDT-LTK and return parsed pass/fail/
            skip results with stack traces for failures.

            USAGE:
              run_tests(scope={kind:"method", typeName:"com.example.FooTest", methodName:"testBar"})
              run_tests(scope={kind:"method", filePath:"...", line:42, column:4})
              run_tests(scope={kind:"class",  typeName:"com.example.FooTest"})
              run_tests(scope={kind:"class",  filePath:"...", line:10, column:0})
              run_tests(scope={kind:"package", packageName:"com.example.tests"})

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
              skipped, timeMs, timedOut?}, failures[{testClass, testMethod,
              status, message, stackTrace, durationMs}], stdoutTail, stderrTail }

            Failure modes:
            - INVALID_PARAMETER — bad scope (no @Test method at position,
              empty package, no test framework on classpath, or an unsupported
              scope-field combination such as {filePath, methodName}).
            - INTERNAL_ERROR — JUnit launch infrastructure failed (missing
              target-platform bundle, etc.).
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
        schema.put("properties", properties);
        schema.put("required", List.of("scope"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
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
            JUnitLaunchHelper.LaunchRequest req = new JUnitLaunchHelper.LaunchRequest();
            req.project = javaProject;

            String frameworkRaw = getStringParam(arguments, "framework", "auto");
            JUnitLaunchHelper.TestRunnerKind runner = FrameworkDetection.detect(frameworkRaw, javaProject);
            if (runner == null) {
                return ToolResponse.invalidParameter("framework",
                    "Could not detect a JUnit/TestNG framework on the project's classpath. "
                        + "Set framework explicitly or add a test dependency.");
            }
            req.runnerKind = runner;

            int timeout = getIntParam(arguments, "timeoutSeconds", 120);
            req.timeoutSeconds = Math.min(600, Math.max(1, timeout));

            if (arguments.has("vmArgs") && arguments.get("vmArgs").isArray()) {
                arguments.get("vmArgs").forEach(n -> req.vmArgs.add(n.asText()));
            }

            switch (kind) {
                case "method" -> {
                    ToolResponse error = configureMethodScope(req, scope, service);
                    if (error != null) return error;
                }
                case "class" -> {
                    ToolResponse error = configureClassScope(req, scope, service);
                    if (error != null) return error;
                }
                case "package" -> {
                    if (!scope.has("packageName") || scope.get("packageName").asText().isBlank()) {
                        return ToolResponse.invalidParameter("scope.packageName",
                            "packageName is required for kind='package'.");
                    }
                    req.scope = JUnitLaunchHelper.Scope.PACKAGE;
                    req.packageName = scope.get("packageName").asText();
                }
                default -> {
                    return ToolResponse.invalidParameter("scope.kind",
                        "Must be 'method', 'class', or 'package'; got '" + kind + "'.");
                }
            }

            JUnitLaunchHelper helper = new JUnitLaunchHelper();
            JUnitLaunchHelper.Result r = helper.run(req, new NullProgressMonitor());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "run_tests");
            data.put("framework", frameworkRaw.equals("auto") ? runnerKindToShortName(runner) : frameworkRaw);
            data.put("projectsTested", 1);

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("total", r.total);
            summary.put("passed", r.passed);
            summary.put("failed", r.failed);
            summary.put("skipped", r.skipped);
            summary.put("timeMs", r.timeMs);
            if (r.timedOut) summary.put("timedOut", true);
            data.put("summary", summary);

            List<Map<String, Object>> failures = new ArrayList<>();
            for (JUnitLaunchHelper.CaseResult c : r.failures) {
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

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(r.total)
                .returnedCount(failures.size())
                .build());
        } catch (Exception e) {
            log.warn("run_tests threw unexpectedly: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse configureMethodScope(JUnitLaunchHelper.LaunchRequest req,
                                              JsonNode scope, IJdtService service)
            throws Exception {
        if (scope.has("typeName") && scope.has("methodName")) {
            req.scope = JUnitLaunchHelper.Scope.METHOD;
            req.typeName = scope.get("typeName").asText();
            req.methodName = scope.get("methodName").asText();
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
        req.scope = JUnitLaunchHelper.Scope.METHOD;
        req.typeName = declaring.getFullyQualifiedName();
        req.methodName = method.getElementName();
        return null;
    }

    private ToolResponse configureClassScope(JUnitLaunchHelper.LaunchRequest req,
                                             JsonNode scope, IJdtService service)
            throws Exception {
        if (scope.has("typeName") && !scope.get("typeName").asText().isBlank()) {
            req.scope = JUnitLaunchHelper.Scope.CLASS;
            req.typeName = scope.get("typeName").asText();
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
        req.scope = JUnitLaunchHelper.Scope.CLASS;
        req.typeName = type.getFullyQualifiedName();
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
