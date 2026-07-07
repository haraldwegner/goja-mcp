package org.goja.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.goja.core.search.SearchService;

import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Service interface for JDT operations.
 * Provides semantic code analysis capabilities through Eclipse JDT.
 *
 * <p>This interface defines the contract for JDT-based analysis.
 * Implementations wrap the Eclipse JDT SearchEngine and related APIs.
 */
public interface IJdtService {

    /**
     * Get path utilities for the loaded project.
     *
     * @return Path utilities instance
     */
    IPathUtils getPathUtils();

    /**
     * Get the project root path.
     *
     * @return Absolute path to the project root
     */
    Path getProjectRoot();

    /**
     * Get the configured timeout in seconds.
     *
     * @return Timeout value (default 30, range 5-300)
     */
    int getTimeoutSeconds();

    /**
     * Execute an operation with timeout protection.
     * Prevents long-running operations from blocking the server.
     *
     * @param operation The operation to execute
     * @param operationName Name for error messages
     * @param <T> Return type
     * @return The operation result
     * @throws RuntimeException if timeout, interrupted, or operation fails
     */
    <T> T executeWithTimeout(Callable<T> operation, String operationName);

    /**
     * Get the search service for indexed queries.
     *
     * @return SearchService instance
     */
    SearchService getSearchService();

    /**
     * Get the underlying Java project.
     *
     * @return IJavaProject instance
     */
    IJavaProject getJavaProject();

    /**
     * Get the ICompilationUnit for a file path.
     *
     * @param filePath Path to the source file (absolute or relative to project root)
     * @return ICompilationUnit or null if not found
     */
    ICompilationUnit getCompilationUnit(Path filePath);

    /**
     * Get the IJavaElement at a specific position in a file.
     * Uses zero-based line and column numbers.
     *
     * @param filePath Path to the source file
     * @param line Zero-based line number
     * @param column Zero-based column number
     * @return IJavaElement at position, or null if not found
     */
    IJavaElement getElementAtPosition(Path filePath, int line, int column);

    /**
     * Get the IType at a specific position in a file.
     *
     * @param filePath Path to the source file
     * @param line Zero-based line number
     * @param column Zero-based column number
     * @return IType at position, or null if not a type
     */
    IType getTypeAtPosition(Path filePath, int line, int column);

    /**
     * Find a type by its fully qualified or simple name.
     *
     * @param typeName Type name (e.g., "String" or "java.lang.String")
     * @return IType or null if not found
     */
    IType findType(String typeName);

    /**
     * Sprint 21e (item A): resolve a type name (simple or qualified) to the UNIQUE
     * project-SOURCE type it denotes, or {@code null}. This is the resolution gate that
     * makes automatic symbol anchoring safe: dead/renamed names don't resolve, binary
     * (JDK/library) types are excluded, and an ambiguous simple name (2+ source types,
     * nested types included) is refused — absence beats a wrong pointer.
     *
     * @param typeName simple ("SlotManager") or qualified ("pipeline.SlotManager") name
     * @return the unique source {@link IType}, or {@code null} (unresolvable / binary /
     *         ambiguous / no search scope yet)
     */
    default IType resolveUniqueSourceType(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return null;
        }
        try {
            if (typeName.indexOf('.') >= 0) {
                IType t = findType(typeName);
                return t != null && t.exists() && t.getCompilationUnit() != null ? t : null;
            }
            SearchService search = getSearchService();
            if (search == null) {
                return null;
            }
            // Exact-name TYPE declarations over the current all-projects scope; keep only
            // SOURCE hits, dedup by FQN (the same type loaded twice is not ambiguity).
            Map<String, IType> sourceByFqn = new LinkedHashMap<>();
            for (SearchMatch m : search.searchSymbols(typeName, IJavaSearchConstants.TYPE, 50)) {
                if (m.getElement() instanceof IType t
                        && typeName.equals(t.getElementName())
                        && t.getCompilationUnit() != null) {
                    sourceByFqn.putIfAbsent(t.getFullyQualifiedName('.'), t);
                }
            }
            return sourceByFqn.size() == 1 ? sourceByFqn.values().iterator().next() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get source line content at a specific position.
     *
     * @param cu Compilation unit
     * @param offset Character offset
     * @return The source line content
     */
    String getContextLine(ICompilationUnit cu, int offset);

    /**
     * Convert zero-based line/column to character offset.
     *
     * @param cu Compilation unit
     * @param line Zero-based line number
     * @param column Zero-based column number
     * @return Character offset in the source
     */
    int getOffset(ICompilationUnit cu, int line, int column);

    /**
     * Convert character offset to zero-based line number.
     *
     * @param cu Compilation unit
     * @param offset Character offset
     * @return Zero-based line number
     */
    int getLineNumber(ICompilationUnit cu, int offset);

    /**
     * Convert character offset to zero-based column number.
     *
     * @param cu Compilation unit
     * @param offset Character offset
     * @return Zero-based column number
     */
    int getColumnNumber(ICompilationUnit cu, int offset);

    /**
     * Get all Java source files in the project.
     *
     * @return List of source file paths
     */
    List<Path> getAllJavaFiles();

    // ============================================================
    // Multi-project workspace API (Sprint 10).
    //
    // Sprint 10 introduces multi-project workspaces: a single goja
    // process holds N projects loaded simultaneously, all sharing one
    // Eclipse workspace. Tools that pre-date Sprint 10 keep working via
    // the existing single-project methods above; those methods route to
    // the "default project", which is the most recently loaded project
    // (set by load_project) or the first added project if load_project
    // has not been called.
    //
    // Default methods provide a safe single-project fallback so
    // alternative IJdtService implementations don't break.
    // ============================================================

    /** Project key of the default project (the one returned by single-project getters), if any. */
    default Optional<String> defaultProjectKey() {
        return Optional.empty();
    }

    /** Look up a loaded project by its project key. */
    default Optional<LoadedProject> getProject(String projectKey) {
        return Optional.empty();
    }

    /**
     * Sprint 14 (bugs.md #11): if a caller holds a {@code projectKey} that
     * USED to be valid but has been dropped (e.g. via
     * {@link #removeProject(String)} or a manager-side workspace mutation),
     * this returns the drop timestamp (milliseconds since the epoch) for up
     * to a TTL window after the drop. After the TTL window the entry is
     * evicted and this returns {@link Optional#empty()} — distinguishing
     * "dropped recently" from "never existed" so the caller can surface
     * {@code PROJECT_KEY_DROPPED} instead of a generic {@code INVALID_PARAMETER}.
     */
    default Optional<Long> wasRecentlyDropped(String projectKey) {
        return Optional.empty();
    }

    /** Keys of all currently loaded projects. */
    default Collection<String> projectKeys() {
        return List.of();
    }

    /** All currently loaded projects. */
    default Collection<LoadedProject> allProjects() {
        return List.of();
    }

    /**
     * Append a project to the workspace without clearing existing projects.
     * The default project key is unchanged unless this is the first project
     * registered, in which case the new project becomes the default.
     */
    default LoadedProject addProject(Path projectPath) throws org.eclipse.core.runtime.CoreException {
        throw new UnsupportedOperationException(
            "addProject is not supported by this IJdtService implementation");
    }

    /**
     * Remove a project from the workspace. If the removed project was the
     * default, the next available project (if any) becomes the new default.
     *
     * @return true if a project with the given key existed and was removed
     */
    default boolean removeProject(String projectKey) {
        return false;
    }
}
