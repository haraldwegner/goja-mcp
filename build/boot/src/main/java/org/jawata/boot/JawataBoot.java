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
                final Path toClean = sessionPath;
                Runtime.getRuntime().addShutdownHook(new Thread(() -> cleanupSession(toClean)));
                modified.add("-data");
                modified.add(sessionPath.toString());
                i++;
            } else {
                modified.add(args.get(i));
            }
        }
        if (sessionPath == null) {
            System.err.println("Warning: No -data argument provided. Session isolation disabled.");
        }

        Map<String, String> props = baseProps(home, bundlesDir, null);
        props.put("eclipse.application", "org.jawata.mcp.application");
        EclipseStarter.setInitialProperties(props);
        Object exit = EclipseStarter.run(modified.toArray(new String[0]), null);
        EclipseStarter.shutdown();
        return exit instanceof Integer i ? i : 0;
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
        System.out.println("Discovered " + classNames.size() + " test classes");

        Bundle mcp = null;
        for (Bundle b : ctx.getBundles()) {
            if ("org.jawata.mcp".equals(b.getSymbolicName())) mcp = b;
        }
        if (mcp == null) {
            System.err.println("FATAL: org.jawata.mcp bundle not found");
            return 2;
        }
        Class<?> main = mcp.loadClass("org.jawata.mcp.SpikeTestMain");
        Method run = main.getMethod("run", String[].class);
        int failed = (int) run.invoke(null, (Object) classNames.toArray(new String[0]));
        EclipseStarter.shutdown();
        return failed;
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

    private static String osgiBundlesList(Path dir) throws Exception {
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
