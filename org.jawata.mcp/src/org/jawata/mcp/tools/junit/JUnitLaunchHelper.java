package org.jawata.mcp.tools.junit;

/**
 * Holds the {@link TestRunnerKind} classification shared by
 * {@link org.jawata.mcp.tools.shared.FrameworkDetection} and its consumers.
 *
 * <p>History: through v2.9.x this class wrapped JDT-LTK's JUnit launching
 * machinery (launch configurations + {@code TestRunListener}). Sprint 23
 * replaced that lifecycle with the forked-runner execution spine
 * ({@link org.jawata.mcp.execution.ForkedTestRunner}) — headless-fixture
 * capable, streaming, §13 safe-execution — and the launch machinery was
 * deleted. Only the runner-kind enum survives.</p>
 */
public class JUnitLaunchHelper {

    public enum TestRunnerKind {
        JUNIT3,
        JUNIT4,
        JUNIT5
    }

    private JUnitLaunchHelper() {}
}
