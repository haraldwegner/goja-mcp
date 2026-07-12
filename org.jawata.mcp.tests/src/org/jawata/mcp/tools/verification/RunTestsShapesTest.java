package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sprint 23 (Stage 3) — the remaining project shapes on the forked-runner
 * spine, each proven against its known fixture totals:
 *
 * <ul>
 *   <li><b>Gradle</b> — ad-hoc java-plugin project (mavenLocal, no network;
 *       skippable via {@code jawata.skip.gradle=true} like the importer's own
 *       Tooling-API tests);</li>
 *   <li><b>plain Java</b> — Eclipse-style {@code .classpath} project plus the
 *       {@code extraClasspath} launch descriptor, proven by a runtime-only
 *       reflective dependency that fails without it and passes with it;</li>
 *   <li><b>Maven reactor cross-module</b> — module-b tests exercising
 *       module-a classes (the {@code reactor-cross} fixture).</li>
 * </ul>
 */
class RunTestsShapesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    // ------------------------------------------------------------- Gradle

    @Test
    @DisplayName("Gradle shape: java-plugin project runs through the spine, counts = fixture totals")
    void gradleProject_runsThroughSpine() throws Exception {
        assumeTrue(!"true".equalsIgnoreCase(System.getProperty("jawata.skip.gradle", "false")),
            "Gradle Tooling API tests skipped via jawata.skip.gradle=true");

        Path root = helper.getTempDirectory().resolve("gradle-shape");
        Files.createDirectories(root.resolve("src/test/java/com/example/gradle"));
        Files.writeString(root.resolve("settings.gradle"),
            "rootProject.name = 'gradle-shape'\n");
        Files.writeString(root.resolve("build.gradle"), """
            plugins { id 'java' }
            repositories { mavenLocal() }
            dependencies { testImplementation 'org.junit.jupiter:junit-jupiter:5.11.4' }
            """);
        Files.writeString(root.resolve("src/test/java/com/example/gradle/GradleShapeTest.java"), """
            package com.example.gradle;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            public class GradleShapeTest {
                @Test void passesOne() { assertEquals(1, 1); }
                @Test void passesTwo() { assertEquals(2, 2); }
                @Test void failsDeliberately() { assertEquals(1, 2); }
            }
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);
        RunTestsTool tool = new RunTestsTool(() -> service);

        ObjectNode args = classScope("com.example.gradle.GradleShapeTest");
        args.put("timeoutSeconds", 120);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> summary = summaryOf(r);
        assertEquals(3, summary.get("total"), "summary: " + summary);
        assertEquals(2, summary.get("passed"), "summary: " + summary);
        assertEquals(1, summary.get("failed"), "summary: " + summary);
        assertEquals(Boolean.TRUE, summary.get("evidenceFinalized"), "summary: " + summary);
    }

    // --------------------------------------------------------- plain Java

    @Test
    @DisplayName("plain-Java shape: .classpath project + extraClasspath launch descriptor (fails without, passes with)")
    void plainJava_extraClasspathDescriptor() throws Exception {
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        Path jupiterApi = m2.resolve(
            "org/junit/jupiter/junit-jupiter-api/5.11.4/junit-jupiter-api-5.11.4.jar");
        Path opentest4j = m2.resolve("org/opentest4j/opentest4j/1.3.0/opentest4j-1.3.0.jar");
        Path runtimeOnlyJar = m2.resolve(
            "org/eclipse/jdt/org.eclipse.jdt.annotation/2.3.0/org.eclipse.jdt.annotation-2.3.0.jar");
        assumeTrue(Files.isRegularFile(jupiterApi) && Files.isRegularFile(runtimeOnlyJar)
                && Files.isRegularFile(opentest4j),
            "local repository must hold the fixture jars (our own build guarantees them)");

        Path root = helper.getTempDirectory().resolve("plain-shape");
        Files.createDirectories(root.resolve("src/com/example/plain"));
        Files.writeString(root.resolve(".project"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <projectDescription>
                <name>plain-shape</name>
                <buildSpec><buildCommand><name>org.eclipse.jdt.core.javabuilder</name></buildCommand></buildSpec>
                <natures><nature>org.eclipse.jdt.core.javanature</nature></natures>
            </projectDescription>
            """);
        Files.writeString(root.resolve(".classpath"), """
            <?xml version="1.0" encoding="UTF-8"?>
            <classpath>
                <classpathentry kind="src" path="src"/>
                <classpathentry kind="con" path="org.eclipse.jdt.launching.JRE_CONTAINER"/>
                <classpathentry kind="lib" path="%s"/>
                <classpathentry kind="lib" path="%s"/>
                <classpathentry kind="output" path="bin"/>
            </classpath>
            """.formatted(jupiterApi, opentest4j));
        Files.writeString(root.resolve("src/com/example/plain/PlainShapeTest.java"), """
            package com.example.plain;

            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertNotNull;

            public class PlainShapeTest {
                @Test void plainPasses() { assertNotNull("x"); }
                @Test void needsRuntimeOnlyJar() throws Exception {
                    // Compiles with NO reference to the jar; at runtime the class
                    // must come from the extraClasspath launch descriptor.
                    assertNotNull(Class.forName("org.eclipse.jdt.annotation.NonNull"));
                }
            }
            """);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(root);
        RunTestsTool tool = new RunTestsTool(() -> service);

        // WITHOUT the descriptor: the reflective test fails (CNFE), the plain one passes.
        ObjectNode without = classScope("com.example.plain.PlainShapeTest");
        without.put("timeoutSeconds", 120);
        ToolResponse r1 = tool.execute(without);
        assertTrue(r1.isSuccess(), "got: " + r1.getError());
        Map<String, Object> s1 = summaryOf(r1);
        assertEquals(1, s1.get("passed"), "without descriptor: " + s1);
        assertEquals(1, s1.get("failed"), "without descriptor: " + s1
            + " failures: " + dataOf(r1).get("failures"));

        // WITH the descriptor: both pass.
        ObjectNode with = classScope("com.example.plain.PlainShapeTest");
        with.put("timeoutSeconds", 120);
        with.putArray("extraClasspath").add(runtimeOnlyJar.toString());
        ToolResponse r2 = tool.execute(with);
        assertTrue(r2.isSuccess(), "got: " + r2.getError());
        Map<String, Object> s2 = summaryOf(r2);
        assertEquals(2, s2.get("passed"), "with descriptor: " + s2
            + " failures: " + dataOf(r2).get("failures"));
        assertEquals(0, s2.get("failed"), "with descriptor: " + s2);
    }

    // ------------------------------------------------- reactor cross-module

    @Test
    @DisplayName("reactor shape: module-b tests exercise module-a classes")
    void reactorCrossModule_bTestsExerciseA() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("reactor-cross");
        RunTestsTool tool = new RunTestsTool(() -> service);

        ObjectNode args = classScope("com.example.cross.CrossModuleTest");
        args.put("timeoutSeconds", 120);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> summary = summaryOf(r);
        assertEquals(2, summary.get("total"), "summary: " + summary);
        assertEquals(2, summary.get("passed"), "summary: " + summary
            + " failures: " + dataOf(r).get("failures"));
        assertEquals(Boolean.TRUE, summary.get("evidenceFinalized"), "summary: " + summary);
    }

    private ObjectNode classScope(String typeName) {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", typeName);
        args.put("framework", "junit5");
        return args;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summaryOf(ToolResponse r) {
        return (Map<String, Object>) dataOf(r).get("summary");
    }
}
