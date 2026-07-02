package org.goja.mcp.refactoring;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.goja.core.IJdtService;
import org.goja.core.LoadedProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 18 — the compile half of the orchestration parity gate: build every
 * loaded project and report whether it is error-clean. The multi-step loop
 * (Stage 5) runs this <b>after every step</b>; a non-clean result rolls the step
 * (and the plan) back. Deliberately lean and self-contained (it reimplements the
 * refreshLocal + FULL_BUILD + PROBLEM-marker sweep that {@code compile_workspace}
 * performs, rather than depending on the tools layer — refactoring must not depend
 * on tools). Test execution is a separate, opt-in gate the loop layers on top;
 * this is the fast structural backstop that catches a step that fails to compile.
 */
public final class ParityGate {

    private static final Logger log = LoggerFactory.getLogger(ParityGate.class);

    /** One compile error surfaced by the gate. */
    public record Diagnostic(String filePath, int line, String message) {}

    /** Gate outcome: {@code clean} iff no error-severity problem markers remain. */
    public record Result(boolean clean, int errorCount, List<Diagnostic> errors) {}

    private ParityGate() {}

    /**
     * Refresh + full-build every loaded project and collect error-severity
     * problem markers. {@code clean} is true iff none remain.
     */
    public static Result compile(IJdtService service) {
        List<Diagnostic> errors = new ArrayList<>();
        for (LoadedProject loaded : service.allProjects()) {
            IProject project = loaded.javaProject().getProject();
            try {
                project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
                project.build(IncrementalProjectBuilder.FULL_BUILD, new NullProgressMonitor());
            } catch (Exception e) {
                log.warn("ParityGate build failed on '{}': {}", loaded.projectKey(), e.getMessage());
                errors.add(new Diagnostic(null, -1, "build failed on " + loaded.projectKey() + ": " + e.getMessage()));
                continue;
            }
            try {
                IMarker[] problems = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
                for (IMarker marker : problems) {
                    if (marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO) != IMarker.SEVERITY_ERROR) {
                        continue;
                    }
                    String file = null;
                    IResource resource = marker.getResource();
                    if (resource != null && resource.getLocation() != null) {
                        file = resource.getLocation().toOSString();
                    }
                    int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
                    errors.add(new Diagnostic(file, line, marker.getAttribute(IMarker.MESSAGE, "")));
                }
            } catch (Exception e) {
                log.warn("ParityGate marker sweep failed on '{}': {}", loaded.projectKey(), e.getMessage());
                errors.add(new Diagnostic(null, -1, "marker sweep failed on " + loaded.projectKey() + ": " + e.getMessage()));
            }
        }
        return new Result(errors.isEmpty(), errors.size(), errors);
    }
}
