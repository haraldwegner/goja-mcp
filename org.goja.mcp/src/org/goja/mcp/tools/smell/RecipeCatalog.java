package org.goja.mcp.tools.smell;

import java.util.List;

/**
 * Sprint 22a R.1 (Stage 14) — the <b>recipe bridge</b>. Generalizes {@link OcpCure}
 * from the single OCP-cure axis to the whole detect&rarr;cure map: a smell {@code kind}
 * (as reported by {@code find_quality_issue}) &rarr; the ordered recipe kinds that close
 * it, each a runnable, parity-gated plan kind
 * ({@code PlanRefactoringTool.PLAN_KINDS}). The plan front door consumes this so a
 * finding hands straight to {@code refactoring(action=plan, kind=<smell>)} &rarr;
 * {@code apply_plan}.
 *
 * <p>Honest by construction: a kind with no known recipe returns an empty list, and the
 * caller reports "no recipe" — never a stub. Only {@code (family,kind)}s that map to an
 * existing {@code refactor_to_pattern}/{@code compose_method} primitive are covered
 * (Sprint 22a R.1 risk note); everything else is left to the human.</p>
 */
public final class RecipeCatalog {

    private RecipeCatalog() {
    }

    /**
     * The recipe (plan) kinds that cure smell {@code kind}, best-first; empty if none.
     * The OCP traces ({@code divergent_change}/{@code shotgun_surgery}) delegate to
     * {@link OcpCure}; the remaining mappings are the non-OCP cures.
     */
    public static List<String> recipesFor(String kind) {
        if (kind == null) {
            return List.of();
        }
        List<String> ocp = OcpCure.recipesFor(kind);
        if (!ocp.isEmpty()) {
            return ocp;
        }
        return switch (kind) {
            case "singleton"         -> List.of("inline_singleton");
            case "type_code"         -> List.of("replace_type_code_with_class");
            case "switch_statements" -> List.of("refactor_to_state");
            case "long_method"       -> List.of("compose_method");
            default                  -> List.of();
        };
    }

    /** Whether {@code kind} has at least one known cure recipe. */
    public static boolean hasRecipe(String kind) {
        return !recipesFor(kind).isEmpty();
    }
}
