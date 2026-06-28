package org.goja.mcp.tools.build;

import org.goja.core.LoadedProject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 14 Phase C (v1.8.0) — text-level Gradle build-file support for
 * the Ring 3 dependency-management tools. Parallel to
 * {@link MavenPomSupport} for {@code pom.xml}, but operating on
 * {@code build.gradle} (Groovy DSL) or {@code build.gradle.kts}
 * (Kotlin DSL).
 *
 * <h2>Scope (v1.8.0)</h2>
 *
 * <p>Text-level read/write only. We do NOT invoke the Buildship API to
 * synchronize the resolved classpath after a mutation — the Eclipse
 * Buildship target-platform integration is deferred to v1.8.x.x; agents
 * needing post-edit classpath refresh should call
 * {@code refresh_workspace} on the project.</p>
 *
 * <h2>Supported configurations</h2>
 *
 * <p>{@code implementation}, {@code api}, {@code compileOnly},
 * {@code runtimeOnly}, {@code testImplementation},
 * {@code testCompileOnly}, {@code testRuntimeOnly},
 * {@code annotationProcessor}, {@code kapt}.</p>
 *
 * <h2>Supported declaration forms</h2>
 *
 * <ul>
 *   <li>Groovy single-quoted: {@code implementation 'group:artifact:version'}</li>
 *   <li>Groovy double-quoted: {@code implementation "group:artifact:version"}</li>
 *   <li>Kotlin parens: {@code implementation("group:artifact:version")}</li>
 * </ul>
 */
public final class GradleBuildSupport {

    /**
     * Configurations recognised by the parser / writer. Order matters for
     * regex alternation precedence (testXxx must come before xxx in the
     * regex so the "test" prefix matches first; we sort by length-desc
     * below where it matters).
     */
    static final List<String> CONFIGURATIONS = List.of(
        "testImplementation", "testCompileOnly", "testRuntimeOnly",
        "implementation", "api", "compileOnly", "runtimeOnly",
        "annotationProcessor", "kapt");

    private static final Pattern DEP_LINE = Pattern.compile(
        // (config)( ( | ' | " )group:artifact:version( ' | " )( ) )?
        "(?m)^(\\s*)(" + String.join("|", CONFIGURATIONS) + ")\\s*\\(?\\s*"
            + "(['\"])([^:'\"\\s]+):([^:'\"\\s]+):([^:'\"\\s]+)\\3\\s*\\)?"
            + "(?:\\s*//[^\\n]*)?\\s*$");

    private GradleBuildSupport() {}

    /**
     * @return the project's {@code build.gradle} or {@code build.gradle.kts}
     *         file, whichever exists at the project root; {@code null} if
     *         neither is present.
     */
    public static Path locateBuildFile(LoadedProject project) {
        Path root = project.projectRoot();
        Path groovy = root.resolve("build.gradle");
        if (Files.isRegularFile(groovy)) return groovy;
        Path kotlin = root.resolve("build.gradle.kts");
        if (Files.isRegularFile(kotlin)) return kotlin;
        return null;
    }

    /** True iff the build file is Kotlin DSL ({@code .kts} suffix). */
    public static boolean isKotlinDsl(Path buildFile) {
        return buildFile.getFileName().toString().endsWith(".kts");
    }

    /**
     * Parse declared dependencies. Returns one entry per match against the
     * canonical {@code config 'g:a:v'} or {@code config("g:a:v")} forms;
     * non-matching lines (BOM dependencies, version catalog refs,
     * dependency-platform calls, etc.) are silently skipped.
     */
    public static List<DeclaredGradleDep> readDependencies(Path buildFile) throws IOException {
        String text = Files.readString(buildFile, StandardCharsets.UTF_8);
        List<DeclaredGradleDep> out = new ArrayList<>();
        Matcher m = DEP_LINE.matcher(text);
        while (m.find()) {
            out.add(new DeclaredGradleDep(
                m.group(2),  // configuration
                m.group(4),  // groupId
                m.group(5),  // artifactId
                m.group(6))); // version
        }
        return out;
    }

    public static boolean hasDependency(List<DeclaredGradleDep> declared,
                                         String groupId, String artifactId) {
        for (DeclaredGradleDep d : declared) {
            if (d.groupId().equals(groupId) && d.artifactId().equals(artifactId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Insert a new dependency at the end of the {@code dependencies { ... }}
     * block (preserving indentation and surrounding formatting). When no
     * such block exists, append one at the file's end.
     *
     * @return the new file content, or {@code null} when the existing
     *         {@code dependencies { ... }} block cannot be parsed
     *         unambiguously.
     */
    public static String insertDependency(String buildText, String groupId,
                                           String artifactId, String version,
                                           String configuration, boolean kotlinDsl) {
        String dep = formatDependencyLine(configuration, groupId, artifactId, version, kotlinDsl);

        int depsStart = findDependenciesBlockOpenBrace(buildText);
        if (depsStart < 0) {
            // No dependencies block — append a fresh one to the end.
            String block = "\n\ndependencies {\n    " + dep + "\n}\n";
            return buildText.stripTrailing() + block;
        }

        // Find the matching close brace.
        int closeIdx = findMatchingCloseBrace(buildText, depsStart);
        if (closeIdx < 0) return null;

        // Insert before the close brace, with the same indentation we infer
        // from existing block contents (or default to 4 spaces).
        String indent = inferIndent(buildText, depsStart, closeIdx);
        String before = buildText.substring(0, closeIdx);
        String after = buildText.substring(closeIdx);
        String trimmedBefore = stripTrailingBlankLines(before);
        return trimmedBefore + "\n" + indent + dep + "\n" + after;
    }

    /**
     * Replace the version of an existing declared dep matching
     * {@code groupId+artifactId}. Returns {@code null} when no match.
     */
    public static UpdateGradleResult updateVersion(String buildText, String groupId,
                                                    String artifactId, String newVersion) {
        Matcher m = DEP_LINE.matcher(buildText);
        while (m.find()) {
            if (groupId.equals(m.group(4)) && artifactId.equals(m.group(5))) {
                String oldVersion = m.group(6);
                String quote = m.group(3);
                String lineStart = buildText.substring(0, m.start());
                String oldLine = m.group();
                String newLine = oldLine.replace(
                    quote + groupId + ":" + artifactId + ":" + oldVersion + quote,
                    quote + groupId + ":" + artifactId + ":" + newVersion + quote);
                String tail = buildText.substring(m.end());
                return new UpdateGradleResult(lineStart + newLine + tail, oldVersion);
            }
        }
        return null;
    }

    private static String formatDependencyLine(String configuration, String groupId,
                                                String artifactId, String version,
                                                boolean kotlinDsl) {
        if (kotlinDsl) {
            return configuration + "(\"" + groupId + ":" + artifactId + ":" + version + "\")";
        }
        return configuration + " '" + groupId + ":" + artifactId + ":" + version + "'";
    }

    private static int findDependenciesBlockOpenBrace(String text) {
        Pattern p = Pattern.compile("(?m)^\\s*dependencies\\s*\\{");
        Matcher m = p.matcher(text);
        if (!m.find()) return -1;
        return m.end() - 1;  // index of the '{'
    }

    private static int findMatchingCloseBrace(String text, int openBraceIdx) {
        int depth = 0;
        for (int i = openBraceIdx; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String inferIndent(String text, int blockOpen, int blockClose) {
        // Scan the block for an existing line's leading whitespace.
        int lineStart = -1;
        for (int i = blockOpen + 1; i < blockClose; i++) {
            char c = text.charAt(i);
            if (c == '\n') { lineStart = i + 1; continue; }
            if (lineStart >= 0 && !Character.isWhitespace(c)) {
                return text.substring(lineStart, i);
            }
        }
        return "    ";
    }

    private static String stripTrailingBlankLines(String s) {
        int end = s.length();
        while (end > 0) {
            char c = s.charAt(end - 1);
            if (c == '\n' || c == ' ' || c == '\t' || c == '\r') {
                end--;
            } else break;
        }
        return s.substring(0, end);
    }

    public record DeclaredGradleDep(String configuration, String groupId,
                                     String artifactId, String version) {}

    public record UpdateGradleResult(String updatedText, String oldVersion) {}
}
