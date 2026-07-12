package org.jawata.boot;

import org.eclipse.core.runtime.adaptor.EclipseStarter;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Sprint 22d — the boot glue: programmatic Equinox startup over a
 * {@code bundles/} directory, replacing the Eclipse launcher + the
 * p2-generated {@code config.ini} (the jdt.ls production pattern).
 *
 * <p>Modes:</p>
 * <ul>
 *   <li><b>serve</b> (default): {@code java -jar org.jawata.boot.jar -data <ws> [app args]}
 *       — session-isolates the workspace exactly like the old JawataLauncher
 *       (UUID subdir, original path published as {@code jawata.workspace.root},
 *       cleanup on shutdown), then runs {@code org.jawata.mcp.application}.</li>
 *   <li><b>-runTests</b>: boots the framework without the application, installs
 *       the test fragments + JUnit bundles from {@code test-bundles/}, and runs
 *       the whole suite in-framework via {@code SpikeTestMain} (the
 *       tycho-surefire replacement we own). Exit code = failed count.</li>
 * </ul>
 */
public final class JawataBoot {

    private static final Map<String, String> START_MARKERS = Map.of(
        "org.apache.felix.scr", "@1:start",
        "org.eclipse.equinox.common", "@2:start",
        "org.eclipse.equinox.app", "@2:start",
        "org.eclipse.equinox.registry", "@2:start",
        "org.eclipse.equinox.preferences", "@2:start",
        "org.eclipse.core.runtime", "@start"
    );

    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("jawata.boot.home",
            Path.of(JawataBoot.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).getParent().toString()));
        Path bundlesDir = home.resolve("bundles");
        if (!Files.isDirectory(bundlesDir)) {
            System.err.println("FATAL: no bundles/ directory beside the boot jar: " + bundlesDir);
            System.exit(2);
        }

        boolean runTests = false;
        List<String> passThrough = new ArrayList<>();
        for (String a : args) {
            if ("-runTests".equals(a)) runTests = true; else passThrough.add(a);
        }

        System.exit(runTests ? runTests(home, bundlesDir) : serve(home, bundlesDir, passThrough));
    }

    // ------------------------------------------------------------------ serve

    private static int serve(Path home, Path bundlesDir, List<String> args) throws Exception {
        // Session isolation, ported in behaviour from JawataLauncher:
        // -data <base> becomes <base>/<uuid8>; the ORIGINAL base is published as
        // jawata.workspace.root; the session dir is deleted on shutdown.
        SessionArgs session = isolateSession(args);
        if (session.sessionPath == null) {
            System.err.println("Warning: No -data argument provided. Session isolation disabled.");
        } else {
            final Path toClean = session.sessionPath;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> cleanupSession(toClean)));
        }
        List<String> modified = session.rewrittenArgs;

        Map<String, String> props = baseProps(home, bundlesDir, null);
        props.put("eclipse.application", "org.jawata.mcp.application");
        EclipseStarter.setInitialProperties(props);
        try {
            Object exit = EclipseStarter.run(modified.toArray(new String[0]), null);
            return exit instanceof Integer i ? i : 0;
        } finally {
            EclipseStarter.shutdown();
        }
    }

    // -------------------------------------------------------------- run tests

    private static int runTests(Path home, Path bundlesDir) throws Exception {
        Path testBundles = home.resolve("test-bundles");
        if (!Files.isDirectory(testBundles)) {
            System.err.println("FATAL: -runTests needs a test-bundles/ directory beside the boot jar");
            return 2;
        }
        Path instance = Files.createTempDirectory("jawata-test-ws");

        Map<String, String> props = baseProps(home, bundlesDir, testBundles);
        props.put("eclipse.ignoreApp", "true");
        props.put("osgi.noShutdown", "true");
        props.put("osgi.instance.area", instance.toUri().toString());
        EclipseStarter.setInitialProperties(props);
        BundleContext ctx = EclipseStarter.startup(new String[0], null);
        try {
            return runInFramework(ctx, testBundles);
        } finally {
            // v2.9.0 lesson: failure must never skip shutdown — Equinox's
            // non-daemon threads otherwise keep a dead JVM alive (the fake
            // 75-minute CI "hang" was a 30-second resolution failure).
            EclipseStarter.shutdown();
        }
    }

    private static int runInFramework(BundleContext ctx, Path testBundles) throws Exception {
        String filter = System.getProperty("jawata.test.filter");
        if (filter != null && !filter.isBlank()) {
            System.out.println("Filter '" + filter + "' active");
        }
        List<String> classNames = discoverTestClasses(testBundles, filter);
        System.out.println("Discovered " + classNames.size() + " test classes");

        Bundle mcp = null;
        for (Bundle b : ctx.getBundles()) {
            if ("org.jawata.mcp".equals(b.getSymbolicName())) mcp = b;
        }
        if (mcp == null) {
            System.err.println("FATAL: org.jawata.mcp bundle not found");
            return 2;
        }
        // Fail-fast resolution gate (v2.9.0): force the resolver NOW — an
        // incomplete dist dies here in seconds with the full unresolved-
        // requirement chain, instead of a bare ClassNotFoundException later.
        mcp.start();
        // Boot-side watchdog + stage markers: the LAST blind window (runner-class
        // load/activation, before SpikeTestMain's own watchdog exists). Every
        // stage prints; 5 idle minutes anywhere here → thread dump + halt(125).
        final long[] bootStage = { System.currentTimeMillis() };
        final String[] bootWhere = { "loading runner class (bundle activation)" };
        Thread bootDog = new Thread(() -> {
            while (true) {
                try { Thread.sleep(30_000); } catch (InterruptedException e) { return; }
                if (System.currentTimeMillis() - bootStage[0] > 300_000) {
                    System.out.println("\n=== BOOT STALL: stuck in " + bootWhere[0] + " ===");
                    Thread.getAllStackTraces().forEach((t, st) -> {
                        System.out.println("--- thread: " + t.getName() + " (" + t.getState() + ")");
                        for (StackTraceElement e : st) System.out.println("    at " + e);
                    });
                    System.out.flush();
                    Runtime.getRuntime().halt(125);
                }
            }
        }, "boot-watchdog");
        bootDog.setDaemon(true);
        bootDog.start();

        System.out.println("stage: loading runner class"); System.out.flush();
        Class<?> main = mcp.loadClass("org.jawata.mcp.SpikeTestMain");
        bootStage[0] = System.currentTimeMillis(); bootWhere[0] = "resolving run() method";
        System.out.println("stage: runner class loaded"); System.out.flush();
        Method run = main.getMethod("run", String[].class);
        bootStage[0] = System.currentTimeMillis();
        bootWhere[0] = "runner init (SpikeTestMain static init / entering run)";
        System.out.println("stage: invoking runner"); System.out.flush();
        // disarm: the runner's own watchdog takes over at run() entry; the
        // remaining unguarded slice is the reflective dispatch itself
        // (SpikeTestMain has no static state) — localized by the marker above.
        bootDog.interrupt();
        return (int) run.invoke(null, (Object) classNames.toArray(new String[0]));
    }

    // ---------------------------------------------------------- testable seams

    /** Result of {@link #isolateSession}: the rewritten argv + the created session dir. */
    static final class SessionArgs {
        final List<String> rewrittenArgs;
        final Path sessionPath;
        SessionArgs(List<String> rewrittenArgs, Path sessionPath) {
            this.rewrittenArgs = rewrittenArgs;
            this.sessionPath = sessionPath;
        }
    }

    /**
     * Session isolation (JawataLauncher behaviour, verbatim): {@code -data <base>}
     * becomes {@code <base>/<uuid8>} (dir created), the ORIGINAL base is published
     * as {@code jawata.workspace.root} unless already set; all other args pass
     * through untouched. Package-visible for unit tests.
     */
    static SessionArgs isolateSession(List<String> args) throws java.io.IOException {
        List<String> modified = new ArrayList<>();
        Path sessionPath = null;
        for (int i = 0; i < args.size(); i++) {
            if ("-data".equals(args.get(i)) && i + 1 < args.size()) {
                Path basePath = Path.of(args.get(i + 1));
                if (System.getProperty("jawata.workspace.root") == null) {
                    System.setProperty("jawata.workspace.root", basePath.toAbsolutePath().toString());
                }
                sessionPath = basePath.resolve(UUID.randomUUID().toString().substring(0, 8));
                Files.createDirectories(sessionPath);
                modified.add("-data");
                modified.add(sessionPath.toString());
                i++;
            } else {
                modified.add(args.get(i));
            }
        }
        return new SessionArgs(modified, sessionPath);
    }

    /**
     * Test-class discovery: scan the org.jawata.* jars in {@code testBundles} for
     * top-level {@code *Test.class} entries; apply the optional substring
     * {@code filter}. Package-visible for unit tests.
     */
    static List<String> discoverTestClasses(Path testBundles, String filter) throws Exception {
        List<String> classNames = new ArrayList<>();
        try (Stream<Path> jars = Files.list(testBundles)) {
            for (Path jar : jars.filter(p -> p.getFileName().toString().startsWith("org.jawata.")
                    && p.getFileName().toString().endsWith(".jar")).toList()) {
                try (JarFile jf = new JarFile(jar.toFile())) {
                    jf.stream().map(java.util.jar.JarEntry::getName)
                        .filter(n -> n.endsWith("Test.class") && !n.contains("$"))
                        .map(n -> n.substring(0, n.length() - 6).replace('/', '.'))
                        .forEach(classNames::add);
                }
            }
        }
        classNames.sort(String::compareTo);
        if (filter != null && !filter.isBlank()) {
            classNames.removeIf(cn -> !cn.contains(filter));
        }
        return classNames;
    }

    // ------------------------------------------------------------------ common

    private static Map<String, String> baseProps(Path home, Path bundlesDir, Path extraBundles)
            throws Exception {
        Map<String, String> props = new HashMap<>();
        StringBuilder list = new StringBuilder(osgiBundlesList(bundlesDir));
        if (extraBundles != null) {
            list.append(',').append(osgiBundlesList(extraBundles));
        }
        props.put("osgi.bundles", list.toString());
        props.put("osgi.bundles.defaultStartLevel", "4");
        props.put("osgi.configuration.area",
            Files.createTempDirectory("jawata-boot-config").toString());
        props.put("osgi.install.area", home.toString());
        props.put("osgi.compatibility.bootdelegation", "true");
        return props;
    }

    /** Package-visible for unit tests. */
    static String osgiBundlesList(Path dir) throws Exception {
        List<String> entries = new ArrayList<>();
        try (Stream<Path> jars = Files.list(dir)) {
            jars.filter(p -> p.getFileName().toString().endsWith(".jar"))
                .sorted()
                .forEach(jar -> {
                    String name = jar.getFileName().toString();
                    String marker = START_MARKERS.entrySet().stream()
                        .filter(e -> name.startsWith(e.getKey() + "-")
                            || name.startsWith(e.getKey() + "_"))
                        .map(Map.Entry::getValue)
                        .findFirst().orElse("");
                    entries.add("reference:file:" + jar.toAbsolutePath() + marker);
                });
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("no bundles in " + dir);
        }
        return String.join(",", entries);
    }

    private static void cleanupSession(Path sessionPath) {
        if (sessionPath == null || !Files.exists(sessionPath)) return;
        try (Stream<Path> walk = Files.walk(sessionPath)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private JawataBoot() {}
}
