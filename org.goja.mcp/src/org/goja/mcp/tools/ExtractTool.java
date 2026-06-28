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
 * Sprint 16b/A — parametric front door for the four LTK extract refactorings.
 * Each delegate self-validates its required params in {@code executeWithService},
 * so a flat schema (only {@code kind} + {@code filePath} required) is safe; the
 * per-kind params are documented and validated by the delegate.
 *
 * <p>Replaces {@code extract_method} / {@code extract_variable} /
 * {@code extract_constant} / {@code extract_interface}. Carries the apply/undo
 * contract unchanged (returns filesModified/diff/undoChangeId/summary).</p>
 */
public class ExtractTool extends AbstractTool {

    private static final List<String> KINDS = List.of("method", "variable", "constant", "interface");

    private final ExtractMethodTool method;
    private final ExtractVariableTool variable;
    private final ExtractConstantTool constant;
    private final ExtractInterfaceTool interfaceTool;

    public ExtractTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.method = new ExtractMethodTool(serviceSupplier, cache);
        this.variable = new ExtractVariableTool(serviceSupplier, cache);
        this.constant = new ExtractConstantTool(serviceSupplier, cache);
        this.interfaceTool = new ExtractInterfaceTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "extract";
    }

    @Override
    public String getDescription() {
        return """
            Extract a method, variable, constant, or interface (behaviour-preserving, reversible).

            USAGE: extract(kind="<kind>", filePath=..., ...)

            Kinds and their params (all ZERO-BASED coordinates):
            - method   — extract a statement range into a new method.
                         Needs: startLine, startColumn, endLine, endColumn, methodName.
            - variable — extract an expression range into a local variable.
                         Needs: startLine, startColumn, endLine, endColumn (optional variableName).
            - constant — extract an expression range into a static final constant.
                         Needs: startLine, startColumn, endLine, endColumn, constantName.
            - interface— extract an interface from the type at a caret.
                         Needs: line, column, interfaceName (optional methodNames[] to pull up).

            Applies by default; returns filesModified/diff/undoChangeId/summary. Pass
            auto_apply=false to stage without applying.

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
        kind.put("description", "Which extract refactoring to run. See the tool description for per-kind params.");
        properties.put("kind", kind);

        properties.put("filePath", Map.of("type", "string", "description", "Path to source file."));
        properties.put("startLine", Map.of("type", "integer", "description", "method/variable/constant: zero-based start line of the selection."));
        properties.put("startColumn", Map.of("type", "integer", "description", "method/variable/constant: zero-based start column."));
        properties.put("endLine", Map.of("type", "integer", "description", "method/variable/constant: zero-based end line."));
        properties.put("endColumn", Map.of("type", "integer", "description", "method/variable/constant: zero-based end column."));
        properties.put("line", Map.of("type", "integer", "description", "interface: zero-based line of a caret in the type."));
        properties.put("column", Map.of("type", "integer", "description", "interface: zero-based column."));
        properties.put("methodName", Map.of("type", "string", "description", "method: name for the extracted method."));
        properties.put("variableName", Map.of("type", "string", "description", "variable: optional name for the extracted variable."));
        properties.put("constantName", Map.of("type", "string", "description", "constant: name for the extracted constant."));
        properties.put("interfaceName", Map.of("type", "string", "description", "interface: name for the extracted interface."));
        properties.put("methodNames", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "interface: optional method names to declare in the new interface."));

        schema.put("properties", properties);
        schema.put("required", List.of("kind", "filePath"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + KINDS);
        }
        return switch (kind) {
            case "method"    -> method.executeWithService(service, arguments);
            case "variable"  -> variable.executeWithService(service, arguments);
            case "constant"  -> constant.executeWithService(service, arguments);
            case "interface" -> interfaceTool.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }
}
