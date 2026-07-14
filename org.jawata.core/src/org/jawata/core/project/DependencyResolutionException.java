package org.jawata.core.project;

/**
 * The build system's dependency resolution FAILED while a project was being
 * loaded — so the project's classpath would be missing its declared
 * dependencies, and every subsequent answer computed on it (compile
 * diagnostics, type lookups, searches, smell scans) would be wrong in a way
 * the caller cannot see.
 *
 * <p>The pre-fix behaviour returned an EMPTY dependency list on every failure
 * path (no executable, timeout, non-zero exit, exception) and logged at warn —
 * a NOP in the test harness. The project then loaded "successfully" with 3
 * classpath entries instead of dozens, and the first visible symptom was a
 * tool answering {@code Type not found: org.junit.jupiter.api.Test} about a
 * type that sat in the local repository the whole time. A project without its
 * declared dependencies must not load as if it were healthy.</p>
 */
public class DependencyResolutionException extends RuntimeException {

    public DependencyResolutionException(String message) {
        super(message);
    }

    public DependencyResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
