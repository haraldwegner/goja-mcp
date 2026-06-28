package org.goja.mcp.tools.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.tools.AbstractTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A — parametric front door for the six code-generation tools,
 * selected by {@code kind}. Lives in the {@code codegen} package so it can reach
 * the delegates' package-private {@link AbstractTool#executeWithService}.
 *
 * <p>Replaces {@code generate_constructor} / {@code generate_getters_setters} /
 * {@code generate_equals_hashcode} / {@code generate_tostring} /
 * {@code generate_test_skeleton} / {@code override_methods}. Apply/undo contract
 * unchanged.</p>
 *
 * <p>Name collision: {@code generate_getters_setters} reads its accessor
 * selection from a param literally named {@code kind} (get/set/both). Our
 * discriminator already claims {@code kind}, so the accessor selection is exposed
 * publicly as {@code accessorKind} and remapped onto the delegate's {@code kind}
 * on dispatch (mirrors {@code find_quality_issue}'s query→exceptionType alias).</p>
 */
public class GenerateTool extends AbstractTool {

    private static final List<String> KINDS = List.of(
        "constructor", "getters_setters", "equals_hashcode",
        "tostring", "test_skeleton", "override_methods");

    private final GenerateConstructorTool constructor;
    private final GenerateGettersSettersTool gettersSetters;
    private final GenerateEqualsHashCodeTool equalsHashCode;
    private final GenerateToStringTool toStringTool;
    private final GenerateTestSkeletonTool testSkeleton;
    private final OverrideMethodsTool overrideMethods;

    public GenerateTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.constructor = new GenerateConstructorTool(serviceSupplier, cache);
        this.gettersSetters = new GenerateGettersSettersTool(serviceSupplier, cache);
        this.equalsHashCode = new GenerateEqualsHashCodeTool(serviceSupplier, cache);
        this.toStringTool = new GenerateToStringTool(serviceSupplier, cache);
        this.testSkeleton = new GenerateTestSkeletonTool(serviceSupplier, cache);
        this.overrideMethods = new OverrideMethodsTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "generate";
    }

    @Override
    public String getDescription() {
        return """
            Generate boilerplate into the type at a caret (reversible).

            USAGE: generate(kind="<kind>", filePath=..., line=..., column=...)

            Kinds (all ZERO-BASED coordinates on a caret in the target type):
            - constructor      — a constructor. Needs: fields[]. Optional: visibility, callSuper.
            - getters_setters  — accessors. Needs: fields[]. Optional: accessorKind (get|set|both, default both), visibility.
            - equals_hashcode  — equals() + hashCode(). Needs: fields[].
            - tostring         — toString(). Needs: fields[]. Optional: style.
            - test_skeleton    — a test class for the type. Optional: framework, includePrivateMethods.
            - override_methods — override/implement methods. Optional: methods[] (else all overridable).

            Applies by default; returns filesModified/diff/undoChangeId/summary. Pass
            auto_apply=false to stage only.

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
        kind.put("description", "Which generator to run. See the tool description for per-kind params.");
        properties.put("kind", kind);

        properties.put("filePath", Map.of("type", "string", "description", "Path to source file."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line of a caret in the target type."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));
        properties.put("fields", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "constructor/getters_setters/equals_hashcode/tostring: field names to include."));
        properties.put("visibility", Map.of("type", "string", "description", "constructor/getters_setters: member visibility (e.g. public)."));
        properties.put("callSuper", Map.of("type", "string", "description", "constructor: whether to call super (auto|true|false)."));
        properties.put("accessorKind", Map.of("type", "string", "enum", List.of("get", "set", "both"),
            "description", "getters_setters: which accessors to generate (default both)."));
        properties.put("style", Map.of("type", "string", "description", "tostring: rendering style."));
        properties.put("framework", Map.of("type", "string", "description", "test_skeleton: test framework (e.g. junit5)."));
        properties.put("includePrivateMethods", Map.of("type", "boolean", "description", "test_skeleton: also stub private methods."));
        properties.put("methods", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "override_methods: specific method names to override (default all overridable)."));

        schema.put("properties", properties);
        schema.put("required", List.of("kind", "filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + KINDS);
        }
        return switch (kind) {
            case "constructor"      -> constructor.executeWithService(service, arguments);
            case "getters_setters"  -> gettersSetters.executeWithService(service, remapAccessorKind(arguments));
            case "equals_hashcode"  -> equalsHashCode.executeWithService(service, arguments);
            case "tostring"         -> toStringTool.executeWithService(service, arguments);
            case "test_skeleton"    -> testSkeleton.executeWithService(service, arguments);
            case "override_methods" -> overrideMethods.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }

    /**
     * Map the public {@code accessorKind} onto the delegate's {@code kind} param
     * (get/set/both), since our discriminator already occupies {@code kind}.
     */
    private JsonNode remapAccessorKind(JsonNode arguments) {
        ObjectNode copy = (ObjectNode) arguments.deepCopy();
        String accessor = getStringParam(arguments, "accessorKind", "both");
        copy.put("kind", accessor);
        copy.remove("accessorKind");
        return copy;
    }
}
