package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.goja.core.IJdtService;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 11 Phase E — {@code move_package}: rename / relocate a whole
 * Java package, recursing into all compilation units.
 *
 * <p>JDT models a package "move" as a rename whose new name is a
 * different fully-qualified package — no separate "MovePackage" descriptor
 * exists. We use {@link RenameJavaElementDescriptor} with type
 * {@link IJavaRefactorings#RENAME_PACKAGE}.</p>
 */
public class MovePackageTool extends AbstractRefactoringTool {

    public MovePackageTool(Supplier<IJdtService> serviceSupplier,
                          RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "move_package";
    }

    @Override
    public String getDescription() {
        return """
            Rename / move an entire Java package, recursing into every compilation
            unit underneath. All references in the workspace are updated.

            USAGE: move_package(packageName="com.example.old", newPackageName="com.example.new")

            Inputs:
            - packageName     — fully-qualified current package name.
            - newPackageName  — fully-qualified target package name.
            - updateReferences (default true) — when false, only renames the
                                package; callers' import lines are NOT rewritten.

            On conflict (target package already exists with same-name CUs, etc.)
            the refactoring fails with REFACTORING_FAILED and no files are
            modified.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("packageName", Map.of(
            "type", "string",
            "description", "Fully-qualified current package name (e.g., 'com.example.old')."
        ));
        properties.put("newPackageName", Map.of(
            "type", "string",
            "description", "Fully-qualified target package name (e.g., 'com.example.new')."
        ));
        properties.put("updateReferences", Map.of(
            "type", "boolean",
            "description", "Update import lines and qualified references in callers (default true)."
        ));

        schema.put("properties", properties);
        schema.put("required", List.of("packageName", "newPackageName"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String packageName = getStringParam(arguments, "packageName");
        String newPackageName = getStringParam(arguments, "newPackageName");
        boolean updateReferences = arguments != null && arguments.has("updateReferences")
            ? arguments.get("updateReferences").asBoolean(true)
            : true;

        if (packageName == null || packageName.isBlank()) {
            return ToolResponse.invalidParameter("packageName", "packageName is required");
        }
        if (newPackageName == null || newPackageName.isBlank()) {
            return ToolResponse.invalidParameter("newPackageName", "newPackageName is required");
        }
        if (packageName.equals(newPackageName)) {
            return ToolResponse.invalidParameter("newPackageName",
                "newPackageName must differ from packageName");
        }

        try {
            IJavaProject jp = service.getJavaProject();
            if (jp == null) {
                return ToolResponse.projectNotLoaded();
            }
            IPackageFragment fragment = findPackage(jp, packageName);
            if (fragment == null) {
                return ToolResponse.symbolNotFound("Package not found: " + packageName);
            }

            RenameJavaElementDescriptor descriptor =
                (RenameJavaElementDescriptor) RefactoringCore
                    .getRefactoringContribution(IJavaRefactorings.RENAME_PACKAGE)
                    .createDescriptor();
            descriptor.setProject(jp.getProject().getName());
            descriptor.setJavaElement(fragment);
            descriptor.setNewName(newPackageName);
            descriptor.setUpdateReferences(updateReferences);

            return runRefactoring(service, descriptor, "move_package", arguments);

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Walk the project's package-fragment roots looking for a fragment whose
     * element name matches {@code packageName}. Default-package match uses
     * {@code ""} as packageName.
     */
    private static IPackageFragment findPackage(IJavaProject jp, String packageName) throws Exception {
        for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                continue;
            }
            IPackageFragment fragment = root.getPackageFragment(packageName);
            if (fragment != null && fragment.exists()) {
                return fragment;
            }
        }
        return null;
    }
}
