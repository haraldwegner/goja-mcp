package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.goja.core.IJdtService;
import org.goja.mcp.refactoring.DiffRenderer;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AbstractTool;
import org.goja.mcp.tools.junit.JUnitLaunchHelper;
import org.goja.mcp.tools.shared.FrameworkDetection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 13 (v1.7.0) — Ring 2 codegen: {@code generate_test_skeleton}.
 *
 * <p>Creates a JUnit test class adjacent to the target class in the
 * {@code src/test/java} mirror of the source package. Stubs out one
 * {@code @Test} method per public method on the target plus a
 * {@code @BeforeEach setUp()}. Bodies contain a {@code // TODO: implement}
 * placeholder; the agent fills them.</p>
 *
 * <p>Framework selection follows the {@code run_tests} rules
 * ({@link FrameworkDetection#detect}): explicit {@code framework} arg wins;
 * otherwise auto-detect from the project classpath.</p>
 */
public class GenerateTestSkeletonTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GenerateTestSkeletonTool.class);

    private final org.goja.mcp.refactoring.RefactoringChangeCache changeCache;

    public GenerateTestSkeletonTool(Supplier<IJdtService> serviceSupplier,
                                   RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "generate_test_skeleton";
    }

    @Override
    public String getDescription() {
        return """
            Create a JUnit test class skeleton for the target class. Walks the
            target's public methods and emits a stub @Test per method, plus
            a @BeforeEach setUp(). Bodies contain a "TODO: implement" line —
            the agent fills them.

            USAGE:
              generate_test_skeleton(filePath="...", line=10, column=14)
              generate_test_skeleton(filePath="...", line=10, column=14,
                                     framework="junit4")

            Inputs:
            - filePath / line / column — caret on or inside the target class.
            - framework — "auto" (default) | "junit5" | "junit4" | "testng".
            - includePrivateMethods — default false.

            Result:
              { operation, generatedFilePath, framework, methodsStubbed[],
                generatedSource, modifiedFiles }
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string"));
        properties.put("line", Map.of("type", "integer"));
        properties.put("column", Map.of("type", "integer"));
        properties.put("framework", Map.of(
            "type", "string",
            "enum", List.of("auto", "junit5", "junit4", "testng")));
        properties.put("includePrivateMethods", Map.of("type", "boolean"));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse missing = requireParam(arguments, "filePath");
        if (missing != null) return missing;

        String filePathRaw = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", 0);
        int column = getIntParam(arguments, "column", 0);
        String framework = getStringParam(arguments, "framework", "auto");
        boolean includePrivate = getBooleanParam(arguments, "includePrivateMethods", false);

        try {
            Path filePath = Path.of(filePathRaw);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            IType type = walkUpToType(element);
            if (type == null) {
                return ToolResponse.invalidParameter("filePath/line/column",
                    "Caret does not resolve to a class.");
            }
            IJavaProject javaProject = type.getJavaProject();
            if (javaProject == null) {
                return ToolResponse.invalidParameter("filePath",
                    "Target type is not in a Java project.");
            }

            JUnitLaunchHelper.TestRunnerKind runner = FrameworkDetection.detect(framework, javaProject);
            if (runner == null) {
                return ToolResponse.invalidParameter("framework",
                    "Could not detect a JUnit framework on the classpath. "
                        + "Set framework explicitly (junit5/junit4/testng) "
                        + "or add a test dependency to the project.");
            }
            String resolvedFramework = switch (runner) {
                case JUNIT3 -> "junit3";
                case JUNIT4 -> "junit4";
                case JUNIT5 -> "junit5";
            };

            // Collect target methods.
            List<String> methodNames = new ArrayList<>();
            for (IMethod m : type.getMethods()) {
                if (m.isConstructor()) continue;
                int flags = m.getFlags();
                if (Flags.isStatic(flags)) continue;
                if (!includePrivate && !Flags.isPublic(flags)) continue;
                methodNames.add(m.getElementName());
            }

            String typeName = type.getElementName();
            String testTypeName = typeName + "Test";
            String packageName = type.getPackageFragment().getElementName();

            Path sourceFile = filePath;
            Path testFile = computeTestFilePath(sourceFile, packageName, testTypeName);
            if (testFile == null) {
                return ToolResponse.invalidParameter("filePath",
                    "Could not derive test source path. Expected source under "
                        + "src/main/java/<package>/.");
            }
            if (Files.exists(testFile)) {
                return ToolResponse.invalidParameter("filePath",
                    "Test file already exists at: " + testFile + ". "
                        + "Refusing to overwrite.");
            }

            String generatedSource = renderSkeleton(packageName, testTypeName, typeName,
                methodNames, resolvedFramework);

            String newFileDiff = DiffRenderer.unifiedDiff(
                service.getPathUtils().formatPath(testFile), "", generatedSource);

            boolean autoApply = getBooleanParam(arguments, "auto_apply", true);
            if (!autoApply) {
                // Preview-only: file creation can't be staged as a cached
                // Change (the target folder may not exist in the workspace
                // tree yet) — return the rendered content for inspection.
                Map<String, Object> stagedData = new LinkedHashMap<>();
                stagedData.put("operation", "generate_test_skeleton");
                stagedData.put("applied", false);
                stagedData.put("generatedFilePath", service.getPathUtils().formatPath(testFile));
                stagedData.put("diff", newFileDiff);
                stagedData.put("generatedSource", generatedSource);
                stagedData.put("note", "Re-run with auto_apply: true (default) to create the file.");
                return ToolResponse.success(stagedData, ResponseMeta.builder().build());
            }

            Files.createDirectories(testFile.getParent());
            Files.writeString(testFile, generatedSource, StandardCharsets.UTF_8);

            // Refresh the project so JDT picks up the new file.
            try {
                javaProject.getProject().refreshLocal(
                    org.eclipse.core.resources.IResource.DEPTH_INFINITE,
                    new org.eclipse.core.runtime.NullProgressMonitor());
            } catch (Exception ignore) {}

            // Undo handle: delete the created file (resolvable only after the
            // refresh registered it in the workspace tree).
            String undoChangeId = null;
            org.eclipse.core.resources.IFile createdFile =
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()
                    .getFileForLocation(org.eclipse.core.runtime.Path.fromOSString(testFile.toString()));
            if (createdFile != null && createdFile.exists()) {
                undoChangeId = changeCache.put(
                    RefactoringChangeCache.Kind.UNDO,
                    new org.eclipse.ltk.core.refactoring.resource.DeleteResourceChange(
                        createdFile.getFullPath(), true),
                    "undo: generate_test_skeleton " + testTypeName,
                    "", List.of(service.getPathUtils().formatPath(testFile)));
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "generate_test_skeleton");
            data.put("framework", resolvedFramework);
            data.put("generatedFilePath", service.getPathUtils().formatPath(testFile));
            data.put("methodsStubbed", methodNames);
            data.put("generatedSource", generatedSource);

            data.put("applied", true);
            data.put("filesModified", List.of(data.get("generatedFilePath")));
            data.put("diff", newFileDiff);
            data.put("undoChangeId", undoChangeId);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(methodNames.size())
                .returnedCount(methodNames.size())
                .build());
        } catch (Exception e) {
            log.warn("generate_test_skeleton failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private static Path computeTestFilePath(Path sourceFile, String packageName, String testTypeName) {
        String s = sourceFile.toString().replace('\\', '/');
        int idx = s.lastIndexOf("/src/main/java/");
        if (idx < 0) return null;
        String projectRoot = s.substring(0, idx);
        String packagePath = packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/";
        return Path.of(projectRoot + "/src/test/java/" + packagePath + testTypeName + ".java");
    }

    private static String renderSkeleton(String packageName, String testTypeName,
                                         String targetTypeName, List<String> methods,
                                         String framework) {
        StringBuilder sb = new StringBuilder();
        if (packageName != null && !packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }
        if ("junit5".equals(framework)) {
            sb.append("import org.junit.jupiter.api.BeforeEach;\n");
            sb.append("import org.junit.jupiter.api.Test;\n\n");
            sb.append("import static org.junit.jupiter.api.Assertions.*;\n\n");
        } else if ("junit4".equals(framework) || "junit3".equals(framework)) {
            sb.append("import org.junit.Before;\n");
            sb.append("import org.junit.Test;\n\n");
            sb.append("import static org.junit.Assert.*;\n\n");
        } else { // testng
            sb.append("import org.testng.annotations.BeforeMethod;\n");
            sb.append("import org.testng.annotations.Test;\n\n");
            sb.append("import static org.testng.Assert.*;\n\n");
        }
        sb.append("class ").append(testTypeName).append(" {\n\n");
        sb.append("    private ").append(targetTypeName).append(" subject;\n\n");

        // setUp
        if ("junit5".equals(framework)) {
            sb.append("    @BeforeEach\n");
        } else if ("junit4".equals(framework) || "junit3".equals(framework)) {
            sb.append("    @Before\n");
        } else {
            sb.append("    @BeforeMethod\n");
        }
        sb.append("    void setUp() {\n");
        sb.append("        // TODO: instantiate subject\n");
        sb.append("    }\n");

        // @Test stubs
        for (String name : methods) {
            sb.append("\n");
            sb.append("    @Test\n");
            sb.append("    void ").append(name).append("() {\n");
            sb.append("        // TODO: implement\n");
            sb.append("        fail(\"not yet implemented\");\n");
            sb.append("    }\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static IType walkUpToType(IJavaElement element) {
        IJavaElement cursor = element;
        while (cursor != null) {
            if (cursor instanceof IType t) return t;
            cursor = cursor.getParent();
        }
        return null;
    }
}
