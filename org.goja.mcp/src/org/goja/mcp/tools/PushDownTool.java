package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.internal.corext.refactoring.structure.PushDownRefactoringProcessor;
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
 * Sprint 11 Phase E — {@code push_down}: move a method or field from a
 * supertype down into all of its direct subtypes.
 *
 * <p>Like {@link PullUpTool} this drives JDT's internal
 * {@link PushDownRefactoringProcessor} directly because the public
 * {@code PushDownDescriptor} has no setters in the 2024-09 release. See
 * {@code docs/upgrade-checklist.md} for what to verify on Eclipse
 * target-platform bumps.</p>
 */
public class PushDownTool extends AbstractRefactoringTool {

    public PushDownTool(Supplier<IJdtService> serviceSupplier,
                       RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "push_down";
    }

    @Override
    public String getDescription() {
        return """
            Move a method or field from a supertype down into all of its
            direct subtypes.

            USAGE:
              push_down(filePath="src/main/java/com/example/Animal.java",
                        line=8, column=20)

            Inputs:
            - filePath / line / column — position inside the method or field
              to push down (zero-based line/column).

            All direct subtypes receive a copy of the member; the original
            on the supertype is removed. JDT's checkInitialConditions
            rejects the operation if any subtype is read-only (binary, in a
            sealed jar) before any modification happens.

            Conflict → REFACTORING_FAILED with no files modified.

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
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);

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
            IType supertype = member.getDeclaringType();
            if (supertype == null) {
                return ToolResponse.invalidParameter("position",
                    "Member has no declaring type — cannot push down");
            }

            PushDownRefactoringProcessor processor =
                new PushDownRefactoringProcessor(new IMember[]{member});

            ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
            return runRefactoring(service, refactoring, "push_down", arguments);

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
