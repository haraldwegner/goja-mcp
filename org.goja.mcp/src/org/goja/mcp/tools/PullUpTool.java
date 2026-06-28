package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.structure.PullUpRefactoringProcessor;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.goja.core.IJdtService;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 11 Phase E — {@code pull_up}: move a method or field from a
 * subtype up to its direct superclass.
 *
 * <p>JDT's {@code PullUpDescriptor} has no public setters in the
 * 2024-09 release — the descriptor is configured via an internal
 * argument map. Rather than serialize through that map (stringly-typed,
 * undocumented keys), this tool drives the internal
 * {@link PullUpRefactoringProcessor} directly. See
 * {@code docs/upgrade-checklist.md} for the implications of this
 * dependency on Eclipse target-platform bumps.</p>
 */
public class PullUpTool extends AbstractRefactoringTool {

    public PullUpTool(Supplier<IJdtService> serviceSupplier,
                     RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "pull_up";
    }

    @Override
    public String getDescription() {
        return """
            Move a method or field from a subtype up to its direct superclass.

            USAGE:
              pull_up(filePath="src/main/java/com/example/Dog.java",
                      line=12, column=20,
                      abstractInOriginal=false)

            Inputs:
            - filePath / line / column — position inside the method or field
              to move (zero-based line/column).
            - abstractInOriginal (default false) — for methods, also leave
              an abstract declaration on the original subtype.

            The supertype is the direct superclass of the subtype. To climb
            multiple levels, call pull_up repeatedly.

            Conflict (e.g. supertype already declares the same member,
            superclass is from a binary jar) → REFACTORING_FAILED with no
            files modified.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file containing the member."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line number on the member."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column number on the line."));
        properties.put("abstractInOriginal", Map.of("type", "boolean",
            "description", "For methods only: leave an abstract declaration on the subtype (default false)."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        boolean abstractInOriginal = arguments != null && arguments.has("abstractInOriginal")
            ? arguments.get("abstractInOriginal").asBoolean(false)
            : false;

        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            if (!(element instanceof IMember member)) {
                return ToolResponse.invalidParameter("position",
                    "Position does not resolve to a method or field; got "
                        + (element == null ? "null" : element.getClass().getSimpleName()));
            }
            IType subtype = member.getDeclaringType();
            if (subtype == null) {
                return ToolResponse.invalidParameter("position",
                    "Member has no declaring type — cannot pull up");
            }
            String superclassName = subtype.getSuperclassName();
            if (superclassName == null || "Object".equals(superclassName) || "java.lang.Object".equals(superclassName)) {
                return ToolResponse.invalidParameter("position",
                    "Subtype's superclass is java.lang.Object (or absent); nothing to pull up to");
            }
            IType destination = resolveSuperclass(subtype);
            if (destination == null) {
                return ToolResponse.invalidParameter("position",
                    "Could not resolve direct superclass of " + subtype.getFullyQualifiedName());
            }

            CodeGenerationSettings settings = new CodeGenerationSettings();
            PullUpRefactoringProcessor processor = new PullUpRefactoringProcessor(
                new IMember[]{member}, settings);
            processor.setDestinationType(destination);
            processor.setMembersToMove(new IMember[]{member});

            if (member instanceof IMethod method) {
                if (abstractInOriginal) {
                    processor.setAbstractMethods(new IMethod[]{method});
                    processor.setDeletedMethods(new IMethod[0]);
                } else {
                    processor.setAbstractMethods(new IMethod[0]);
                    processor.setDeletedMethods(new IMethod[]{method});
                }
            }

            ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
            return runRefactoring(service, refactoring, "pull_up", arguments);

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private static IType resolveSuperclass(IType subtype) throws Exception {
        org.eclipse.jdt.core.ITypeHierarchy hierarchy = subtype.newSupertypeHierarchy(null);
        IType[] supers = hierarchy.getSuperclass(subtype) == null
            ? new IType[0]
            : new IType[]{hierarchy.getSuperclass(subtype)};
        return supers.length == 0 ? null : supers[0];
    }
}
