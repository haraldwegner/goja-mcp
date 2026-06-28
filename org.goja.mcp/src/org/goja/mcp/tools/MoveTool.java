package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A — parametric front door for move-class / move-package. The two
 * delegates diverge in params (class moves by caret + target package; package
 * moves by name), so this is a flat schema with per-kind params documented and
 * delegate-validated.
 *
 * <p>Replaces {@code move_class} / {@code move_package}; apply/undo contract
 * unchanged.</p>
 */
public class MoveTool extends AbstractTool {

    private static final List<String> KINDS = List.of("class", "package");

    private final MoveClassTool moveClass;
    private final MovePackageTool movePackage;

    public MoveTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.moveClass = new MoveClassTool(serviceSupplier, cache);
        this.movePackage = new MovePackageTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "move";
    }

    @Override
    public String getDescription() {
        return """
            Move a class or a package, updating references (behaviour-preserving, reversible).

            USAGE: move(kind="<class|package>", ...)

            - class   — move the type at a caret to another package.
                        Needs: filePath, line, column, targetPackage (optional targetProjectKey).
            - package — move/rename a whole package.
                        Needs: packageName, newPackageName.

            Common: updateReferences (default true). IMPORTANT: ZERO-BASED coordinates.
            Applies by default; returns filesModified/diff/undoChangeId/summary.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", KINDS);
        kind.put("description", "Move a class (by caret) or a package (by name).");
        properties.put("kind", kind);

        properties.put("filePath", Map.of("type", "string", "description", "class: path to the source file."));
        properties.put("line", Map.of("type", "integer", "description", "class: zero-based line of a caret in the type."));
        properties.put("column", Map.of("type", "integer", "description", "class: zero-based column."));
        properties.put("targetPackage", Map.of("type", "string", "description", "class: destination package name."));
        properties.put("targetProjectKey", Map.of("type", "string", "description", "class: optional destination project (cross-project move)."));
        properties.put("packageName", Map.of("type", "string", "description", "package: the package to move/rename."));
        properties.put("newPackageName", Map.of("type", "string", "description", "package: the new package name."));
        properties.put("updateReferences", Map.of("type", "boolean", "description", "Update all references (default true)."));

        schema.put("properties", properties);
        schema.put("required", List.of("kind"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + KINDS);
        }
        return switch (kind) {
            case "class"   -> moveClass.executeWithService(service, arguments);
            case "package" -> movePackage.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }
}
