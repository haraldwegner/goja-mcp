package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.GetCallHierarchyIncomingTool;
import org.jawata.mcp.tools.InspectTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 23 (D8 + D11, C12) — source by FQN on Maven and PDE trees (origin
 * always declared: workspace / attached / sources-jar / honest stub), and
 * the incoming call hierarchy free of Javadoc phantom callers.
 */
class LibSourceAndHierarchyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ObjectMapper om;

    @BeforeEach
    void setUp() {
        om = new ObjectMapper();
    }

    @Test
    @DisplayName("D8: workspace / JDK / dependency / deterministic sources-jar / PDE — origin declared")
    void sourceByFqn_allOrigins() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        InspectTool inspect = new InspectTool(() -> service);

        // Workspace type: the real source.
        Map<String, Object> workspace = source(inspect, "com.example.HelloWorld");
        assertEquals("workspace-source", workspace.get("origin"), "got: " + workspace);
        assertTrue(String.valueOf(workspace.get("source")).contains("class HelloWorld"));

        // JDK type: attached src.zip where the JDK ships one, honest stub else.
        Map<String, Object> jdk = source(inspect, "java.util.ArrayList");
        assertNotNull(jdk.get("origin"), "got: " + jdk);
        String jdkSource = String.valueOf(jdk.get("source"));
        assertTrue(jdkSource.contains("ArrayList"), "got origin " + jdk.get("origin"));
        if ("disassembled-stub".equals(jdk.get("origin"))) {
            assertTrue(jdkSource.contains("DISASSEMBLED STUB"), "the stub must be labeled");
        }

        // Dependency type (jupiter on the fixture classpath): source or labeled stub.
        Map<String, Object> dep = source(inspect, "org.junit.jupiter.api.Test");
        assertNotNull(dep.get("origin"), "got: " + dep);
        assertTrue(String.valueOf(dep.get("source")).contains("Test"),
            "got origin " + dep.get("origin"));

        // DETERMINISTIC sources-jar case: a lib built in-test with a sibling
        // -sources.jar, loaded via an Eclipse-style .classpath project.
        Path work = Files.createTempDirectory("jawata-libsrc-");
        Path srcDir = Files.createDirectories(work.resolve("libsrc/com/libx"));
        Files.writeString(srcDir.resolve("LibThing.java"), """
            package com.libx;

            /** ORIGINAL-COMMENT-MARKER */
            public class LibThing {
                public int libValue() {
                    return 11;
                }
            }
            """);
        Path classesDir = Files.createDirectories(work.resolve("classes"));
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null,
            "-d", classesDir.toString(), srcDir.resolve("LibThing.java").toString());
        assertEquals(0, rc, "javac must succeed");
        Path libJar = work.resolve("libx-1.0.jar");
        jar(libJar, classesDir);
        Path sourcesJar = work.resolve("libx-1.0-sources.jar");
        jar(sourcesJar, work.resolve("libsrc"));

        Path project = Files.createDirectories(work.resolve("plainlib"));
        Files.createDirectories(project.resolve("src/com/app"));
        Files.writeString(project.resolve("src/com/app/UsesLib.java"), """
            package com.app;
            public class UsesLib {
                public int use() { return new com.libx.LibThing().libValue(); }
            }
            """);
        Files.writeString(project.resolve(".project"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <projectDescription>
                <name>plainlib</name>
                <buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name></buildCommand></buildSpec>
                <natures><nature>org.eclipse.jdt.core.javanature</nature></natures>
            </projectDescription>
            """);
        Files.writeString(project.resolve(".classpath"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <classpath>
                <classpathentry kind="src" path="src"/>
                <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
                <classpathentry kind="lib" path="%s"/>
                <classpathentry kind="output" path="bin"/>
            </classpath>
            """.formatted(libJar));
        JdtServiceImpl libService = new JdtServiceImpl();
        libService.loadProject(project);
        InspectTool libInspect = new InspectTool(() -> libService);
        Map<String, Object> lib = source(libInspect, "com.libx.LibThing");
        assertEquals("sources-jar", lib.get("origin"), "got: " + lib);
        assertTrue(String.valueOf(lib.get("source")).contains("ORIGINAL-COMMENT-MARKER"),
            "the REAL source must come from the sibling sources jar; got: " + lib);
    }

    @Test
    @DisplayName("D8 PDE half: a pool-resolved dependency type is readable by FQN")
    void sourceByFqn_pdeTree() throws Exception {
        String poolsBefore = System.getProperty("jawata.bundle.pools");
        String distRoot = System.getProperty("jawata.dist.root");
        assertNotNull(distRoot, "boot sets jawata.dist.root");
        System.setProperty("jawata.bundle.pools",
            Path.of(distRoot, "bundles") + File.pathSeparator + Path.of(distRoot, "test-bundles"));
        try {
            JdtServiceImpl service = helper.loadWorkspaceCopy("pde-external", "pde-external-tests");
            InspectTool inspect = new InspectTool(() -> service);
            Map<String, Object> jackson = source(inspect,
                "com.fasterxml.jackson.databind.ObjectMapper");
            assertNotNull(jackson.get("origin"), "got: " + jackson);
            assertTrue(String.valueOf(jackson.get("source")).contains("ObjectMapper"),
                "got origin " + jackson.get("origin"));
        } finally {
            if (poolsBefore == null) {
                System.clearProperty("jawata.bundle.pools");
            } else {
                System.setProperty("jawata.bundle.pools", poolsBefore);
            }
        }
    }

    @Test
    @DisplayName("D11: a Javadoc {@link} is NOT a caller — exactly the one real caller remains")
    void incomingHierarchy_noJavadocPhantoms() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        GetCallHierarchyIncomingTool tool = new GetCallHierarchyIncomingTool(() -> service);

        Path file = service.getProjectRoot()
            .resolve("src/main/java/com/example/DocLinked.java");
        String source = Files.readString(file);
        int offset = source.indexOf("public int callee") + "public int ".length();
        int line = (int) source.substring(0, offset).chars().filter(c -> c == '\n').count();
        int column = offset - (source.lastIndexOf('\n', offset) + 1);

        ObjectNode args = om.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", line);
        args.put("column", column);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callers = (List<Map<String, Object>>) data.get("callers");
        assertEquals(1, callers.size(),
            "exactly the ONE real caller — no Javadoc phantom; got: " + callers);
        assertEquals("realCaller", callers.get(0).get("callerMethod"), "got: " + callers);
        assertFalse(String.valueOf(callers).contains("<initializer>"), "got: " + callers);
    }

    // ------------------------------------------------------------- helpers

    private Map<String, Object> source(InspectTool inspect, String typeName) {
        ObjectNode args = om.createObjectNode();
        args.put("kind", "source");
        args.put("typeName", typeName);
        ToolResponse r = inspect.execute(args);
        assertTrue(r.isSuccess(), "inspect source failed for " + typeName + ": " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return data;
    }

    /** Minimal jar of a directory tree (no manifest niceties needed). */
    private static void jar(Path jarFile, Path contentRoot) throws Exception {
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(jarFile));
             var walk = Files.walk(contentRoot)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new java.util.zip.ZipEntry(
                    contentRoot.relativize(p).toString().replace('\\', '/')));
                out.write(Files.readAllBytes(p));
                out.closeEntry();
            }
        }
    }
}
