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
 * Sprint 19 (Kerievsky "Refactoring to Patterns") — parametric front door for the
 * pattern-targeted transforms. Mirrors {@link ExtractTool}: a flat schema whose
 * {@code kind} selects a delegate that self-validates its per-kind params, carrying
 * the apply/undo contract unchanged (returns filesModified/diff/undoChangeId/summary).
 *
 * <p>The catalogue runs both directions — <em>toward</em> patterns (when complexity
 * warrants) and <em>away</em> from patterns (Kerievsky's distinguishing insight:
 * Inline Singleton, Replace Pattern with Idiom). Detection of candidates is via
 * {@code find_quality_issue} kinds (family {@code kerievsky} + reused Fowler kinds);
 * this tool applies the chosen transform at a caller-supplied target.</p>
 */
public class RefactorToPatternTool extends AbstractTool {

    /** All kinds in the catalogue; delegates are wired in as each ships. */
    private static final List<String> KINDS = List.of(
        "inline_singleton",              // away  — Stage 0
        "compose_method",                // toward — Stage 1
        "replace_type_code_with_class",  // toward — Stage 2
        "refactor_to_state",             // toward — Stage 3
        "refactor_to_command_dispatcher",// toward — Stage 4
        "form_template_method",          // toward — Stage 5
        "refactor_to_visitor",           // toward — Stage 6
        "replace_pattern_with_idiom");   // away  — Stage 7

    private final InlineSingletonTool inlineSingleton;
    private final ComposeMethodTool composeMethod;
    private final ReplaceTypeCodeWithClassTool replaceTypeCode;
    private final RefactorToStateTool refactorToState;
    private final RefactorToCommandDispatcherTool refactorToCommand;

    public RefactorToPatternTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.inlineSingleton = new InlineSingletonTool(serviceSupplier, cache);
        this.composeMethod = new ComposeMethodTool(serviceSupplier, cache);
        this.replaceTypeCode = new ReplaceTypeCodeWithClassTool(serviceSupplier, cache);
        this.refactorToState = new RefactorToStateTool(serviceSupplier, cache);
        this.refactorToCommand = new RefactorToCommandDispatcherTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "refactor_to_pattern";
    }

    @Override
    public String getDescription() {
        return """
            Apply a pattern-targeted refactoring (Kerievsky "Refactoring to Patterns"),
            behaviour-preserving and reversible. Runs BOTH directions — toward a pattern
            when complexity warrants, and AWAY from a pattern that has outlived its use.

            USAGE: refactor_to_pattern(kind="<kind>", filePath=..., line=..., column=...)

            Kinds (ZERO-BASED coordinates; find candidates with find_quality_issue):
            - inline_singleton — AWAY: a GoF singleton whose uniqueness no longer matters →
                                 rewrite Type.getInstance() call sites to `new Type()`, make the
                                 constructor public, and strip the static holder + accessor.
                                 Needs: line, column on the singleton type. (find_quality_issue
                                 kind=singleton locates candidates.)
            - compose_method   — TOWARD: reshape a long method into a short sequence of
                                 intention-revealing calls. Needs: `sections` = an array of
                                 >= 2 disjoint {startLine, startColumn, endLine, endColumn,
                                 methodName} statement ranges to extract. Applies atomically
                                 (auto_apply=false not supported). (find_quality_issue
                                 kind=long_method locates candidates.)
            - replace_type_code_with_class — TOWARD: generate a type-safe enum from a group of
                                 static-final type-code constants into a new file (same package).
                                 Needs: line, column on the class + newTypeName (optional prefix
                                 to pick the constant group). Conservative: introduces the enum +
                                 reports the mapping; does NOT auto-migrate usages.
                                 (find_quality_issue kind=type_code locates candidates.)
            - refactor_to_state — TOWARD: a method switching on an int state field becomes
                                 delegation to nested State classes (a <Context>State interface +
                                 one inner class per case). Needs: line, column on/inside the
                                 switch. Conservative — refuses unsafe shapes (see errors).
                                 (find_quality_issue kind=switch_statements locates candidates.)
            - refactor_to_command_dispatcher — TOWARD: a type-coded action switch becomes nested
                                 Command classes selected by a switch-expression. Needs: line,
                                 column on/inside the switch. Conservative — case bodies must not
                                 use the method's parameters. (find_quality_issue
                                 kind=switch_statements locates candidates.)
            (further kinds ship across Sprint 19: form_template_method,
             refactor_to_visitor, replace_pattern_with_idiom.)

            Applies by default; returns filesModified/diff/undoChangeId/summary. Pass
            auto_apply=false to stage without applying. Verify with compile_workspace;
            revert with undo_refactoring(undoChangeId).

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
        kind.put("description", "Which pattern transform to apply. See the tool description for per-kind params.");
        properties.put("kind", kind);

        properties.put("filePath", Map.of("type", "string", "description", "Path to source file."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the target (e.g. the singleton type for inline_singleton)."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column of the target."));
        properties.put("sections", Map.of("type", "array",
            "items", Map.of("type", "object"),
            "description", "compose_method: >= 2 disjoint {startLine, startColumn, endLine, endColumn, "
                + "methodName} statement ranges (ZERO-BASED) to extract into named sub-methods."));
        properties.put("newTypeName", Map.of("type", "string",
            "description", "replace_type_code_with_class: name for the generated enum type."));
        properties.put("prefix", Map.of("type", "string",
            "description", "replace_type_code_with_class: optional constant prefix to convert (e.g. STATUS)."));

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
            case "inline_singleton" -> inlineSingleton.executeWithService(service, arguments);
            case "compose_method" -> composeMethod.executeWithService(service, arguments);
            case "replace_type_code_with_class" -> replaceTypeCode.executeWithService(service, arguments);
            case "refactor_to_state" -> refactorToState.executeWithService(service, arguments);
            case "refactor_to_command_dispatcher" -> refactorToCommand.executeWithService(service, arguments);
            case "form_template_method", "refactor_to_visitor",
                 "replace_pattern_with_idiom" -> ToolResponse.error(
                    "NOT_YET_IMPLEMENTED",
                    "refactor_to_pattern kind '" + kind + "' ships later in Sprint 19.",
                    "Available now: inline_singleton. See the tool description for the schedule.");
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }
}
