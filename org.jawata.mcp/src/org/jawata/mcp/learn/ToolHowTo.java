package org.jawata.mcp.learn;

import java.util.Map;

/**
 * v3.3.1: the one-line HOW-TO the precedent push carries (Sprint 26a D2 body —
 * the injector "surfaces the precedent (with the how-to)"). v3.3.0 named the
 * tool and the agreement count but never said how to drive it, so a pushed
 * precedent read as a bare name; an external code review against the signed
 * spec caught the gap.
 *
 * <p>Deliberately ONE line per tool, phrased to match the D1 catalog delivered
 * at initialize ({@code McpProtocolHandler.SERVER_INSTRUCTIONS}) so the two
 * cannot drift into contradicting each other. An uncatalogued tool yields
 * {@code null}: the hint is ADDITIVE, and its absence never suppresses the
 * precedent itself.</p>
 */
public final class ToolHowTo {

    private ToolHowTo() {
    }

    private static final Map<String, String> HOW_TO = Map.ofEntries(
        // CHANGE — a hand-edit misses references; stage before you apply.
        Map.entry("rename_symbol",
            "rename_symbol updates ALL references — address by FQN ('com.foo.Bar#method')"),
        Map.entry("extract",
            "stage it: pass auto_apply=false, review the diff, then apply (compile-verified, undoable)"),
        Map.entry("move",
            "stage it: pass auto_apply=false, review the diff, then apply (compile-verified, undoable)"),
        Map.entry("move_in_hierarchy",
            "stage it: pass auto_apply=false, review the diff, then apply (compile-verified, undoable)"),
        Map.entry("move_method",
            "stage it: pass auto_apply=false, review the diff, then apply (compile-verified, undoable)"),
        Map.entry("inline",
            "stage it: pass auto_apply=false, review the diff, then apply (compile-verified, undoable)"),
        Map.entry("change_method_signature",
            "stage it with auto_apply=false — a coupled change lists every introduced error as the worklist"),
        Map.entry("refactoring",
            "refactoring(action=plan) then apply_plan — parity-gated per step, rolled back atomically"),
        Map.entry("generate",
            "generate(kind=copy_class) duplicates a class; stage with auto_apply=false to review first"),
        // FIND / UNDERSTAND — grep misses or overmatches symbols.
        Map.entry("search_symbols",
            "search_symbols takes globs ('*Service'); pass fields=[...] to trim large result rows"),
        Map.entry("find_references",
            "address by FQN ('com.foo.Bar#method'); kind=implementations for subtypes"),
        Map.entry("analyze",
            "analyze(kind=symbol) answers definition + type + references in ONE call"),
        Map.entry("inspect",
            "inspect(kind=type_members|type_hierarchy) for shape; kind=source reads ANY type by FQN"),
        // THE OUTCOME GATE.
        Map.entry("compile_workspace",
            "compile_workspace + get_diagnostics is the outcome gate — run it after every edit"),
        // RUNTIME — both need ZERO code change.
        Map.entry("debug",
            "debug: attach or launch, probe_set kind=logpoint reads LIVE values with ZERO code change"),
        Map.entry("profile",
            "profile: sample the JVM — hotspots / latency_seam name the hotspot as a symbol, ZERO code change")
    );

    /**
     * @param tool the tool named by a precedent ({@code null} tolerated)
     * @return its one-line how-to, or {@code null} when the tool has none catalogued
     */
    public static String of(String tool) {
        return tool == null ? null : HOW_TO.get(tool);
    }
}
