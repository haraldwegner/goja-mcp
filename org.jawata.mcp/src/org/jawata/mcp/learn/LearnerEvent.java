package org.jawata.mcp.learn;

/**
 * One immediate label signal for the injector's learners (Sprint 26). The
 * continuous-learning contract: labels arrive AS THEY HAPPEN — a tool error,
 * an undo, a mechanical touch, a gate call, a compile failure on a touched
 * file — never batched, never waiting for an offline cycle.
 *
 * @param sessionId the MCP session the signal belongs to (minted by the
 *     transport; {@code "local"} for direct/in-process calls)
 * @param kind one of the {@code KIND_*} constants
 * @param tool the tool whose call produced the signal
 * @param detailJson free-form JSON detail (small — counts, flags, file lists)
 */
public record LearnerEvent(String sessionId, String kind, String tool, String detailJson) {

    /** A tool returned a structured error (the resident's own failure — also a defect signal). */
    public static final String KIND_TOOL_ERROR = "tool_error";
    /** A refactoring undo was requested — the immediate revert label. */
    public static final String KIND_UNDO = "undo";
    /** A mechanical transform touched files (rename/move/… — the exempt set). */
    public static final String KIND_MECHANICAL_TOUCH = "mechanical_touch";
    /** A gate tool ran (compile/diagnostics/tests) — carries its outcome. */
    public static final String KIND_GATE_CALL = "gate_call";
    /** A gate reported errors while mechanically-touched files were pending — the compile-after-touch failure label. */
    public static final String KIND_COMPILE_AFTER_TOUCH_FAIL = "compile_after_touch_fail";
    /** A watch finding was emitted (Stage 2 writes these; fate arrives as a later event). */
    public static final String KIND_WATCH_FINDING = "watch_finding";
    /** An observed .java edit arrived (observe_edit) — held pending until its consequence. */
    public static final String KIND_EDIT_OBSERVED = "edit_observed";
    /** Pending edits were resolved by their consequence (a gate outcome or an undo). */
    public static final String KIND_EDIT_RESOLVED = "edit_resolved";
}
