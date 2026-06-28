package org.goja.mcp.tools.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 14 Phase C — unit tests for the Gradle text-mutation helper.
 */
class GradleBuildSupportTest {

    @Test
    @DisplayName("reads Groovy-DSL deps with both single- and double-quoted forms")
    void readsGroovyDslDeps_bothQuoteForms() {
        String text = """
            plugins { id 'java' }

            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.14.0'
                api "com.google.guava:guava:33.0.0-jre"
                testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.0'
            }
            """;
        // Read via a temp file
        java.nio.file.Path tmp = createTempBuildFile(text, false);
        List<GradleBuildSupport.DeclaredGradleDep> deps;
        try {
            deps = GradleBuildSupport.readDependencies(tmp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        assertEquals(3, deps.size(), "expected 3 deps; got " + deps);
        assertEquals("implementation", deps.get(0).configuration());
        assertEquals("commons-lang3", deps.get(0).artifactId());
        assertEquals("api", deps.get(1).configuration());
        assertEquals("guava", deps.get(1).artifactId());
        assertEquals("testImplementation", deps.get(2).configuration());
        assertEquals("junit-jupiter-api", deps.get(2).artifactId());
    }

    @Test
    @DisplayName("reads Kotlin-DSL deps with parens form")
    void readsKotlinDslDeps() {
        String text = """
            plugins { java }

            dependencies {
                implementation("org.apache.commons:commons-lang3:3.14.0")
                testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
            }
            """;
        java.nio.file.Path tmp = createTempBuildFile(text, true);
        List<GradleBuildSupport.DeclaredGradleDep> deps;
        try {
            deps = GradleBuildSupport.readDependencies(tmp);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        assertEquals(2, deps.size());
        assertEquals("commons-lang3", deps.get(0).artifactId());
    }

    @Test
    @DisplayName("insertDependency appends to existing dependencies block (Groovy DSL)")
    void insertDependency_groovyDsl_appendsInBlock() {
        String text = """
            plugins { id 'java' }

            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.14.0'
            }
            """;
        String out = GradleBuildSupport.insertDependency(text,
            "com.google.guava", "guava", "33.0.0-jre",
            "implementation", false);
        assertNotNull(out);
        assertTrue(out.contains("commons-lang3"), "preserves existing dep");
        assertTrue(out.contains("guava"), "adds new dep; got:\n" + out);
        assertTrue(out.contains("implementation 'com.google.guava:guava:33.0.0-jre'"),
            "uses Groovy single-quoted form for new line");
    }

    @Test
    @DisplayName("insertDependency creates the dependencies block when none exists")
    void insertDependency_noBlock_createsBlock() {
        String text = """
            plugins { id 'java' }
            """;
        String out = GradleBuildSupport.insertDependency(text,
            "junit", "junit", "4.13.2", "testImplementation", false);
        assertNotNull(out);
        assertTrue(out.contains("dependencies {"),
            "must create dependencies block when missing; got:\n" + out);
        assertTrue(out.contains("testImplementation 'junit:junit:4.13.2'"));
    }

    @Test
    @DisplayName("insertDependency uses Kotlin parens form when isKts=true")
    void insertDependency_kotlinDsl_usesParens() {
        String text = """
            plugins { java }

            dependencies {
                implementation("org.apache.commons:commons-lang3:3.14.0")
            }
            """;
        String out = GradleBuildSupport.insertDependency(text,
            "com.google.guava", "guava", "33.0.0-jre",
            "implementation", true);
        assertNotNull(out);
        assertTrue(out.contains("implementation(\"com.google.guava:guava:33.0.0-jre\")"),
            "must use Kotlin parens form; got:\n" + out);
    }

    @Test
    @DisplayName("updateVersion replaces only the matched dep's version")
    void updateVersion_replacesMatchingVersion() {
        String text = """
            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.10.0'
                testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.0'
            }
            """;
        GradleBuildSupport.UpdateGradleResult r = GradleBuildSupport.updateVersion(
            text, "org.apache.commons", "commons-lang3", "3.14.0");
        assertNotNull(r);
        assertEquals("3.10.0", r.oldVersion());
        assertTrue(r.updatedText().contains("commons-lang3:3.14.0"));
        assertTrue(r.updatedText().contains("junit-jupiter-api:5.10.0"),
            "other deps must be untouched");
    }

    @Test
    @DisplayName("updateVersion returns null when no matching dep")
    void updateVersion_noMatch_returnsNull() {
        String text = """
            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.10.0'
            }
            """;
        GradleBuildSupport.UpdateGradleResult r = GradleBuildSupport.updateVersion(
            text, "no.such", "artifact", "1.0.0");
        assertNull(r);
    }

    @Test
    @DisplayName("hasDependency matches by groupId+artifactId, ignoring version")
    void hasDependency_matchesByGA() {
        List<GradleBuildSupport.DeclaredGradleDep> deps = List.of(
            new GradleBuildSupport.DeclaredGradleDep(
                "implementation", "com.google.guava", "guava", "33.0.0"));
        assertTrue(GradleBuildSupport.hasDependency(deps, "com.google.guava", "guava"));
        assertTrue(!GradleBuildSupport.hasDependency(deps, "com.google.guava", "guice"));
    }

    private static java.nio.file.Path createTempBuildFile(String content, boolean kts) {
        try {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile(
                kts ? "build" : "build", kts ? ".gradle.kts" : ".gradle");
            java.nio.file.Files.writeString(tmp, content);
            return tmp;
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
