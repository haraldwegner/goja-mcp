package org.jawata.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.JavaRuntime;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Imports external Java projects (Maven/Gradle/Bazel) into the Eclipse workspace
 * with proper classpath configuration for JDT analysis.
 *
 * Uses linked folders to keep all Eclipse metadata in the workspace,
 * not polluting the user's actual project directory.
 */
public class ProjectImporter {

    private static final Logger log = LoggerFactory.getLogger(ProjectImporter.class);

    public enum BuildSystem { MAVEN, GRADLE, BAZEL, ECLIPSE_PDE, ECLIPSE, UNKNOWN }

    /**
     * Tycho packaging values for which Maven's {@code dependency:build-classpath}
     * goal returns wrong/empty results: Tycho injects classpath via the target
     * platform + {@code MANIFEST.MF}, not via pom {@code <dependencies>}.
     * Detected projects bypass {@link #getMavenDependencies(java.nio.file.Path)}
     * and rely on {@code .classpath kind="lib"} entries plus (in v1.5.0+)
     * {@code Require-Bundle} resolution against the workspace bundle pool.
     */
    private static final Set<String> TYCHO_PACKAGINGS = Set.of(
        "eclipse-plugin",
        "eclipse-test-plugin",
        "eclipse-feature",
        "eclipse-repository",
        "eclipse-update-site",
        "eclipse-target-definition"
    );

    // Source folder mapping: external relative path -> linked folder name
    private static final String[][] SOURCE_MAPPINGS = {
        {"src/main/java", "src-main-java"},
        {"src/test/java", "src-test-java"},
        {"src/main/kotlin", "src-main-kotlin"},
        {"src/test/kotlin", "src-test-kotlin"},
        {"src", "src"}
    };

    // Directories to skip during recursive source scanning
    private static final List<String> IGNORED_DIRS = List.of(
        ".git", ".svn", ".mvn", ".gradle", ".settings", ".metadata",
        "node_modules", "target", "build", "bin", "out", "dist"
    );

    /**
     * Exclusion patterns applied to every linked source entry (Sprint 22a
     * P0-c). Editor / agent scratch copies land INSIDE a source root — e.g.
     * {@code src/.claude/.edit-baks/**} or {@code *.edit-bak} backups — and,
     * because they carry their original {@code package} declaration, JDT
     * indexes them as DUPLICATE types that pollute search + call-hierarchy
     * with phantom results. IGNORED_DIRS skips them during source-ROOT
     * scanning, but the entry that links a root still sees everything beneath
     * it; these patterns exclude the scratch copies from the entry itself.
     * {@code **}/ matches at any depth including the root.
     */
    private static final IPath[] SOURCE_EXCLUSIONS = {
        new Path("**/.claude/**"),
        new Path("**/*.edit-bak")
    };

    // Pattern to extract module names from pom.xml
    private static final Pattern MODULE_PATTERN = Pattern.compile("<module>([^<]+)</module>");

    /**
     * Configure an IProject as a Java project with proper classpath.
     * Creates linked folders for source directories to keep Eclipse metadata
     * in the workspace, not polluting the user's project directory.
     *
     * @param project The workspace project (must be created and open)
     * @param projectPath The filesystem path to the external project
     * @param workspaceManager WorkspaceManager for creating linked folders
     * @return Configured IJavaProject
     * @throws CoreException if configuration fails
     */
    public IJavaProject configureJavaProject(IProject project, java.nio.file.Path projectPath,
            org.jawata.core.workspace.WorkspaceManager workspaceManager) throws CoreException {
        IJavaProject javaProject = JavaCore.create(project);

        // Build classpath entries
        List<IClasspathEntry> entries = new ArrayList<>();

        // 1. Add JRE container (provides java.* classes)
        IPath jreContainerPath = JavaRuntime.getDefaultJREContainerEntry().getPath();
        entries.add(JavaCore.newContainerEntry(jreContainerPath));

        // 2. Create linked folders and add source entries
        addSourceEntries(entries, project, projectPath, workspaceManager);

        // 3. Add dependency JARs from build system + Require-Bundle siblings
        addDependencyEntries(entries, projectPath, workspaceManager);

        // 4. Add output location
        IPath outputPath = project.getFullPath().append("bin");

        // Set the classpath
        javaProject.setRawClasspath(
            entries.toArray(new IClasspathEntry[0]),
            outputPath,
            new NullProgressMonitor()
        );

        log.info("Configured Java project with {} classpath entries", entries.size());
        return javaProject;
    }

    /**
     * Detect build system from project structure.
     *
     * <p>Order matters when a project has multiple markers. Maven wins over
     * Gradle wins over Bazel wins over Eclipse — common Tycho-style hybrids
     * (PDE + Maven pom) are classified as Maven so their dependency resolution
     * uses the Maven path. Plain Eclipse PDE bundles (MANIFEST.MF +
     * Bundle-SymbolicName, no pom/gradle) become ECLIPSE_PDE; plain Eclipse
     * projects (.classpath only) become ECLIPSE. (v1.7.1 / bug #4.)
     */
    public BuildSystem detectBuildSystem(java.nio.file.Path projectPath) {
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return BuildSystem.MAVEN;
        }
        if (Files.exists(projectPath.resolve("build.gradle")) ||
            Files.exists(projectPath.resolve("build.gradle.kts"))) {
            return BuildSystem.GRADLE;
        }
        // Bazel: check root-level workspace markers (not BUILD files, which are per-package)
        if (Files.exists(projectPath.resolve("MODULE.bazel")) ||
            Files.exists(projectPath.resolve("WORKSPACE.bazel")) ||
            Files.exists(projectPath.resolve("WORKSPACE"))) {
            return BuildSystem.BAZEL;
        }
        if (hasManifestSymbolicName(projectPath)) {
            return BuildSystem.ECLIPSE_PDE;
        }
        if (Files.exists(projectPath.resolve(".classpath"))) {
            return BuildSystem.ECLIPSE;
        }
        return BuildSystem.UNKNOWN;
    }

    /**
     * @return {@code true} iff {@code META-INF/MANIFEST.MF} exists at the
     * project root AND contains a {@code Bundle-SymbolicName} header. That
     * combination is what makes a directory an Eclipse PDE bundle.
     */
    private boolean hasManifestSymbolicName(java.nio.file.Path projectPath) {
        java.nio.file.Path manifest = projectPath.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.isRegularFile(manifest)) {
            return false;
        }
        try (Stream<String> lines = Files.lines(manifest, java.nio.charset.StandardCharsets.UTF_8)) {
            return lines.anyMatch(line -> line.startsWith("Bundle-SymbolicName:"));
        } catch (java.io.IOException e) {
            log.debug("Failed to read {}: {}", manifest, e.getMessage());
            return false;
        }
    }

    /**
     * Detect if this is a multi-module Maven project.
     */
    public boolean isMultiModuleProject(java.nio.file.Path projectPath) {
        java.nio.file.Path pomPath = projectPath.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            return false;
        }
        try {
            String content = Files.readString(pomPath);
            return content.contains("<modules>") || content.contains("<packaging>pom</packaging>");
        } catch (IOException e) {
            log.debug("Error reading pom.xml: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get list of module directories for a multi-module project.
     */
    public List<java.nio.file.Path> getModules(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> modules = new ArrayList<>();
        java.nio.file.Path pomPath = projectPath.resolve("pom.xml");

        if (!Files.exists(pomPath)) {
            return modules;
        }

        try {
            String content = Files.readString(pomPath);
            Matcher matcher = MODULE_PATTERN.matcher(content);
            while (matcher.find()) {
                String moduleName = matcher.group(1).trim();
                java.nio.file.Path modulePath = projectPath.resolve(moduleName);
                if (Files.exists(modulePath) && Files.isDirectory(modulePath)) {
                    modules.add(modulePath);
                }
            }
        } catch (IOException e) {
            log.warn("Error reading pom.xml for modules: {}", e.getMessage());
        }

        log.debug("Found {} modules in multi-module project", modules.size());
        return modules;
    }

    /**
     * Get all source directories, including from submodules if multi-module project.
     */
    private List<java.nio.file.Path> getAllSourcePaths(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> sourcePaths = new ArrayList<>();

        // v2.9.1 (dogfood D1): module traversal is RECURSIVE — nested aggregators
        // (root pom -> build/ aggregator -> leaf modules whose <sourceDirectory>
        // points back into sibling bundle dirs, the post-22d jawata-mcp shape)
        // yielded 0 sources under the old one-level walk, blinding the resident
        // to its own code and cascading into the store's staleness pass.
        collectSourcePaths(projectPath, sourcePaths, new HashSet<>(), 0);

        // For Bazel projects without standard source layout, scan for Java source directories
        if (sourcePaths.isEmpty() && detectBuildSystem(projectPath) == BuildSystem.BAZEL) {
            addBazelSourcePaths(projectPath, sourcePaths);
        }

        // Sprint 11 Phase C: for Gradle projects, also pull source directories
        // declared via sourceSets in build.gradle (custom srcDirs etc.) — the
        // Tooling API gives us the resolved list. Falls back silently to the
        // heuristic when Gradle isn't reachable.
        if (detectBuildSystem(projectPath) == BuildSystem.GRADLE) {
            readGradleProjectModel(projectPath).ifPresent(model -> {
                for (java.nio.file.Path declared : model.srcPaths()) {
                    if (!sourcePaths.contains(declared)) {
                        sourcePaths.add(declared);
                    }
                }
            });
        }

        return sourcePaths;
    }

    /** Depth-first module traversal (cycle-safe via visited set, depth-capped). */
    private void collectSourcePaths(java.nio.file.Path dir, List<java.nio.file.Path> sourcePaths,
            Set<java.nio.file.Path> visited, int depth) {
        if (depth > 10 || !visited.add(dir.toAbsolutePath().normalize())) {
            return;
        }
        List<java.nio.file.Path> found = new ArrayList<>();
        addSourcePathsFromDirectory(dir, found);
        for (java.nio.file.Path src : found) {
            if (!sourcePaths.contains(src)) {
                sourcePaths.add(src);
            }
        }
        if (isMultiModuleProject(dir)) {
            for (java.nio.file.Path modulePath : getModules(dir)) {
                collectSourcePaths(modulePath, sourcePaths, visited, depth + 1);
            }
        }
    }

    /**
     * Add source paths from a single project directory.
     *
     * Discovery precedence (ADR 0001):
     * 1. pom.xml &lt;sourceDirectory&gt; / &lt;testSourceDirectory&gt; overrides if declared.
     * 2. Eclipse .classpath &lt;classpathentry kind="src"&gt; entries if .classpath exists.
     * 3. Hardcoded SOURCE_MAPPINGS heuristic walk + &lt;root&gt;/src/ fallback.
     */
    private void addSourcePathsFromDirectory(java.nio.file.Path projectPath, List<java.nio.file.Path> sourcePaths) {
        int initialSize = sourcePaths.size();

        // 1. pom.xml <sourceDirectory> / <testSourceDirectory> overrides.
        SourceDirs pomDirs = readPomSourceDirs(projectPath.resolve("pom.xml"));
        pomDirs.srcMain().filter(Files::isDirectory).ifPresent(sourcePaths::add);
        pomDirs.srcTest().filter(Files::isDirectory).ifPresent(sourcePaths::add);
        if (sourcePaths.size() > initialSize) {
            return;
        }

        // 2. Eclipse .classpath src entries.
        ClasspathInfo cp = readEclipseClasspath(projectPath);
        for (java.nio.file.Path src : cp.srcPaths()) {
            if (Files.isDirectory(src)) {
                sourcePaths.add(src);
            }
        }
        if (sourcePaths.size() > initialSize) {
            return;
        }

        // 3. Heuristic: standard SOURCE_MAPPINGS layouts.
        for (int i = 0; i < SOURCE_MAPPINGS.length - 1; i++) {
            java.nio.file.Path srcPath = projectPath.resolve(SOURCE_MAPPINGS[i][0]);
            if (Files.exists(srcPath) && Files.isDirectory(srcPath)) {
                sourcePaths.add(srcPath);
            }
        }

        // Only add "src" fallback if no standard layout found for this directory
        boolean foundStandard = sourcePaths.stream()
            .anyMatch(p -> p.startsWith(projectPath));
        if (!foundStandard) {
            java.nio.file.Path srcPath = projectPath.resolve("src");
            if (Files.exists(srcPath) && Files.isDirectory(srcPath)) {
                sourcePaths.add(srcPath);
            }
        }
    }

    /**
     * Create linked folders for source directories and add them to classpath.
     * Uses linked folders to keep Eclipse metadata in the workspace.
     * Supports multi-module projects by scanning submodules.
     */
    private void addSourceEntries(List<IClasspathEntry> entries, IProject project,
            java.nio.file.Path projectPath, org.jawata.core.workspace.WorkspaceManager workspaceManager)
            throws CoreException {

        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);
        int folderIndex = 0;

        for (java.nio.file.Path srcPath : sourcePaths) {
            // Create unique linked folder name based on relative path
            String relativePath = projectPath.relativize(srcPath).toString().replace(File.separator, "-");
            String linkedName = "src-" + folderIndex + "-" + sanitizeFolderName(relativePath);
            folderIndex++;

            try {
                workspaceManager.createLinkedFolder(project, linkedName, srcPath);
                IPath sourceEntryPath = project.getFolder(linkedName).getFullPath();
                entries.add(JavaCore.newSourceEntry(sourceEntryPath,
                    new IPath[0], SOURCE_EXCLUSIONS, null));
                log.debug("Added linked source folder: {} -> {}", linkedName, srcPath);
            } catch (Exception e) {
                log.warn("Failed to create linked folder for {}: {}", srcPath, e.getMessage());
            }
        }

        log.info("Added {} source folders (multi-module: {})", sourcePaths.size(), isMultiModuleProject(projectPath));
    }

    /**
     * Sanitize folder name for Eclipse project.
     */
    private String sanitizeFolderName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\-_]", "-").replaceAll("-+", "-");
    }

    private void addDependencyEntries(List<IClasspathEntry> entries, java.nio.file.Path projectPath,
            org.jawata.core.workspace.WorkspaceManager workspaceManager) {
        // Bug #7 (Sprint 14): dedupe library and project entries by IPath
        // before passing to setRawClasspath(). JDT throws
        // "Build path contains duplicate entry" on any dup. Sources of
        // duplication seen in production:
        //   1. .classpath listing the same jar twice.
        //   2. .classpath kind="lib" AND a build-system path both contributing
        //      the same resolved jar (the fork's own multi-module repo, where
        //      gradle-tooling-api-8.10.jar surfaces from both).
        //   3. Compiled-output directories overlapping a .classpath entry.
        //   4. Multiple Require-Bundle headers resolving to the same workspace
        //      sibling.
        // First occurrence wins; subsequent duplicates are dropped silently.
        Set<IPath> addedLibPaths = new HashSet<>();
        Set<IPath> addedProjectPaths = new HashSet<>();

        // Eclipse .classpath kind="lib" entries (ADR 0001).
        // Merged alongside build-system-resolved deps; pure-Eclipse projects without a pom
        // get full dependency resolution from .classpath alone.
        ClasspathInfo cp = readEclipseClasspath(projectPath);
        int classpathLibCount = 0;
        for (java.nio.file.Path lib : cp.libPaths()) {
            if (Files.isRegularFile(lib)) {
                IPath eclipsePath = new Path(lib.toString());
                if (addedLibPaths.add(eclipsePath)) {
                    entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
                    classpathLibCount++;
                }
            }
        }

        BuildSystem buildSystem = detectBuildSystem(projectPath);
        boolean tycho = buildSystem == BuildSystem.MAVEN && isTychoProject(projectPath);
        if (tycho) {
            log.debug("Tycho packaging detected at {} — skipping mvn dependency:build-classpath", projectPath);
        }

        List<String> jars = switch (buildSystem) {
            case MAVEN -> tycho ? List.of() : getMavenDependencies(projectPath);
            case GRADLE -> getGradleDependencies(projectPath);
            case BAZEL -> getBazelDependencies(projectPath);
            default -> List.of();
        };

        for (String jar : jars) {
            java.nio.file.Path jarPath = java.nio.file.Path.of(jar);
            if (Files.exists(jarPath)) {
                IPath eclipsePath = new Path(jar);
                if (addedLibPaths.add(eclipsePath)) {
                    entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
                }
            }
        }

        if (classpathLibCount > 0) {
            log.debug("Added {} library entries from .classpath", classpathLibCount);
        }

        // Add compiled classes directories (Maven)
        addIfExists(entries, projectPath, "target/classes", addedLibPaths);
        addIfExists(entries, projectPath, "target/test-classes", addedLibPaths);
        // Add compiled classes directories (Gradle)
        addIfExists(entries, projectPath, "build/classes/java/main", addedLibPaths);
        addIfExists(entries, projectPath, "build/classes/java/test", addedLibPaths);

        // Sprint 11 Phase B: workspace bundle pool — resolve Require-Bundle
        // entries against sibling projects already loaded into the workspace.
        // Sprint 23 (D7): whatever the workspace pool does NOT satisfy is
        // resolved against EXTERNAL pools (materialized target platform via
        // jawata.bundle.pools, the shared p2 pool, the server's own dist
        // bundles) — as EXPORTED library entries, plus an Import-Package pass
        // over the pools' Export-Package index. Still-unresolved requirements
        // stay unresolved (logged), exactly as before.
        int bundleEntries = 0;
        List<String> unresolvedRequires = new ArrayList<>();
        for (String required : readManifestRequireBundle(projectPath)) {
            Optional<org.eclipse.jdt.core.IJavaProject> sibling = workspaceManager == null
                ? Optional.empty() : workspaceManager.resolveBundle(required);
            if (sibling.isPresent()) {
                IPath projPath = sibling.get().getPath();
                if (addedProjectPaths.add(projPath)) {
                    entries.add(JavaCore.newProjectEntry(projPath));
                    bundleEntries++;
                }
            } else {
                unresolvedRequires.add(required);
            }
        }
        List<String> importedPackages = readManifestImportPackage(projectPath);
        if (!unresolvedRequires.isEmpty() || !importedPackages.isEmpty()) {
            ExternalBundlePool pool = ExternalBundlePool.index(ExternalBundlePool.defaultPoolDirs());
            int external = 0;
            for (String required : unresolvedRequires) {
                Optional<java.nio.file.Path> jar = pool.bundleJar(required);
                if (jar.isPresent()) {
                    IPath eclipsePath = new Path(jar.get().toString());
                    if (addedLibPaths.add(eclipsePath)) {
                        // exported=true: a required bundle's classes are part of
                        // this project's runtime surface for dependents.
                        entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null, true));
                        external++;
                    }
                } else {
                    log.debug("Require-Bundle '{}' not found in workspace or external pools; skipping", required);
                }
            }
            for (String pkg : importedPackages) {
                Optional<java.nio.file.Path> jar = pool.packageProvider(pkg);
                if (jar.isPresent()) {
                    IPath eclipsePath = new Path(jar.get().toString());
                    if (addedLibPaths.add(eclipsePath)) {
                        entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null, true));
                        external++;
                    }
                } else {
                    log.debug("Import-Package '{}' has no provider in the external pools; skipping", pkg);
                }
            }
            if (external > 0) {
                log.info("Resolved {} PDE requirement(s) from the external bundle pools", external);
            }
        }
        if (bundleEntries > 0) {
            log.info("Resolved {} Require-Bundle entries from the workspace bundle pool", bundleEntries);
        }

        log.info("Added {} dependency entries from {}", jars.size(), buildSystem);
    }

    private void addIfExists(List<IClasspathEntry> entries, java.nio.file.Path projectPath,
                              String relativePath, Set<IPath> addedLibPaths) {
        java.nio.file.Path fullPath = projectPath.resolve(relativePath);
        if (Files.exists(fullPath) && Files.isDirectory(fullPath)) {
            IPath eclipsePath = new Path(fullPath.toString());
            if (addedLibPaths.add(eclipsePath)) {
                entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
            }
        }
    }

    /** Per-module classpath file name — relative, so the reactor writes one per module. */
    private static final String CP_FILE_NAME = "jawata-classpath.txt";

    /**
     * v2.9.2 (dogfood D4): resolve the Maven executable robustly. A Studio-launched
     * resident inherits a desktop/AppImage PATH without the user's shell profile —
     * /opt/apache-maven/bin was absent, ProcessBuilder("mvn") failed silently, and
     * the whole workspace classpath came back EMPTY (17k unresolved imports).
     * Order: project wrapper (mvnw) > PATH > caller-supplied known locations.
     * Package-visible seam for tests; production callers use the 1-arg overload.
     */
    static java.nio.file.Path resolveMavenCommand(java.nio.file.Path projectPath, String pathEnv,
            List<java.nio.file.Path> knownLocations, boolean windows) {
        String mvn = windows ? "mvn.cmd" : "mvn";
        java.nio.file.Path wrapper = projectPath.resolve(windows ? "mvnw.cmd" : "mvnw");
        if (Files.isExecutable(wrapper)) {
            return wrapper;
        }
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    java.nio.file.Path candidate = java.nio.file.Path.of(dir).resolve(mvn);
                    if (Files.isExecutable(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        for (java.nio.file.Path dir : knownLocations) {
            java.nio.file.Path candidate = dir.resolve(mvn);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private java.nio.file.Path resolveMavenCommand(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> known = new ArrayList<>();
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null && !mavenHome.isBlank()) {
            known.add(java.nio.file.Path.of(mavenHome, "bin"));
        }
        known.add(java.nio.file.Path.of("/opt/apache-maven/bin"));
        known.add(java.nio.file.Path.of("/opt/maven/bin"));
        known.add(java.nio.file.Path.of("/usr/share/maven/bin"));
        known.add(java.nio.file.Path.of("/usr/local/bin"));
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            known.add(java.nio.file.Path.of(home, ".sdkman", "candidates", "maven", "current", "bin"));
        }
        return resolveMavenCommand(projectPath, System.getenv("PATH"), known, isWindows());
    }

    private List<String> getMavenDependencies(java.nio.file.Path projectPath) {
        // v2.9.1 (dogfood D1): the output file is RELATIVE — it resolves against each
        // module's basedir, so a multi-module reactor writes one file per module.
        // (An absolute path made every module OVERWRITE the same file and the last,
        // dependency-less aggregator left it EMPTY — 0 classpath entries for the
        // whole workspace; -Dmdep.appendOutput proved a no-op in plugin 3.7.1.)
        // The per-module files are merged + deduped by parseClasspathOutput and
        // deleted after reading; they live under target/, never in source dirs.
        List<String> jars = new ArrayList<>();
        java.nio.file.Path mvnCmd = resolveMavenCommand(projectPath);
        if (mvnCmd == null) {
            log.warn("No Maven executable found (checked project mvnw, PATH, MAVEN_HOME, known "
                + "install dirs) — dependency classpath will be EMPTY and imports will not resolve");
            return jars;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                mvnCmd.toString(),
                "dependency:build-classpath",
                "-Dmdep.outputFile=target/" + CP_FILE_NAME,
                "-q"
            );
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);

            log.info("Running Maven ({}) to get classpath...", mvnCmd);
            Process process = pb.start();

            // Consume output to prevent blocking
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) { /* discard */ }
            }

            boolean completed = process.waitFor(120, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                log.warn("Maven classpath command timed out");
                return jars;
            }

            if (process.exitValue() == 0) {
                StringBuilder merged = new StringBuilder();
                try (Stream<java.nio.file.Path> walk = Files.walk(projectPath, 8)) {
                    for (java.nio.file.Path f : walk
                            .filter(pth -> CP_FILE_NAME.equals(pth.getFileName().toString()))
                            .filter(pth -> pth.getParent() != null
                                && "target".equals(pth.getParent().getFileName().toString()))
                            .toList()) {
                        merged.append(Files.readString(f)).append('\n');
                        Files.deleteIfExists(f);
                    }
                }
                jars.addAll(parseClasspathOutput(merged.toString()));
                log.info("Got {} classpath entries from Maven", jars.size());
            } else {
                log.warn("Maven classpath command failed with exit code: {}", process.exitValue());
            }

        } catch (Exception e) {
            log.error("Failed to get Maven classpath", e);
        }

        return jars;
    }

    /**
     * Parse {@code dependency:build-classpath} output: one line per reactor module
     * (with {@code -Dmdep.appendOutput=true}), entries newline- and
     * pathSeparator-delimited, deduped preserving order. Package-visible for tests.
     */
    static List<String> parseClasspathOutput(String content) {
        java.util.LinkedHashSet<String> jars = new java.util.LinkedHashSet<>();
        for (String line : content.split("\\R")) {
            for (String piece : line.split(File.pathSeparator)) {
                String trimmed = piece.trim();
                if (!trimmed.isEmpty()) {
                    jars.add(trimmed);
                }
            }
        }
        return new ArrayList<>(jars);
    }

    private List<String> getGradleDependencies(java.nio.file.Path projectPath) {
        // Sprint 11 Phase C: ask Gradle for the actual classpath via the
        // Tooling API. Falls back to an empty list when Gradle isn't
        // reachable (no internet on first run, daemon failure, etc.) —
        // build/classes/java/{main,test} added in addDependencyEntries
        // remains as a backstop for the heuristic case.
        return readGradleProjectModel(projectPath)
            .map(model -> model.classpathJars().stream()
                .map(java.nio.file.Path::toString)
                .toList())
            .orElseGet(List::of);
    }

    /**
     * Sprint 11 Phase C — Gradle Tooling API integration.
     *
     * <p>Connects to Gradle via the embedded
     * {@code gradle-tooling-api} jar (see {@code Bundle-ClassPath} in
     * MANIFEST.MF), queries the {@link EclipseProject} model, and
     * returns the resolved source directories and classpath jars.</p>
     *
     * <p>Returns {@link Optional#empty()} on any failure (no Gradle
     * distribution available, daemon launch failure, project doesn't
     * apply the {@code java} plugin, etc.) so callers can fall back to
     * heuristics. Failures are logged at debug level — they're expected
     * for non-Gradle projects and CI environments without network access.</p>
     */
    public static Optional<GradleProjectModel> readGradleProjectModel(java.nio.file.Path projectPath) {
        if (!Files.isDirectory(projectPath)) {
            return Optional.empty();
        }
        boolean hasBuildScript = Files.isRegularFile(projectPath.resolve("build.gradle"))
            || Files.isRegularFile(projectPath.resolve("build.gradle.kts"));
        if (!hasBuildScript) {
            return Optional.empty();
        }
        GradleConnector connector = GradleConnector.newConnector()
            .forProjectDirectory(projectPath.toFile());
        try (ProjectConnection connection = connector.connect()) {
            EclipseProject eclipseProject = connection.getModel(EclipseProject.class);
            List<java.nio.file.Path> srcPaths = new ArrayList<>();
            for (EclipseSourceDirectory srcDir : eclipseProject.getSourceDirectories()) {
                java.nio.file.Path resolved = srcDir.getDirectory().toPath().toAbsolutePath().normalize();
                if (Files.isDirectory(resolved)) {
                    srcPaths.add(resolved);
                }
            }
            List<java.nio.file.Path> classpathJars = new ArrayList<>();
            for (EclipseExternalDependency dep : eclipseProject.getClasspath()) {
                java.nio.file.Path jar = dep.getFile().toPath().toAbsolutePath().normalize();
                if (Files.isRegularFile(jar)) {
                    classpathJars.add(jar);
                }
            }
            return Optional.of(new GradleProjectModel(srcPaths, classpathJars));
        } catch (Exception e) {
            log.debug("Gradle Tooling API failed for {}: {}", projectPath, e.getMessage());
            return Optional.empty();
        } finally {
            // Daemons spawned by the Tooling API would otherwise stick around
            // after the test JVM exits. Disconnecting the singleton-per-JVM
            // connector tells them to stop on the next idle cycle.
            try {
                ((org.gradle.tooling.internal.consumer.DefaultGradleConnector) connector).disconnect();
            } catch (Throwable t) {
                // Best-effort cleanup; ignore if the internal API isn't available.
            }
        }
    }

    /** Outcome of {@link #readGradleProjectModel(java.nio.file.Path)}. */
    public record GradleProjectModel(
        List<java.nio.file.Path> srcPaths,
        List<java.nio.file.Path> classpathJars
    ) {}

    /**
     * Get dependency JARs from Bazel build output.
     * Scans bazel-bin and bazel-out for JAR files rather than running a Bazel subprocess,
     * similar to how Gradle dependencies are resolved via build output.
     */
    private List<String> getBazelDependencies(java.nio.file.Path projectPath) {
        List<String> jars = new ArrayList<>();
        scanBazelDirForJars(projectPath.resolve("bazel-bin"), jars);
        scanBazelDirForJars(projectPath.resolve("bazel-out"), jars);
        log.debug("Found {} JARs from Bazel output", jars.size());
        return jars;
    }

    private void scanBazelDirForJars(java.nio.file.Path dir, List<String> jars) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<java.nio.file.Path> stream = Files.walk(dir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                  .filter(Files::isRegularFile)
                  .map(java.nio.file.Path::toString)
                  .forEach(jars::add);
        } catch (IOException e) {
            log.warn("Failed to scan {} for JARs: {}", dir, e.getMessage());
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Count Java source files in the project.
     * Supports multi-module projects.
     */
    public int countSourceFiles(java.nio.file.Path projectPath) {
        int count = 0;
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);

        for (java.nio.file.Path srcPath : sourcePaths) {
            try (Stream<java.nio.file.Path> stream = Files.walk(srcPath)) {
                count += (int) stream.filter(p -> p.toString().endsWith(".java")).count();
            } catch (IOException e) {
                log.warn("Failed to count files in {}", srcPath, e);
            }
        }
        return count;
    }

    /**
     * Find all packages in the project.
     * Supports multi-module projects.
     */
    public List<String> findPackages(java.nio.file.Path projectPath) {
        List<String> packages = new ArrayList<>();
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);

        for (java.nio.file.Path srcPath : sourcePaths) {
            try (Stream<java.nio.file.Path> stream = Files.walk(srcPath)) {
                stream.filter(Files::isDirectory)
                      .filter(this::containsJavaFiles)
                      .map(p -> srcPath.relativize(p).toString())
                      .map(s -> s.replace(File.separator, "."))
                      .filter(s -> !s.isEmpty())
                      .filter(s -> !packages.contains(s))  // Avoid duplicates
                      .forEach(packages::add);
            } catch (IOException e) {
                log.warn("Failed to find packages in {}", srcPath, e);
            }
        }

        return packages;
    }

    /**
     * Scan for Java source directories in a Bazel project.
     * Looks for directories containing both a BUILD/BUILD.bazel file and .java files.
     * Skips bazel-* output directories.
     */
    private void addBazelSourcePaths(java.nio.file.Path projectPath, List<java.nio.file.Path> sourcePaths) {
        try (Stream<java.nio.file.Path> stream = Files.walk(projectPath)) {
            stream.filter(Files::isDirectory)
                  .filter(dir -> !IGNORED_DIRS.contains(dir.getFileName().toString()))
                  .filter(dir -> !isBazelOutputDirectory(projectPath, dir))
                  .filter(this::isBazelJavaPackage)
                  .forEach(sourcePaths::add);
        } catch (IOException e) {
            log.warn("Failed to scan Bazel project for source directories: {}", e.getMessage());
        }
        log.debug("Found {} Bazel source directories", sourcePaths.size());
    }

    private boolean isBazelOutputDirectory(java.nio.file.Path projectRoot, java.nio.file.Path dir) {
        if (dir.equals(projectRoot)) {
            return false;
        }
        java.nio.file.Path relative = projectRoot.relativize(dir);
        String first = relative.getName(0).toString();
        return first.startsWith("bazel-");
    }

    private boolean isBazelJavaPackage(java.nio.file.Path dir) {
        boolean hasBuildFile = Files.exists(dir.resolve("BUILD")) ||
                               Files.exists(dir.resolve("BUILD.bazel"));
        return hasBuildFile && containsJavaFiles(dir);
    }

    private boolean containsJavaFiles(java.nio.file.Path dir) {
        try (Stream<java.nio.file.Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

    // ============================================================
    // Portable project-metadata helpers (ADR 0004).
    //
    // Pure java.nio.file.Path + DOM/XML / java.util.jar.Manifest parsing.
    // No JDT, OSGi, or Eclipse Workspace types in helper signatures.
    // Designed to be lifted verbatim into a future Eclipse IDE plugin or
    // LSP-based standalone server.
    // ============================================================

    /** pom.xml &lt;sourceDirectory&gt; / &lt;testSourceDirectory&gt; overrides. */
    record SourceDirs(Optional<java.nio.file.Path> srcMain, Optional<java.nio.file.Path> srcTest) {
        static SourceDirs empty() {
            return new SourceDirs(Optional.empty(), Optional.empty());
        }
    }

    /** Eclipse .classpath src/lib/output entries. */
    record ClasspathInfo(List<java.nio.file.Path> srcPaths,
                          List<java.nio.file.Path> libPaths,
                          Optional<java.nio.file.Path> outputPath) {
        static ClasspathInfo empty() {
            return new ClasspathInfo(List.of(), List.of(), Optional.empty());
        }
    }

    /**
     * Read the top-level &lt;packaging&gt; element from pom.xml.
     * Returns {@code Optional.empty()} when the pom is missing, malformed,
     * or has no &lt;packaging&gt; element (which Maven treats as "jar").
     */
    static Optional<String> readPomPackaging(java.nio.file.Path pomXml) {
        if (!Files.isRegularFile(pomXml)) {
            return Optional.empty();
        }
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(pomXml.toFile());
            // <packaging> is always a direct child of the project root in pom.xml.
            Element root = doc.getDocumentElement();
            if (root == null) {
                return Optional.empty();
            }
            NodeList kids = root.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i) instanceof Element el && "packaging".equals(el.getTagName())) {
                    String text = el.getTextContent();
                    if (text != null) {
                        String trimmed = text.trim();
                        if (!trimmed.isEmpty()) {
                            return Optional.of(trimmed);
                        }
                    }
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to parse pom.xml at {}: {}", pomXml, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * True for Tycho packaging types (eclipse-plugin, eclipse-feature, …)
     * for which Maven's classpath-extraction goal produces misleading results.
     */
    static boolean isTychoPackaging(String packaging) {
        return packaging != null && TYCHO_PACKAGINGS.contains(packaging);
    }

    /**
     * True if {@code projectPath/pom.xml} declares a Tycho packaging type.
     * Used to gate the {@code mvn dependency:build-classpath} shell-out.
     */
    static boolean isTychoProject(java.nio.file.Path projectPath) {
        return readPomPackaging(projectPath.resolve("pom.xml"))
            .map(ProjectImporter::isTychoPackaging)
            .orElse(false);
    }

    /**
     * Read {@code Bundle-SymbolicName} from {@code META-INF/MANIFEST.MF},
     * stripping any directives such as {@code ;singleton:=true}.
     * Returns {@code Optional.empty()} when the manifest is absent, malformed,
     * or has no {@code Bundle-SymbolicName} header.
     *
     * <p>Phase B (Sprint 11): used by the workspace bundle pool to register
     * each loaded PDE bundle by its symbolic name so {@code Require-Bundle}
     * dependencies between sibling projects in the same workspace resolve
     * to project-typed classpath entries.</p>
     */
    public static Optional<String> readManifestSymbolicName(java.nio.file.Path projectRoot) {
        java.nio.file.Path manifestPath = projectRoot.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(manifestPath)) {
            Manifest manifest = new Manifest(in);
            Attributes attrs = manifest.getMainAttributes();
            String value = attrs.getValue("Bundle-SymbolicName");
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(stripDirectives(value));
        } catch (IOException e) {
            log.warn("Failed to parse MANIFEST.MF at {}: {}", manifestPath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Read {@code Require-Bundle} from {@code META-INF/MANIFEST.MF} and
     * return the list of required bundle symbolic names (without version
     * or visibility directives).
     *
     * <p>Multi-line OSGi headers (continuation lines starting with a single
     * space) are joined automatically by {@link Manifest}; this method
     * handles the resulting comma-separated list and per-entry directives.</p>
     */
    public static List<String> readManifestRequireBundle(java.nio.file.Path projectRoot) {
        java.nio.file.Path manifestPath = projectRoot.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.isRegularFile(manifestPath)) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(manifestPath)) {
            Manifest manifest = new Manifest(in);
            String header = manifest.getMainAttributes().getValue("Require-Bundle");
            if (header == null || header.isBlank()) {
                return List.of();
            }
            // Split on commas at the top level (commas inside quoted version
            // ranges would be a problem in theory; in practice OSGi version
            // ranges use semicolons after the comma-separated entries).
            List<String> result = new ArrayList<>();
            for (String entry : header.split(",")) {
                String name = stripDirectives(entry).trim();
                if (!name.isEmpty()) {
                    result.add(name);
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("Failed to parse MANIFEST.MF at {}: {}", manifestPath, e.getMessage());
            return List.of();
        }
    }

    /**
     * Read {@code Import-Package} from {@code META-INF/MANIFEST.MF} and return
     * the imported package names (attributes/directives stripped; {@code java.*}
     * skipped — the JRE container provides those). Sprint 23 (D7): feeds the
     * external-pool Export-Package resolution.
     */
    public static List<String> readManifestImportPackage(java.nio.file.Path projectRoot) {
        java.nio.file.Path manifestPath = projectRoot.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.isRegularFile(manifestPath)) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(manifestPath)) {
            Manifest manifest = new Manifest(in);
            String header = manifest.getMainAttributes().getValue("Import-Package");
            if (header == null || header.isBlank()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (String entry : ExternalBundlePool.splitTopLevel(header)) {
                String name = stripDirectives(entry).trim();
                if (!name.isEmpty() && !name.startsWith("java.")) {
                    result.add(name);
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("Failed to parse MANIFEST.MF at {}: {}", manifestPath, e.getMessage());
            return List.of();
        }
    }

    /** Strip OSGi attribute / directive suffixes (e.g. {@code ;singleton:=true}). */
    private static String stripDirectives(String value) {
        int semi = value.indexOf(';');
        return (semi == -1 ? value : value.substring(0, semi)).trim();
    }

    /**
     * Read pom.xml &lt;build&gt;&lt;sourceDirectory&gt; and &lt;testSourceDirectory&gt;.
     * Resolves declared paths against the pom's directory.
     * Returns SourceDirs.empty() if pom.xml is absent, malformed, or has no overrides.
     */
    static SourceDirs readPomSourceDirs(java.nio.file.Path pomXml) {
        if (!Files.isRegularFile(pomXml)) {
            return SourceDirs.empty();
        }
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(pomXml.toFile());
            java.nio.file.Path pomDir = pomXml.toAbsolutePath().getParent();
            return new SourceDirs(
                readBuildPath(doc, "sourceDirectory", pomDir),
                readBuildPath(doc, "testSourceDirectory", pomDir)
            );
        } catch (Exception e) {
            log.warn("Failed to parse pom.xml at {}: {}", pomXml, e.getMessage());
            return SourceDirs.empty();
        }
    }

    private static Optional<java.nio.file.Path> readBuildPath(Document doc, String elementName,
            java.nio.file.Path pomDir) {
        NodeList builds = doc.getElementsByTagName("build");
        for (int i = 0; i < builds.getLength(); i++) {
            NodeList kids = ((Element) builds.item(i)).getElementsByTagName(elementName);
            if (kids.getLength() > 0) {
                String text = kids.item(0).getTextContent().trim();
                if (!text.isEmpty()) {
                    // v2.9.1 (dogfood D1): interpolate the basedir properties — real
                    // poms write ${project.basedir}/../..; resolving the literal text
                    // produced a path with a ${...} component that silently failed
                    // the isDirectory filter.
                    text = text.replace("${project.basedir}", pomDir.toString())
                               .replace("${basedir}", pomDir.toString());
                    return Optional.of(pomDir.resolve(text).normalize());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Read Eclipse .classpath at the project root.
     * Resolves &lt;classpathentry path="..."&gt; values against projectRoot
     * (so "../lib/foo.jar"-style relative refs work).
     * Returns ClasspathInfo.empty() if .classpath is absent or malformed.
     */
    static ClasspathInfo readEclipseClasspath(java.nio.file.Path projectRoot) {
        java.nio.file.Path file = projectRoot.resolve(".classpath");
        if (!Files.isRegularFile(file)) {
            return ClasspathInfo.empty();
        }
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(file.toFile());
            NodeList entries = doc.getElementsByTagName("classpathentry");
            List<java.nio.file.Path> srcPaths = new ArrayList<>();
            List<java.nio.file.Path> libPaths = new ArrayList<>();
            Optional<java.nio.file.Path> outputPath = Optional.empty();
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                String kind = entry.getAttribute("kind");
                String path = entry.getAttribute("path");
                if (path.isEmpty()) {
                    continue;
                }
                java.nio.file.Path resolved = projectRoot.resolve(path).normalize();
                switch (kind) {
                    case "src" -> srcPaths.add(resolved);
                    case "lib" -> libPaths.add(resolved);
                    case "output" -> outputPath = Optional.of(resolved);
                    default -> {
                        // "con" (containers), "var" (variables), unknown kinds: ignore.
                    }
                }
            }
            return new ClasspathInfo(srcPaths, libPaths, outputPath);
        } catch (Exception e) {
            log.warn("Failed to parse .classpath at {}: {}", file, e.getMessage());
            return ClasspathInfo.empty();
        }
    }
}
