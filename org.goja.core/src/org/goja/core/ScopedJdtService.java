package org.goja.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.goja.core.search.SearchService;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.CoreException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Wraps an {@link IJdtService} and re-points its single-project getters at a
 * specified {@link LoadedProject}, so tools that pass through a tool-side
 * {@code projectKey} parameter operate against just that project's classpath.
 *
 * <p>Sprint 10 introduces multi-project workspaces. By default tools see the
 * full workspace (cross-project search, cross-project file lookup). When an
 * agent wants to scope a query down to one project, it passes
 * {@code projectKey} on the tool call; {@link org.goja.mcp.tools.AbstractTool}
 * resolves the project and wraps the service in this adapter before invoking
 * the tool body. Tool implementations are unaware of the wrapping — they keep
 * calling {@code service.getSearchService()}, {@code service.getJavaProject()},
 * {@code service.getCompilationUnit(filePath)} etc., and those calls now
 * return the per-project view.
 *
 * <p>Multi-project lookup methods ({@link #getProject}, {@link #allProjects},
 * {@link #defaultProjectKey}, {@link #projectKeys}) delegate to the underlying
 * service so tools that want to look beyond their scope still can.
 */
public class ScopedJdtService implements IJdtService {

    private final IJdtService delegate;
    private final LoadedProject scope;

    public ScopedJdtService(IJdtService delegate, LoadedProject scope) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        this.delegate = delegate;
        this.scope = scope;
    }

    /** The project this view is scoped to. */
    public LoadedProject scope() {
        return scope;
    }

    // ===== scoped getters: re-point at the scoped project =====

    @Override
    public IPathUtils getPathUtils() {
        return scope.pathUtils();
    }

    @Override
    public Path getProjectRoot() {
        return scope.projectRoot();
    }

    @Override
    public SearchService getSearchService() {
        return scope.searchService();
    }

    @Override
    public IJavaProject getJavaProject() {
        return scope.javaProject();
    }

    @Override
    public ICompilationUnit getCompilationUnit(Path filePath) {
        IJavaProject jp = scope.javaProject();
        if (jp == null) return null;
        try {
            String pathStr = filePath.toString().replace('\\', '/');
            String classPath = pathStr;
            String[] sourcePrefixes = {"src/main/java/", "src/test/java/", "src/main/kotlin/", "src/test/kotlin/", "src/"};
            for (String prefix : sourcePrefixes) {
                if (pathStr.contains(prefix)) {
                    int idx = pathStr.indexOf(prefix);
                    classPath = pathStr.substring(idx + prefix.length());
                    break;
                }
            }
            String withoutExt = classPath.replace(".java", "");
            String qualifiedName = withoutExt.replace('/', '.');
            IType type = jp.findType(qualifiedName);
            if (type != null) {
                return type.getCompilationUnit();
            }
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    int lastSlash = classPath.lastIndexOf('/');
                    String packageName = lastSlash > 0 ? classPath.substring(0, lastSlash).replace('/', '.') : "";
                    String className = lastSlash > 0 ? classPath.substring(lastSlash + 1) : classPath;
                    IPackageFragment pkg = root.getPackageFragment(packageName);
                    if (pkg != null && pkg.exists()) {
                        ICompilationUnit cu = pkg.getCompilationUnit(className);
                        if (cu != null && cu.exists()) {
                            return cu;
                        }
                    }
                }
            }
            return null;
        } catch (JavaModelException e) {
            return null;
        }
    }

    @Override
    public IJavaElement getElementAtPosition(Path filePath, int line, int column) {
        ICompilationUnit cu = getCompilationUnit(filePath);
        if (cu == null) return null;
        try {
            if (!cu.isOpen()) cu.open(null);
            cu.reconcile(ICompilationUnit.NO_AST, false, null, null);
            int offset = delegate.getOffset(cu, line, column);
            IJavaElement[] elements = cu.codeSelect(offset, 0);
            if (elements.length > 0) return elements[0];
            return cu.getElementAt(offset);
        } catch (CoreException e) {
            return null;
        }
    }

    @Override
    public IType getTypeAtPosition(Path filePath, int line, int column) {
        IJavaElement element = getElementAtPosition(filePath, line, column);
        if (element instanceof IType type) return type;
        if (element != null) return (IType) element.getAncestor(IJavaElement.TYPE);
        return null;
    }

    @Override
    public IType findType(String typeName) {
        IJavaProject jp = scope.javaProject();
        if (jp == null || typeName == null || typeName.isBlank()) return null;
        try {
            IType type = jp.findType(typeName);
            if (type != null) return type;
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    for (IJavaElement child : root.getChildren()) {
                        if (child instanceof IPackageFragment pkg) {
                            for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                                for (IType t : cu.getTypes()) {
                                    if (t.getElementName().equals(typeName)) return t;
                                }
                            }
                        }
                    }
                }
            }
            return null;
        } catch (JavaModelException e) {
            return null;
        }
    }

    @Override
    public List<Path> getAllJavaFiles() {
        List<Path> files = new ArrayList<>();
        IJavaProject jp = scope.javaProject();
        if (jp == null) return files;
        try {
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    collectJavaFilesIn(root, files);
                }
            }
        } catch (JavaModelException ignored) {
            // best-effort
        }
        return files;
    }

    private static void collectJavaFilesIn(IPackageFragmentRoot root, List<Path> files) throws JavaModelException {
        for (IJavaElement child : root.getChildren()) {
            if (child instanceof IPackageFragment pkg) {
                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    IResource resource = cu.getResource();
                    if (resource != null) {
                        IPath location = resource.getLocation();
                        if (location != null) {
                            files.add(Path.of(location.toOSString()));
                        }
                    }
                }
            }
        }
    }

    // ===== pure delegations (workspace queries, line/offset math, timeouts) =====

    @Override
    public int getTimeoutSeconds() {
        return delegate.getTimeoutSeconds();
    }

    @Override
    public <T> T executeWithTimeout(Callable<T> operation, String operationName) {
        return delegate.executeWithTimeout(operation, operationName);
    }

    @Override
    public String getContextLine(ICompilationUnit cu, int offset) {
        return delegate.getContextLine(cu, offset);
    }

    @Override
    public int getOffset(ICompilationUnit cu, int line, int column) {
        return delegate.getOffset(cu, line, column);
    }

    @Override
    public int getLineNumber(ICompilationUnit cu, int offset) {
        return delegate.getLineNumber(cu, offset);
    }

    @Override
    public int getColumnNumber(ICompilationUnit cu, int offset) {
        return delegate.getColumnNumber(cu, offset);
    }

    @Override
    public Optional<String> defaultProjectKey() {
        return delegate.defaultProjectKey();
    }

    @Override
    public Optional<LoadedProject> getProject(String projectKey) {
        return delegate.getProject(projectKey);
    }

    @Override
    public Collection<String> projectKeys() {
        return delegate.projectKeys();
    }

    @Override
    public Collection<LoadedProject> allProjects() {
        return delegate.allProjects();
    }

    @Override
    public LoadedProject addProject(Path projectPath) throws CoreException {
        return delegate.addProject(projectPath);
    }

    @Override
    public boolean removeProject(String projectKey) {
        return delegate.removeProject(projectKey);
    }

    @Override
    public Optional<Long> wasRecentlyDropped(String projectKey) {
        return delegate.wasRecentlyDropped(projectKey);
    }
}
