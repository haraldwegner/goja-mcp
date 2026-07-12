package org.jawata.mcp.execution;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.launching.IRuntimeClasspathEntry;
import org.eclipse.jdt.launching.JavaRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Sprint 23 (D1) — assembles the forked runner JVM's classpath: the dist
 * {@code tools/} jars FIRST (deterministic launcher + engine versions),
 * then the project's compiled output + resolved dependencies with the
 * project's own JUnit-platform/engine jars FILTERED OUT (two engines with
 * the same ID on one classpath is a discovery error — the surefire
 * pattern: we own the platform, the project supplies API + code).
 */
public final class RunnerClasspath {

    private static final Logger log = LoggerFactory.getLogger(RunnerClasspath.class);

    /** Result of assembly: either a classpath or an honest refusal. */
    public static final class Assembled {
        public final List<Path> classpath;
        public final String refusalReason;

        private Assembled(List<Path> classpath, String refusalReason) {
            this.classpath = classpath;
            this.refusalReason = refusalReason;
        }

        static Assembled of(List<Path> cp) { return new Assembled(cp, null); }
        static Assembled refused(String reason) { return new Assembled(null, reason); }
        public boolean isRefused() { return refusalReason != null; }
    }

    /**
     * Build the project (incremental; first build is effectively full) and
     * refuse honestly when compile errors remain — a runner launched on
     * broken classes produces lies, not evidence.
     *
     * <p>Referenced projects build FIRST (transitively): JDT's builder
     * consumes a referenced project's BINARY output, so an unbuilt dependency
     * makes every import from it unresolvable regardless of the model.</p>
     */
    public static String buildAndCheck(IJavaProject project, IProgressMonitor monitor) {
        IProject p = project.getProject();
        try {
            buildRequiredProjects(project, monitor, new java.util.HashSet<>());
            p.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
            IMarker[] problems = p.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            int errors = 0;
            String first = null;
            for (IMarker m : problems) {
                if (m.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR) {
                    errors++;
                    if (first == null) {
                        first = m.getResource().getName() + ":"
                            + m.getAttribute(IMarker.LINE_NUMBER, 0) + " "
                            + m.getAttribute(IMarker.MESSAGE, "");
                    }
                }
            }
            if (errors > 0) {
                return "Project '" + p.getName() + "' has " + errors
                    + " compile error(s); tests cannot produce evidence on broken classes. "
                    + "First: " + first + ". Run compile_workspace for the full list.";
            }
            return null;
        } catch (CoreException e) {
            return "Build of project '" + p.getName() + "' failed: " + e.getMessage();
        }
    }

    /**
     * Depth-first build of the projects referenced via CLASSPATH project
     * entries (cycle-safe). {@code IProject.getReferencedProjects()} sees
     * only static .project references — JDT project entries are dynamic, so
     * the dependency set comes from the resolved classpath.
     */
    private static void buildRequiredProjects(IJavaProject project, IProgressMonitor monitor,
                                              java.util.Set<String> visited) throws CoreException {
        if (!visited.add(project.getElementName())) return;
        org.eclipse.core.resources.IWorkspaceRoot root =
            org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
        for (org.eclipse.jdt.core.IClasspathEntry entry : project.getResolvedClasspath(true)) {
            if (entry.getEntryKind() != org.eclipse.jdt.core.IClasspathEntry.CPE_PROJECT) continue;
            IProject dep = root.getProject(entry.getPath().lastSegment());
            if (dep.exists() && dep.isOpen()) {
                IJavaProject depJava = org.eclipse.jdt.core.JavaCore.create(dep);
                buildRequiredProjects(depJava, monitor, visited);
                dep.build(IncrementalProjectBuilder.INCREMENTAL_BUILD, monitor);
            }
        }
    }

    /** Tools + project classpath, engine-filtered. */
    public static Assembled assemble(IJavaProject project, boolean includeTestNgEngine) {
        List<Path> tools;
        try {
            tools = toolchainJars(includeTestNgEngine);
        } catch (IOException | IllegalStateException e) {
            return Assembled.refused(e.getMessage());
        }

        Set<Path> projectCp = new LinkedHashSet<>();
        try {
            IRuntimeClasspathEntry[] unresolved =
                JavaRuntime.computeUnresolvedRuntimeClasspath(project);
            for (IRuntimeClasspathEntry entry : unresolved) {
                for (IRuntimeClasspathEntry resolved
                        : JavaRuntime.resolveRuntimeClasspathEntry(entry, project)) {
                    if (resolved.getClasspathProperty() != IRuntimeClasspathEntry.USER_CLASSES) {
                        continue; // the forked JVM brings its own JRE
                    }
                    String location = resolved.getLocation();
                    if (location == null) continue;
                    Path path = Path.of(location);
                    if (isProjectSuppliedPlatformJar(path)) {
                        log.debug("runner classpath: filtering project platform jar {}", path);
                        continue;
                    }
                    projectCp.add(path);
                }
            }
        } catch (CoreException e) {
            return Assembled.refused("Could not resolve the project runtime classpath: "
                + e.getMessage());
        }
        if (projectCp.isEmpty()) {
            return Assembled.refused("The project resolved to an EMPTY runtime classpath — "
                + "no compiled output to run tests against.");
        }

        List<Path> cp = new ArrayList<>(tools);
        cp.addAll(projectCp);
        return Assembled.of(cp);
    }

    /**
     * The project's own JUnit-platform infrastructure must not ride the
     * runner classpath (duplicate engine IDs); its API/annotation jars and
     * junit4/testng jars stay (the tools/ engines need them).
     */
    static boolean isProjectSuppliedPlatformJar(Path jar) {
        String name = jar.getFileName() == null ? "" : jar.getFileName().toString();
        return name.startsWith("junit-platform-")
            || name.startsWith("junit-jupiter-engine")
            || name.startsWith("junit-vintage-engine")
            || name.startsWith("testng-engine");
    }

    /** Locate the dist tools/ jars; {@code jawata.tools.dir} overrides for dev runs. */
    static List<Path> toolchainJars(boolean includeTestNgEngine) throws IOException {
        String override = System.getProperty("jawata.tools.dir");
        Path toolsDir;
        if (override != null && !override.isBlank()) {
            toolsDir = Path.of(override);
        } else {
            String distRoot = System.getProperty("jawata.dist.root");
            if (distRoot == null) {
                throw new IllegalStateException("Neither jawata.tools.dir nor jawata.dist.root "
                    + "is set — the forked-runner toolchain cannot be located. "
                    + "(The boot sets jawata.dist.root; a dev embedding must set jawata.tools.dir.)");
            }
            toolsDir = Path.of(distRoot).resolve("tools");
        }
        if (!Files.isDirectory(toolsDir)) {
            throw new IllegalStateException("Runner toolchain directory missing: " + toolsDir
                + " — the dist is incomplete (tools/ ships the JUnit launcher + jawata runner).");
        }
        Path standalone = findOne(toolsDir, "junit-platform-console-standalone-");
        Path runner = findOne(toolsDir, "org.jawata.testrunner-");
        List<Path> jars = new ArrayList<>(List.of(runner, standalone));
        if (includeTestNgEngine) {
            jars.add(findOne(toolsDir, "testng-engine-"));
        }
        return jars;
    }

    private static Path findOne(Path dir, String prefix) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith(prefix)
                    && p.getFileName().toString().endsWith(".jar"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "Runner toolchain jar missing from " + dir + ": " + prefix + "*.jar"));
        }
    }

    private RunnerClasspath() {}
}
