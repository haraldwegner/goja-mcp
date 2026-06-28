package org.goja.mcp.tools.shared;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.goja.mcp.tools.junit.JUnitLaunchHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 13 (v1.7.0) — shared classpath-walk that picks a JUnit / TestNG
 * runner kind for a given project. Extracted from {@code RunTestsTool}
 * (Sprint 12) so {@code generate_test_skeleton} can reuse the same
 * detection without duplicating the inspection.
 *
 * <p>Both {@code RunTestsTool} (Sprint 12) and {@code GenerateTestSkeletonTool}
 * (Sprint 13) delegate here: explicit framework string wins; otherwise walk the
 * resolved classpath. junit-jupiter-api beats junit-4.x; testng routes through
 * the JUnit 4 compat layer (matches Tycho-surefire convention).</p>
 */
public final class FrameworkDetection {

    private static final Logger log = LoggerFactory.getLogger(FrameworkDetection.class);

    private FrameworkDetection() {}

    /**
     * Resolve the framework: explicit value or auto-detect from classpath.
     *
     * @param framework Explicit framework name (case-insensitive): "junit3", "junit4",
     *                  "junit5", "testng", "auto", null. "auto" or null falls through to
     *                  classpath inspection.
     * @param project   The project to inspect.
     * @return Matched runner kind, or {@code null} when {@code framework="auto"} (or null)
     *         and no test framework is found, or when {@code framework} is an
     *         unrecognized non-empty value.
     */
    public static JUnitLaunchHelper.TestRunnerKind detect(String framework, IJavaProject project) {
        if (framework != null) {
            switch (framework.toLowerCase()) {
                case "junit3" -> { return JUnitLaunchHelper.TestRunnerKind.JUNIT3; }
                case "junit4" -> { return JUnitLaunchHelper.TestRunnerKind.JUNIT4; }
                case "junit5" -> { return JUnitLaunchHelper.TestRunnerKind.JUNIT5; }
                case "testng" -> { return JUnitLaunchHelper.TestRunnerKind.JUNIT4; }
                case "auto", "" -> { /* fall through to detection */ }
                default -> { return null; }
            }
        }
        try {
            IClasspathEntry[] resolved = project.getResolvedClasspath(true);
            for (IClasspathEntry entry : resolved) {
                String path = entry.getPath().toString().toLowerCase();
                if (path.contains("junit-jupiter-api")) {
                    return JUnitLaunchHelper.TestRunnerKind.JUNIT5;
                }
            }
            for (IClasspathEntry entry : resolved) {
                String path = entry.getPath().toString().toLowerCase();
                if (path.contains("junit-4") || path.matches(".*/junit-\\d.*\\.jar")) {
                    return JUnitLaunchHelper.TestRunnerKind.JUNIT4;
                }
            }
            for (IClasspathEntry entry : resolved) {
                String path = entry.getPath().toString().toLowerCase();
                if (path.contains("testng")) {
                    return JUnitLaunchHelper.TestRunnerKind.JUNIT4;
                }
            }
        } catch (Exception e) {
            log.debug("Classpath inspection failed during framework detection: {}", e.getMessage());
        }
        return null;
    }
}
