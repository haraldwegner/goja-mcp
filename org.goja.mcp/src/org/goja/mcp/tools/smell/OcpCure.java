package org.goja.mcp.tools.smell;

import java.util.List;

/**
 * Sprint 19 / 20 seam — the <b>OCP cure</b>. Sprint 20 deliberately ships no
 * {@code ocp} detector: an Open/Closed violation is only meaningful relative to the
 * extension axis you intend to keep open (design intent, not a code property). Its
 * observable <em>trace</em> is {@code divergent_change} / {@code shotgun_surgery}
 * (surfaced under family {@code solid}). The <b>cure is a goal</b>, and goals are
 * Kerievsky recipes — introduce an abstraction at the modification axis. This maps a
 * churn-trace kind to the applicable {@code refactor_to_pattern} kinds and is the seed
 * of the future {@code get_target_recipe}.
 */
public final class OcpCure {

    private OcpCure() {
    }

    /** Appended to the churn detectors' messages: the OCP-cure pointer. */
    public static final String HINT =
        " OCP cure: introduce an abstraction at the modification axis — refactor_to_pattern "
            + "kind=refactor_to_state / refactor_to_command_dispatcher / form_template_method "
            + "(or refactoring(action=plan, kind=<same>) then apply_plan for a parity-gated run).";

    /** The {@code refactor_to_pattern} kinds that close the axis a churn trace exposes. */
    public static List<String> recipesFor(String smellKind) {
        return switch (smellKind) {
            case "divergent_change", "shotgun_surgery" ->
                List.of("refactor_to_state", "refactor_to_command_dispatcher", "form_template_method");
            default -> List.of();
        };
    }
}
