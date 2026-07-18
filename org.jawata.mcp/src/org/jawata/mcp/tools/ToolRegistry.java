package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.workspace.StrictDiskSync;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Registry for all JAWATA tools.
 * Handles tool registration, listing, and dispatching calls.
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * Sprint 14b: MCP tool annotations (spec ≥ 2025-03-26). Detect tools are
     * marked {@code readOnlyHint} so restricted client modes (Cursor Ask
     * mode) can allow them without switching to an agent mode. Name-driven:
     * the four detect prefixes plus the read-only tools whose names match no
     * prefix. Anything that mutates source, project configuration, build
     * output, or workspace state stays unannotated — when in doubt, leave a
     * tool unannotated (a missing hint is "unknown", a wrong hint lets a
     * restricted mode mutate the workspace).
     */
    private static final Set<String> READ_ONLY_PREFIXES =
        Set.of("find_", "get_", "analyze_", "search_");
    private static final Set<String> READ_ONLY_NAMES = Set.of(
        "go_to_definition", "health_check", "list_projects",
        "validate_syntax", "inspect_refactoring", "suggest_imports",
        // Sprint 16b/A (v1.1.1): parametric read-only front doors whose names
        // match no detect prefix.
        "analyze", "inspect");

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** Sprint 21d: the strict-disk-sync guard, run once per call before any tool executes. */
    private StrictDiskSync diskSync;

    /** Install the per-call disk-sync guard (Sprint 21d). Null = no guard (tests). */
    public void setDiskSync(StrictDiskSync diskSync) {
        this.diskSync = diskSync;
    }

    /** Sprint 21e (item A): fired after a SUCCESSFUL project mutation
     *  ({@code load_project}, {@code project(action=add|remove)}) so the experience
     *  store's refresh + anchor backfill see the new project set. Null = no hook. */
    private Runnable projectsMutatedHook;

    /** Sprint 26: the learner event tap — null until the application wires it. */
    private org.jawata.mcp.learn.EventTap eventTap;

    /** Sprint 26 (D1): the watch engine — null until the application wires it. */
    private org.jawata.mcp.learn.WatchEngine watchEngine;

    /** Sprint 26 (D4/D5): the server-side checks — null until wired. */
    private org.jawata.mcp.learn.ServerChecks serverChecks;

    /** Sprint 26: install the server-side checks (application wiring). */
    public void setServerChecks(org.jawata.mcp.learn.ServerChecks checks) {
        this.serverChecks = checks;
    }

    /** Install the post-project-mutation hook (Sprint 21e). Null = no hook (tests). */
    public void setProjectsMutatedHook(Runnable hook) {
        this.projectsMutatedHook = hook;
    }

    private static boolean isProjectMutation(String name, JsonNode arguments) {
        if ("load_project".equals(name)) {
            return true;
        }
        if (!"project".equals(name)) {
            return false;
        }
        String action = arguments != null && arguments.has("action")
            ? arguments.get("action").asText("") : "";
        return "add".equals(action) || "remove".equals(action);
    }

    /**
     * Register a tool with the registry.
     */
    public void register(Tool tool) {
        String name = tool.getName();
        if (tools.containsKey(name)) {
            log.warn("Overwriting existing tool: {}", name);
        }
        tools.put(name, tool);
        log.debug("Registered tool: {}", name);
    }

    /**
     * Register multiple tools.
     */
    public void registerAll(Tool... toolsToRegister) {
        for (Tool tool : toolsToRegister) {
            register(tool);
        }
    }

    /**
     * Get a tool by name.
     */
    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    /**
     * Check if a tool exists.
     */
    public boolean hasTool(String name) {
        return tools.containsKey(name);
    }

    /**
     * Get all registered tool names.
     */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * Get the number of registered tools.
     */
    public int getToolCount() {
        return tools.size();
    }

    /**
     * Get tool definitions for the tools/list response.
     * Returns a list of tool definitions in MCP format.
     */
    public List<Map<String, Object>> getToolDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();

        for (Tool tool : tools.values()) {
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("name", tool.getName());
            def.put("description", tool.getDescription());
            def.put("inputSchema", tool.getInputSchema());
            if (isReadOnly(tool.getName())) {
                def.put("annotations", Map.of("readOnlyHint", Boolean.TRUE));
            }
            definitions.add(def);
        }

        return definitions;
    }

    /**
     * Sprint 22 (POST layer): tools that get no steering — session/build/verify
     * tools where a "next step" nudge would be noise or self-referential.
     */
    private static final Set<String> NO_STEER = Set.of(
        "health_check", "list_projects", "load_project", "project",
        "refresh_workspace", "validate_syntax", "compile_workspace", "get_diagnostics");

    /**
     * The directional next-step nudge for a tool, by category. Reuses
     * {@link #isReadOnly} to split navigate/understand (→ change with a JAWATA
     * refactor tool, not a hand-edit) from mutate (→ verify with the build).
     * Returns null for session/build/verify tools (see {@link #NO_STEER}).
     */
    static String steeringFor(String name) {
        if (NO_STEER.contains(name)) {
            return null;
        }
        if (isReadOnly(name)) {
            return "Grounded next step: change what you found with a JAWATA refactor tool "
                + "(rename_symbol / extract / move / refactoring(action=plan)) — not a hand-edit "
                + "(grep and hand-edits miss references).";
        }
        return "Grounded next step: verify with compile_workspace + get_diagnostics; "
            + "JAWATA changes are reversible (undo / undo_plan).";
    }

    /** True when the named tool is a detect tool per the Sprint 14b sets. */
    static boolean isReadOnly(String toolName) {
        if (READ_ONLY_NAMES.contains(toolName)) {
            return true;
        }
        for (String prefix : READ_ONLY_PREFIXES) {
            if (toolName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sprint 22a P2 rider: renamed / consolidated tool names → their current
     * front door, so a call to an old name gets a "did you mean" hint instead of
     * a bare not-found (the tool surface collapsed many narrow tools into
     * parametric front doors across Sprints 11–19).
     */
    private static final Map<String, String> RENAMED_TOOLS = Map.ofEntries(
        Map.entry("get_type_members", "inspect"),
        Map.entry("get_type_hierarchy", "inspect"),
        Map.entry("get_type_usage_summary", "inspect"),
        Map.entry("get_symbol_info", "get_at_position"),
        Map.entry("get_call_hierarchy_incoming", "get_call_hierarchy"),
        Map.entry("get_call_hierarchy_outgoing", "get_call_hierarchy"),
        Map.entry("find_method_references", "find_references"),
        Map.entry("move_class", "move"),
        Map.entry("move_package", "move"),
        Map.entry("pull_up", "move_in_hierarchy"),
        Map.entry("push_down", "move_in_hierarchy"),
        Map.entry("extract_method", "extract"),
        Map.entry("extract_variable", "extract"),
        Map.entry("inline_method", "inline")
    );

    /** The current front door for a renamed/removed tool name, or {@code null}. */
    private static String aliasHint(String name) {
        String direct = RENAMED_TOOLS.get(name);
        if (direct != null) {
            return direct;
        }
        if (name.startsWith("analyze_")) {
            return "analyze";
        }
        if (name.startsWith("inspect_")) {
            return "inspect";
        }
        return null;
    }

    /**
     * Call a tool by name with the given arguments.
     *
     * @param name The tool name
     * @param arguments The tool arguments
     * @return The tool response
     * @throws ToolNotFoundException if the tool is not registered
     */
    public ToolResponse callTool(String name, JsonNode arguments) throws ToolNotFoundException {
        return callTool(name, arguments, "local");
    }

    /**
     * Sprint 26: the session-scoped entry — {@code sessionId} is minted by the
     * transport (per HTTP request header {@code Mcp-Session-Id}, or once per
     * stdio process) and keys the ledger + the learner event stream.
     */
    public ToolResponse callTool(String name, JsonNode arguments, String sessionId)
            throws ToolNotFoundException {
        Tool tool = tools.get(name);
        if (tool == null) {
            String hint = aliasHint(name);
            throw new ToolNotFoundException("Tool not found: " + name
                + (hint != null ? ". Did you mean '" + hint + "'?" : ""));
        }

        log.info("Executing tool: {}", name);
        long startTime = System.currentTimeMillis();

        // Sprint 21d: strict disk sync — reconcile external edits (agent, git, another
        // editor) BEFORE the tool computes anything, so every answer reflects the
        // CURRENT tree. Failure = WARN and proceed (availability over freshness on a
        // guard crash ONLY — never a switch; correctness is not configurable).
        java.util.List<String> syncDelta = java.util.List.of();
        if (diskSync != null) {
            try {
                StrictDiskSync.SyncReport sync = diskSync.syncBeforeCall();
                syncDelta = sync.refreshedPaths();
                if (sync.reconciled()) {
                    log.info("Strict disk sync before {}: {} new project(s), {} file(s) reconciled,"
                            + " {} project(s) built in {}ms",
                        name, sync.newProjects(), sync.refreshedFiles(), sync.builtProjects(),
                        sync.totalNanos() / 1_000_000);
                }
            } catch (Exception e) {
                log.warn("Strict disk sync FAILED before {} — answers may be STALE until the"
                    + " next successful reconcile", name, e);
            }
        }

        try {
            ToolResponse response = tool.execute(arguments);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Tool {} completed in {}ms, success={}", name, duration, response.isSuccess());
            // Sprint 21e (item A): a successful project mutation changes what symbol
            // anchors can resolve — refresh + backfill the store's anchors now (add:
            // new types become anchorable; remove: their auto-anchors get cleared).
            if (projectsMutatedHook != null && response.isSuccess()
                    && isProjectMutation(name, arguments)) {
                try {
                    projectsMutatedHook.run();
                } catch (Exception e) {
                    log.warn("Post-project-mutation experience hook failed after {}", name, e);
                }
            }
            // Sprint 23 (D6): remember files changed by MECHANICAL transforms —
            // the done-time coverage advisory exempts them (a rename needs no
            // new test; NEW BEHAVIOR does).
            if (response.isSuccess() && org.jawata.mcp.coverage.MechanicalChangeJournal
                    .EXEMPT_TOOLS.contains(name)
                    && response.getData() instanceof Map<?, ?> map
                    && map.get("filesModified") instanceof List<?> files) {
                files.forEach(f -> org.jawata.mcp.coverage.MechanicalChangeJournal
                    .recordMechanical(String.valueOf(f)));
            }
            // Sprint 22 (POST layer): central steering injection — every success
            // result names the next grounded step (see steeringFor).
            response.applySteering(steeringFor(name));
            // Sprint 26: the event tap — every outcome becomes a learner label
            // as a side effect of the call itself (D7: training is a side
            // effect of use). Tap failures are the tap's own concern (loud
            // there); they never fail the tool call.
            tap(sessionId, name, arguments, response);
            // Sprint 26 (D1): the automatic architect — the delta (hand edits
            // seen by the pre-call disk sync + files this call modified) runs
            // through the watch engine; NEW findings ride the answer. Never
            // on the quality tool itself (recursion) and never fatally.
            watch(sessionId, name, syncDelta, response);
            // Sprint 26 (D4/D5/D3): the server-side enforcement lane.
            if (serverChecks != null && eventTap != null) {
                try {
                    String block = serverChecks.onCall(sessionId, name, arguments,
                        response, eventTap.ledger());
                    if (block != null) {
                        response.appendSteering(block);
                    }
                } catch (Exception e) {
                    log.error("Server checks failed after {}", name, e);
                }
            }
            return response;
        } catch (Exception e) {
            log.error("Tool {} failed with exception", name, e);
            ToolResponse error = ToolResponse.internalError(e);
            tap(sessionId, name, arguments, error);
            return error;
        } catch (Error err) {
            // v2.7.1 (dogfood 2026-07-10): a JVM Error (StackOverflowError from a
            // pathological scan) escaped every catch(Exception), killed the
            // transport worker and the client saw a dropped socket. This is the
            // per-request boundary — answer structurally for ANY Throwable; the
            // request already failed, dying with it helps nobody.
            log.error("Tool {} failed with a JVM Error — returning a structured error instead of dropping the connection", name, err);
            ToolResponse error = ToolResponse.internalError(err);
            tap(sessionId, name, arguments, error);
            return error;
        }
    }

    /** Sprint 26 (D1): runs the watch engine over the call's delta. */
    private void watch(String sessionId, String name, java.util.List<String> syncDelta,
            ToolResponse response) {
        if (watchEngine == null || "find_quality_issue".equals(name)) {
            return;
        }
        try {
            java.util.List<String> delta = new java.util.ArrayList<>(syncDelta);
            if (response.getData() instanceof Map<?, ?> map
                    && map.get("filesModified") instanceof List<?> files) {
                files.forEach(f -> delta.add(String.valueOf(f)));
            }
            watchEngine.watch(sessionId, delta)
                .ifPresent(response::appendSteering);
        } catch (Exception e) {
            log.error("Watch engine failed after {} — findings for this delta were lost", name, e);
        }
    }

    /** Sprint 26 (D1): install the watch engine (application wiring). */
    public void setWatchEngine(org.jawata.mcp.learn.WatchEngine engine) {
        this.watchEngine = engine;
    }

    /** Sprint 26: forwards the outcome to the event tap; never fails the call. */
    private void tap(String sessionId, String name, JsonNode arguments, ToolResponse response) {
        if (eventTap == null) {
            return;
        }
        try {
            eventTap.onCall(sessionId, name, arguments, response);
        } catch (Exception e) {
            log.error("Event tap failed after {} — the label stream missed this outcome", name, e);
        }
    }

    /** Sprint 26: install the learner event tap (application wiring). */
    public void setEventTap(org.jawata.mcp.learn.EventTap tap) {
        this.eventTap = tap;
    }

    /**
     * Exception thrown when a tool is not found.
     */
    public static class ToolNotFoundException extends Exception {
        public ToolNotFoundException(String message) {
            super(message);
        }
    }
}
