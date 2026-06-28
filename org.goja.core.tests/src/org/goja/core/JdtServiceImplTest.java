package org.goja.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.goja.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for JdtServiceImpl.
 * Uses real JDT APIs with sample project fixtures.
 */
class JdtServiceImplTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        projectPath = helper.getFixturePath("simple-maven");
    }

    // ========== Project Loading Tests ==========

    @Test
    @DisplayName("loadProject should initialize Java project correctly")
    void loadProject_initializesJavaProject() {
        IJavaProject javaProject = service.getJavaProject();

        assertNotNull(javaProject, "Java project should be created");
        assertTrue(javaProject.exists(), "Java project should exist");
    }

    @Test
    @DisplayName("loadProject should detect source files")
    void loadProject_detectsSourceFiles() {
        int sourceFiles = service.getSourceFileCount();

        assertTrue(sourceFiles >= 3, "Should find at least 3 source files (Calculator, HelloWorld, UserService)");
    }

    @Test
    @DisplayName("loadProject should detect packages")
    void loadProject_detectsPackages() {
        int packages = service.getPackageCount();

        assertTrue(packages >= 1, "Should find at least 1 package");
        assertTrue(service.getPackages().contains("com.example") ||
                   service.getPackages().stream().anyMatch(p -> p.startsWith("com.example")),
                   "Should find com.example package");
    }

    // ========== Compilation Unit Tests ==========

    @Test
    @DisplayName("getCompilationUnit should find existing Java files")
    void getCompilationUnit_findsExistingFiles() {
        Path calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java");

        ICompilationUnit cu = service.getCompilationUnit(calculatorPath);

        assertNotNull(cu, "Should find Calculator.java");
        assertTrue(cu.exists(), "Compilation unit should exist");
        assertEquals("Calculator.java", cu.getElementName());
    }

    @Test
    @DisplayName("getCompilationUnit should return null for non-existent files")
    void getCompilationUnit_returnsNullForMissing() {
        Path missingPath = projectPath.resolve("src/main/java/com/example/NotExists.java");

        ICompilationUnit cu = service.getCompilationUnit(missingPath);

        assertNull(cu, "Should return null for missing files");
    }

    @Test
    @DisplayName("getCompilationUnit should find files in subpackages")
    void getCompilationUnit_findsFilesInSubpackages() {
        Path servicePath = projectPath.resolve("src/main/java/com/example/service/UserService.java");

        ICompilationUnit cu = service.getCompilationUnit(servicePath);

        assertNotNull(cu, "Should find UserService.java");
        assertEquals("UserService.java", cu.getElementName());
    }

    // ========== Element at Position Tests ==========

    @Test
    @DisplayName("getElementAtPosition should find class declaration")
    void getElementAtPosition_findsClass() {
        Path calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java");

        // Position at 'Calculator' class name (line 5, column 13 - 0-based)
        // public class Calculator {
        //              ^
        IJavaElement element = service.getElementAtPosition(calculatorPath, 5, 13);

        assertNotNull(element, "Should find element at class declaration");
        assertTrue(element instanceof IType, "Should be a type: " + element.getClass().getName());
        assertEquals("Calculator", element.getElementName());
    }

    @Test
    @DisplayName("getElementAtPosition should find method declaration")
    void getElementAtPosition_findsMethod() {
        Path calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java");

        // Position at 'add' method name (line 13, column 15 - 0-based)
        // public int add(int a, int b) {
        //            ^
        IJavaElement element = service.getElementAtPosition(calculatorPath, 13, 15);

        assertNotNull(element, "Should find element at method declaration");
        // Element could be method or parameter depending on exact position
        String name = element.getElementName();
        assertTrue(name.equals("add") || name.equals("a"),
            "Should find add method or its parameter, got: " + name);
    }

    // ========== Offset Conversion Tests ==========

    @Test
    @DisplayName("getOffset should convert line/column to offset correctly")
    void getOffset_convertsCorrectly() throws Exception {
        Path calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java");
        ICompilationUnit cu = service.getCompilationUnit(calculatorPath);

        assertNotNull(cu, "Should find Calculator.java");

        // Line 0, Column 0 should be offset 0
        assertEquals(0, service.getOffset(cu, 0, 0), "First position should be offset 0");

        // Get source to verify
        String source = cu.getSource();
        assertNotNull(source, "Source should not be null");

        // Line 1 offset should be after first line
        int firstLineLength = source.indexOf('\n') + 1;
        assertEquals(firstLineLength, service.getOffset(cu, 1, 0),
            "Line 1 offset should equal first line length");
    }

    @Test
    @DisplayName("getLineNumber should convert offset to line correctly")
    void getLineNumber_convertsCorrectly() throws Exception {
        Path calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java");
        ICompilationUnit cu = service.getCompilationUnit(calculatorPath);

        assertNotNull(cu, "Should find Calculator.java");

        // Offset 0 should be line 0
        assertEquals(0, service.getLineNumber(cu, 0), "Offset 0 should be line 0");

        // Get source to find newline
        String source = cu.getSource();
        int firstNewline = source.indexOf('\n');

        // Offset right after first newline should be line 1
        assertEquals(1, service.getLineNumber(cu, firstNewline + 1),
            "After first newline should be line 1");
    }

    // ========== Type Resolution Tests ==========

    @Test
    @DisplayName("findType should find type by fully qualified name")
    void findType_findsByQualifiedName() {
        IType type = service.findType("com.example.Calculator");

        assertNotNull(type, "Should find Calculator by FQN");
        assertEquals("Calculator", type.getElementName());
    }

    @Test
    @DisplayName("findType should find type by simple name")
    void findType_findsBySimpleName() {
        IType type = service.findType("Calculator");

        assertNotNull(type, "Should find Calculator by simple name");
        assertEquals("Calculator", type.getElementName());
    }

    @Test
    @DisplayName("findType should return null for non-existent type")
    void findType_returnsNullForMissing() {
        IType type = service.findType("com.example.NotExists");

        assertNull(type, "Should return null for non-existent type");
    }

    // ========== All Java Files Tests ==========

    @Test
    @DisplayName("getAllJavaFiles should return all source files")
    void getAllJavaFiles_returnsAllFiles() {
        var files = service.getAllJavaFiles();

        assertFalse(files.isEmpty(), "Should find Java files");
        assertTrue(files.size() >= 3, "Should find at least 3 files");

        // Verify file names
        boolean hasCalculator = files.stream().anyMatch(p -> p.toString().contains("Calculator.java"));
        boolean hasHelloWorld = files.stream().anyMatch(p -> p.toString().contains("HelloWorld.java"));
        boolean hasUserService = files.stream().anyMatch(p -> p.toString().contains("UserService.java"));

        assertTrue(hasCalculator, "Should include Calculator.java");
        assertTrue(hasHelloWorld, "Should include HelloWorld.java");
        assertTrue(hasUserService, "Should include UserService.java");
    }

    // ========== PathUtils Tests ==========

    @Test
    @DisplayName("getPathUtils should return configured PathUtils")
    void getPathUtils_returnsConfigured() {
        IPathUtils pathUtils = service.getPathUtils();

        assertNotNull(pathUtils, "PathUtils should be configured");
    }

    @Test
    @DisplayName("getProjectRoot should return project path")
    void getProjectRoot_returnsPath() {
        Path root = service.getProjectRoot();

        assertNotNull(root, "Project root should be set");
        assertTrue(root.toString().contains("simple-maven"),
            "Should contain project name");
    }

    // ========== Sprint 10: multi-project workspace ==========

    @Test
    @DisplayName("loadProject sets defaultProjectKey and registers single entry")
    void loadProject_setsDefaultAndRegisters() {
        // helper.loadProject already called loadProject in @BeforeEach.
        assertTrue(service.defaultProjectKey().isPresent(),
            "load_project should set the default key");
        assertEquals(1, service.allProjects().size(),
            "Single load_project => one entry in the project map");
        String key = service.defaultProjectKey().get();
        assertTrue(service.getProject(key).isPresent(),
            "Default key should be addressable in getProject(key)");
    }

    @Test
    @DisplayName("loadProject second time clears the previous project (clear-and-load semantics)")
    void loadProject_clearsPrevious() throws Exception {
        Path pathB = helper.getFixturePath("simple-maven-b");
        service.loadProject(pathB);

        assertEquals(1, service.allProjects().size(),
            "load_project replaces, never appends");
        String key = service.defaultProjectKey().orElseThrow();
        assertTrue(service.getProject(key).orElseThrow()
                .projectRoot().toString().contains("simple-maven-b"),
            "Default project should be the second load");
    }

    @Test
    @DisplayName("addProject appends without clearing or changing the default key")
    void addProject_appendsWithoutClearing() throws Exception {
        String originalDefault = service.defaultProjectKey().orElseThrow();

        Path pathB = helper.getFixturePath("simple-maven-b");
        LoadedProject loaded = service.addProject(pathB);

        assertEquals(2, service.allProjects().size(),
            "addProject should append without clearing");
        assertEquals(originalDefault, service.defaultProjectKey().orElseThrow(),
            "addProject must not change the default key");
        assertTrue(service.getProject(loaded.projectKey()).isPresent(),
            "Newly added project should be addressable by its key");
        assertNotEquals(originalDefault, loaded.projectKey(),
            "New project should have a distinct key");
    }

    @Test
    @DisplayName("removeProject removes the entry and reassigns default if needed")
    void removeProject_reassignsDefault() throws Exception {
        String firstKey = service.defaultProjectKey().orElseThrow();
        Path pathB = helper.getFixturePath("simple-maven-b");
        LoadedProject second = service.addProject(pathB);

        // Remove the default — the remaining project becomes the new default.
        boolean removed = service.removeProject(firstKey);
        assertTrue(removed, "Default project should remove successfully");
        assertEquals(1, service.allProjects().size());
        assertEquals(second.projectKey(), service.defaultProjectKey().orElseThrow(),
            "Removing the default should promote the next available key");
    }

    @Test
    @DisplayName("removeProject returns false for unknown key and does not change state")
    void removeProject_unknownKeyIsNoOp() {
        int before = service.allProjects().size();
        boolean removed = service.removeProject("not-a-real-key");
        assertFalse(removed, "Unknown key should return false");
        assertEquals(before, service.allProjects().size(),
            "Unknown key should not perturb the project map");
    }

    @Test
    @DisplayName("getSearchService returns a workspace-scoped service spanning all loaded projects")
    void getSearchService_spansAllLoadedProjects() throws Exception {
        // simple-maven (loaded in @BeforeEach) has classes Calculator,
        // HelloWorld, UserService. simple-maven-b adds HelloB.
        Path pathB = helper.getFixturePath("simple-maven-b");
        service.addProject(pathB);

        org.goja.core.search.SearchService search = service.getSearchService();
        assertNotNull(search, "Workspace search service should be available with 2 projects loaded");

        // Pattern match for both projects' classes simultaneously.
        java.util.List<org.eclipse.jdt.core.search.SearchMatch> hellos =
            search.searchSymbols("Hello*", null, 100);

        boolean foundA = hellos.stream().anyMatch(m ->
            m.getElement() instanceof org.eclipse.jdt.core.IType t
                && "HelloWorld".equals(t.getElementName()));
        boolean foundB = hellos.stream().anyMatch(m ->
            m.getElement() instanceof org.eclipse.jdt.core.IType t
                && "HelloB".equals(t.getElementName()));

        assertTrue(foundA, "Workspace search should still find HelloWorld in simple-maven");
        assertTrue(foundB, "Workspace search should also find HelloB in simple-maven-b — cross-project");
    }

    @Test
    @DisplayName("workspace SearchService is rebuilt after removeProject")
    void getSearchService_rebuildsAfterRemove() throws Exception {
        Path pathB = helper.getFixturePath("simple-maven-b");
        LoadedProject second = service.addProject(pathB);
        // Sanity: HelloB is findable while both projects are loaded.
        boolean foundB = service.getSearchService().searchSymbols("HelloB", null, 10)
            .stream().anyMatch(m -> m.getElement() instanceof org.eclipse.jdt.core.IType t
                && "HelloB".equals(t.getElementName()));
        assertTrue(foundB, "HelloB should be findable while simple-maven-b is loaded");

        service.removeProject(second.projectKey());

        // After removal, HelloB should no longer be in the workspace scope.
        boolean stillFoundB = service.getSearchService().searchSymbols("HelloB", null, 10)
            .stream().anyMatch(m -> m.getElement() instanceof org.eclipse.jdt.core.IType t
                && "HelloB".equals(t.getElementName()));
        assertFalse(stillFoundB,
            "After removeProject, the workspace scope must shrink to exclude the removed project");
    }

    // ========== Sprint 10 A.4.2: cross-project lookups + ScopedJdtService ==========

    @Test
    @DisplayName("getCompilationUnit finds files in any loaded project, not just the default")
    void getCompilationUnit_crossProjectLookup() throws Exception {
        // Default = simple-maven. Add simple-maven-b. Look up a file that
        // only exists in simple-maven-b — must succeed without projectKey.
        Path pathB = helper.getFixturePath("simple-maven-b");
        service.addProject(pathB);

        Path helloBPath = pathB.resolve("src/main/java/com/exampleb/HelloB.java");
        ICompilationUnit cu = service.getCompilationUnit(helloBPath);
        assertNotNull(cu, "Default service should find files across all loaded projects");
        assertEquals("HelloB.java", cu.getElementName());
    }

    @Test
    @DisplayName("findType resolves types in any loaded project, not just the default")
    void findType_crossProjectLookup() throws Exception {
        Path pathB = helper.getFixturePath("simple-maven-b");
        service.addProject(pathB);

        IType helloB = service.findType("com.exampleb.HelloB");
        assertNotNull(helloB, "Default service should find types across all loaded projects");
        IType helloWorld = service.findType("com.example.HelloWorld");
        assertNotNull(helloWorld, "Default-project type still resolvable");
    }

    @Test
    @DisplayName("getAllJavaFiles aggregates files from every loaded project")
    void getAllJavaFiles_aggregatesAcrossProjects() throws Exception {
        int beforeAdd = service.getAllJavaFiles().size();
        Path pathB = helper.getFixturePath("simple-maven-b");
        service.addProject(pathB);
        int afterAdd = service.getAllJavaFiles().size();
        assertTrue(afterAdd > beforeAdd,
            "After addProject the file aggregate should grow (got " + beforeAdd + " -> " + afterAdd + ")");
    }

    @Test
    @DisplayName("ScopedJdtService scopes search to a single project")
    void scopedJdtService_searchScopesDownToOneProject() throws Exception {
        Path pathB = helper.getFixturePath("simple-maven-b");
        LoadedProject second = service.addProject(pathB);

        ScopedJdtService scoped = new ScopedJdtService(service, second);
        // Pattern that matches both HelloWorld (in simple-maven) and HelloB
        // (in simple-maven-b). Scoped should only see simple-maven-b.
        java.util.List<org.eclipse.jdt.core.search.SearchMatch> hits =
            scoped.getSearchService().searchSymbols("Hello*", null, 100);

        boolean foundA = hits.stream().anyMatch(m ->
            m.getElement() instanceof IType t && "HelloWorld".equals(t.getElementName()));
        boolean foundB = hits.stream().anyMatch(m ->
            m.getElement() instanceof IType t && "HelloB".equals(t.getElementName()));

        assertFalse(foundA, "Scope-down should NOT see HelloWorld (lives in simple-maven)");
        assertTrue(foundB, "Scope-down should see HelloB (lives in simple-maven-b)");
    }

    @Test
    @DisplayName("ScopedJdtService getCompilationUnit/findType reject out-of-scope files and types")
    void scopedJdtService_rejectsOutOfScopeLookups() throws Exception {
        Path pathB = helper.getFixturePath("simple-maven-b");
        LoadedProject second = service.addProject(pathB);

        ScopedJdtService scopedB = new ScopedJdtService(service, second);

        // In-scope: HelloB resolves.
        Path helloBPath = pathB.resolve("src/main/java/com/exampleb/HelloB.java");
        assertNotNull(scopedB.getCompilationUnit(helloBPath),
            "In-scope file lookup should succeed");
        assertNotNull(scopedB.findType("com.exampleb.HelloB"),
            "In-scope type lookup should succeed");

        // Out-of-scope: simple-maven's HelloWorld must NOT resolve through
        // the scoped view (even though it would resolve through the
        // unscoped service).
        Path helloAPath = projectPath.resolve("src/main/java/com/example/HelloWorld.java");
        assertNull(scopedB.getCompilationUnit(helloAPath),
            "Out-of-scope file lookup should return null");
        assertNull(scopedB.findType("com.example.HelloWorld"),
            "Out-of-scope type lookup should return null");
    }

    @Test
    @DisplayName("ScopedJdtService getJavaProject / getProjectRoot / getPathUtils point at the scoped project")
    void scopedJdtService_returnsScopedProjectMetadata() throws Exception {
        Path pathB = helper.getFixturePath("simple-maven-b");
        LoadedProject second = service.addProject(pathB);

        ScopedJdtService scoped = new ScopedJdtService(service, second);
        assertEquals(second.javaProject(), scoped.getJavaProject());
        assertEquals(second.projectRoot(), scoped.getProjectRoot());
        assertEquals(second.pathUtils(), scoped.getPathUtils());
        // Workspace queries still delegate.
        assertEquals(service.allProjects().size(), scoped.allProjects().size());
        assertEquals(service.defaultProjectKey(), scoped.defaultProjectKey());
    }
}
