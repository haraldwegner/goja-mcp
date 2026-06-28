package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.internal.corext.refactoring.sef.SelfEncapsulateFieldRefactoring;
import org.goja.core.IJdtService;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.goja.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 11 Phase E — {@code encapsulate_field}: generate getter/setter,
 * replace direct accesses, optionally tighten the field's visibility.
 *
 * <p>JDT's {@code EncapsulateFieldDescriptor} has no public setters, so
 * this tool drives the internal {@link SelfEncapsulateFieldRefactoring}
 * directly. See {@code docs/upgrade-checklist.md} for what to verify on
 * Eclipse target-platform bumps.</p>
 */
public class EncapsulateFieldTool extends AbstractRefactoringTool {

    public EncapsulateFieldTool(Supplier<IJdtService> serviceSupplier,
                               RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "encapsulate_field";
    }

    @Override
    public String getDescription() {
        return """
            Generate getter/setter for a field, replace direct accesses with
            the accessors, and optionally tighten the field's visibility.

            USAGE:
              encapsulate_field(filePath="src/main/java/com/example/Foo.java",
                                line=12, column=20,
                                newFieldVisibility="private")

            Inputs:
            - filePath / line / column — position on the field declaration
              or any of its references (zero-based line/column).
            - getterName  — defaults to 'get' + Capitalized(name); use 'is'
                            for boolean fields.
            - setterName  — defaults to 'set' + Capitalized(name).
            - newFieldVisibility — public | protected | private | package
                                   (default 'private').
            - generateJavadoc (default false) — emit Javadoc stubs on the
              generated accessors.

            Conflict (e.g. an accessor name already exists) →
            REFACTORING_FAILED with no files modified.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file containing the field."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line number on the field."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column number on the line."));
        properties.put("getterName", Map.of("type", "string",
            "description", "Optional. Default: 'get' + Capitalized name; 'is' for boolean."));
        properties.put("setterName", Map.of("type", "string",
            "description", "Optional. Default: 'set' + Capitalized name."));
        properties.put("newFieldVisibility", Map.of("type", "string",
            "enum", List.of("public", "protected", "private", "package"),
            "description", "Visibility for the field after encapsulation (default 'private')."));
        properties.put("generateJavadoc", Map.of("type", "boolean",
            "description", "Emit Javadoc stubs on the generated accessors (default false)."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        String getterName = getStringParam(arguments, "getterName");
        String setterName = getStringParam(arguments, "setterName");
        String visibilityStr = getStringParam(arguments, "newFieldVisibility", "private");
        boolean generateJavadoc = arguments != null && arguments.has("generateJavadoc")
            ? arguments.get("generateJavadoc").asBoolean(false)
            : false;

        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        int visibilityFlag;
        try {
            visibilityFlag = parseVisibility(visibilityStr);
        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("newFieldVisibility", e.getMessage());
        }

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            if (!(element instanceof IField field)) {
                return ToolResponse.invalidParameter("position",
                    "Position does not resolve to a field; got "
                        + (element == null ? "null" : element.getClass().getSimpleName()));
            }

            String fieldName = field.getElementName();
            String resolvedGetter = getterName != null && !getterName.isBlank()
                ? getterName
                : defaultGetterName(field, fieldName);
            String resolvedSetter = setterName != null && !setterName.isBlank()
                ? setterName
                : defaultSetterName(fieldName);

            SelfEncapsulateFieldRefactoring refactoring = new SelfEncapsulateFieldRefactoring(field);
            refactoring.setGetterName(resolvedGetter);
            refactoring.setSetterName(resolvedSetter);
            refactoring.setVisibility(visibilityFlag);
            refactoring.setEncapsulateDeclaringClass(true);
            refactoring.setGenerateJavadoc(generateJavadoc);

            return runRefactoring(service, refactoring, "encapsulate_field", arguments);

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private static int parseVisibility(String s) {
        if (s == null) return Flags.AccPrivate;
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "public"    -> Flags.AccPublic;
            case "protected" -> Flags.AccProtected;
            case "private"   -> Flags.AccPrivate;
            case "package"   -> Flags.AccDefault;
            default -> throw new IllegalArgumentException(
                "Unknown visibility '" + s + "'; expected public|protected|private|package");
        };
    }

    private static String defaultGetterName(IField field, String fieldName) throws Exception {
        String prefix = "Z".equals(field.getTypeSignature()) ? "is" : "get";
        return prefix + capitalize(fieldName);
    }

    private static String defaultSetterName(String fieldName) {
        return "set" + capitalize(fieldName);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
