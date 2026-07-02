package org.goja.mcp.refactoring;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 18 — the compile half of the parity gate: a clean project passes; an
 * error-severity problem marker fails it.
 *
 * <p>Like {@code CompileWorkspaceToolTest}, this exercises the marker-READ path
 * that {@link ParityGate} owns and attaches a real {@link IMarker#PROBLEM} marker
 * via {@link IFile#createMarker} — the same API JDT's compiler uses — rather than
 * relying on the build job firing (it does not fire in the headless Tycho-test
 * runtime; in the resident, a real build supplies the markers this reads).</p>
 */
class ParityGateTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
    }

    @Test
    @DisplayName("a clean project passes the gate")
    void cleanProject_passes() {
        ParityGate.Result result = ParityGate.compile(service);
        assertTrue(result.clean(), () -> "expected clean, errors: " + result.errors());
        assertEquals(0, result.errorCount());
    }

    @Test
    @DisplayName("an error-severity problem marker fails the gate")
    void errorMarker_failsGate() throws Exception {
        assertTrue(ParityGate.compile(service).clean(), "precondition: clean baseline");

        IProject project = service.getJavaProject().getProject();
        AtomicReference<IFile> found = new AtomicReference<>();
        project.accept(r -> {
            if (r instanceof IFile f && "HelloWorld.java".equals(f.getName())) {
                found.compareAndSet(null, f);
            }
            return true;
        });
        IFile target = found.get();
        assertNotNull(target, "HelloWorld.java must be present in the loaded project");

        IMarker marker = target.createMarker(IMarker.PROBLEM);
        marker.setAttribute(IMarker.SEVERITY, IMarker.SEVERITY_ERROR);
        marker.setAttribute(IMarker.MESSAGE, "synthetic parity-gate error marker");
        marker.setAttribute(IMarker.LINE_NUMBER, 3);

        ParityGate.Result result = ParityGate.compile(service);
        assertFalse(result.clean(), "the error marker must fail the gate: " + result.errors());
        assertTrue(result.errorCount() >= 1, "at least one error expected");
    }
}
