package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 14b Stage A2 — readOnlyHint annotations on detect tools
 * (MCP spec ≥ 2025-03-26; Cursor Ask-mode unblock).
 */
class ReadOnlyHintAnnotationTest {

    private static Tool stub(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "stub"; }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of());
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> definitionOf(ToolRegistry registry, String name) {
        return registry.getToolDefinitions().stream()
            .filter(def -> name.equals(def.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("definition missing for " + name));
    }

    @Test
    @DisplayName("detect tools carry annotations.readOnlyHint = true")
    void detectTools_carryReadOnlyHint() {
        ToolRegistry registry = new ToolRegistry();
        List<String> readOnly = List.of(
            // prefix-rule representatives across all four detect families
            "find_references", "find_duplicate_code",
            "get_diagnostics", "get_call_hierarchy_incoming",
            "analyze_type", "analyze_change_impact",
            "search_symbols",
            // read-only tools whose names match no detect prefix
            "go_to_definition", "health_check", "list_projects",
            "validate_syntax", "inspect_refactoring", "suggest_imports");
        readOnly.forEach(name -> registry.register(stub(name)));

        for (String name : readOnly) {
            Map<String, Object> def = definitionOf(registry, name);
            @SuppressWarnings("unchecked")
            Map<String, Object> annotations = (Map<String, Object>) def.get("annotations");
            assertNotNull(annotations, name + " must carry annotations");
            assertEquals(Boolean.TRUE, annotations.get("readOnlyHint"),
                name + " must be marked readOnlyHint");
        }
    }

    @Test
    @DisplayName("mutating tools carry no annotations")
    void mutatingTools_carryNoAnnotations() {
        ToolRegistry registry = new ToolRegistry();
        List<String> mutating = List.of(
            // refactor
            "rename_symbol", "extract_method", "move_class", "pull_up",
            "change_method_signature", "organize_imports", "format",
            // codegen
            "generate_constructor", "override_methods",
            // apply/undo primitives (they mutate; inspect is the read-only one)
            "apply_refactoring", "undo_refactoring",
            // project + workspace state
            "load_project", "add_project", "remove_project", "refresh_workspace",
            // build / test / fix / dependencies
            "compile_workspace", "run_tests", "apply_quick_fix",
            "optimize_imports_workspace", "add_dependency", "update_dependency");
        mutating.forEach(name -> registry.register(stub(name)));

        for (String name : mutating) {
            Map<String, Object> def = definitionOf(registry, name);
            assertNull(def.get("annotations"),
                name + " must NOT carry annotations (it mutates state)");
        }
    }
}
