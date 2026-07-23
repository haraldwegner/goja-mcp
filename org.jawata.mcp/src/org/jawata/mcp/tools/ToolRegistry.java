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

    /** Sprint 26a (D2): the weighted precedent push's retrieval seam (nullable). */
    private org.jawata.mcp.learn.PrecedentRetriever precedentRetriever;

    /** v3.3.1 (D2 enforcement): the surfaced negative precedents awaiting a written
     *  justification. Instantiated by DEFAULT — the signed D2 body says the steer is
     *  "enforced-by-default", so enforcement may not depend on optional wiring. */
    private org.jawata.mcp.learn.PrecedentLedger precedentLedger =
        new org.jawata.mcp.learn.PrecedentLedger();

    /** Sprint 27 (D6): measurement of what the steering surfaces actually do.
     *  NULL = not installed; the choke behaves identically either way, because a
     *  counter that changes what it counts measures nothing. */
    private org.jawata.mcp.knowledge.QualityLedger qualityLedger;

    /** Sprint 26a (D3b): the deterministic architect-involvement gate (nullable). */
    private org.jawata.mcp.learn.ArchitectGate architectGate;

    /** v3.2.1 (dogfood #1): supplier of the degraded-store notice — non-null
     *  return = the store is degraded and EVERY answer must say so. */
    private java.util.function.Supplier<String> storeNotice;

    /** v3.2.1: install the degraded-store notice supplier (application wiring). */
    public void setStoreNotice(java.util.function.Supplier<String> notice) {
        this.storeNotice = notice;
    }

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

        // v3.3.1 (D2 enforcement): LEVY the justification-cost. If a negative
        // precedent for this (tool, target) was already surfaced in this session,
        // using the tool anyway costs a written reason — refused BEFORE dispatch,
        // because the signed D2 body says the steer is "not an optional hint the
        // agent may ignore". v3.3.0 worded the cost but never charged it.
        ToolResponse unpaid = precedentCharge(sessionId, name, arguments);
        if (unpaid != null) {
            return unpaid;
        }
        // A paid override is a meta-argument of the CHOKE, not of the tool.
        arguments = withoutOverride(arguments);

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
            // Sprint 26a (D2): the WEIGHTED PRECEDENT push — for the thing this
            // call is working on, what did tools do in similar cases before?
            // Runs BEFORE the tap so it reflects PAST experience, not this call's
            // own outcome; appended, never replacing the tool's own line.
            precedent(sessionId, name, arguments, response);
            // Sprint 26: the event tap — every outcome becomes a learner label
            // as a side effect of the call itself (D7: training is a side
            // effect of use). Tap failures are the tap's own concern (loud
            // there); they never fail the tool call.
            tap(sessionId, name, arguments, response);
            // Sprint 26 (D1): the automatic architect — the delta (hand edits
            // seen by the pre-call disk sync + files this call modified) runs
            // through the watch engine; NEW findings ride the answer. Never
            // on the quality tool itself (recursion) and never fatally.
            boolean smellFound = watch(sessionId, name, syncDelta, response);
            // Sprint 26a (D3b): the architect-involvement gate — smell (above) |
            // signature/hierarchy | large edit → involve the architect.
            architectGate(name, arguments, response, smellFound);
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
            // v3.2.1 (dogfood #1): a degraded store announces itself on EVERY
            // answer — a degraded result presented as normal is the recorded
            // top-bug class; nobody should have to NOTICE a missing file size.
            if (storeNotice != null) {
                try {
                    String degraded = storeNotice.get();
                    if (degraded != null) {
                        response.appendSteering(degraded);
                    }
                } catch (Exception e) {
                    log.error("Store-notice supplier failed after {}", name, e);
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

    /**
     * Sprint 26a (D2): the weighted precedent push. Builds the current target,
     * retrieves past tool outcomes for it through the swappable
     * {@link org.jawata.mcp.learn.PrecedentRetriever}, and appends a weighted
     * steer (never replacing the tool's own line). No retriever, no target, or
     * no clear signal → silent. Never fails the call.
     */
    private void precedent(String sessionId, String name, JsonNode arguments,
            ToolResponse response) {
        if (precedentRetriever == null) {
            return;
        }
        try {
            String target = org.jawata.mcp.learn.ToolExperienceRecorder.target(name, arguments);
            if (target == null || target.isBlank()) {
                return;
            }
            java.util.List<org.jawata.mcp.learn.ToolExperience> hits =
                precedentRetriever.retrieve(target, 20);
            // Sprint 27 D3 — the two tiers. The retriever may now gather
            // meaning-near captures from OTHER targets; only IDENTITY hits
            // (situation contains THIS target) may warn and charge — that is
            // v3.3.1's behaviour, unchanged. Similar-but-not-identical cases
            // become ONE advisory line: they inform, they never arm the ledger.
            java.util.List<org.jawata.mcp.learn.ToolExperience> identity = new java.util.ArrayList<>();
            org.jawata.mcp.learn.ToolExperience similar = null;
            for (org.jawata.mcp.learn.ToolExperience h : hits) {
                if (org.jawata.mcp.learn.IdentityMatch.matches(h, target)) {
                    identity.add(h);
                } else if (similar == null) {
                    similar = h;               // capped top-1, the plan constant
                }
            }
            org.jawata.mcp.learn.PrecedentSteer.Verdict verdict =
                org.jawata.mcp.learn.PrecedentSteer.evaluate(name, identity);
            // Sprint 27a: both tiers count their ABSTAIN as well as their speak.
            // A tier that stayed silent and a tier never consulted must not look
            // identical — that is the exact gap QualityLedger.silent exists to
            // close, and until now the choke fired only on speak.
            if (verdict.steer() != null) {
                response.appendSteering(verdict.steer());
                measure(q -> q.fired(
                    org.jawata.mcp.knowledge.QualityLedger.SURFACE_CHOKE_PRECEDENT));
            } else {
                measure(q -> q.silent(
                    org.jawata.mcp.knowledge.QualityLedger.SURFACE_CHOKE_PRECEDENT));
            }
            if (similar != null) {
                measure(q -> q.fired(
                    org.jawata.mcp.knowledge.QualityLedger.SURFACE_CHOKE_ADVISORY));
                response.appendSteering("Similar past case (a DIFFERENT target — "
                    + "advisory only, judge the transfer): `" + similar.tool() + "` "
                    + ("compiled".equals(similar.outcome()) ? "worked" : similar.outcome())
                    + " in: " + similar.situation());
            } else {
                measure(q -> q.silent(
                    org.jawata.mcp.knowledge.QualityLedger.SURFACE_CHOKE_ADVISORY));
            }
            // v3.3.1: a NEGATIVE precedent was just SURFACED — remember it, so a
            // later use of that tool on this target owes the written justification
            // the steer just named. Charging a cost for a warning never shown
            // would be enforcement by ambush.
            if (verdict.warnedTool() != null && precedentLedger != null) {
                precedentLedger.warn(sessionId, verdict.warnedTool(), target);
                measure(org.jawata.mcp.knowledge.QualityLedger::warned);
            }
        } catch (Exception e) {
            log.error("Precedent push failed after {}", name, e);
        }
    }

    /**
     * v3.3.1 (D2 enforcement): the justification-cost, levied. Returns the refusal
     * when a SURFACED negative precedent for this {@code (tool, target)} is still
     * unpaid, else {@code null}.
     *
     * <p>Fails OPEN on any internal error: a broken charge must never block real
     * work. It also never charges for a warning that was not actually shown —
     * enforcement by ambush would be worse than no enforcement.</p>
     */
    private ToolResponse precedentCharge(String sessionId, String name, JsonNode arguments) {
        if (precedentLedger == null) {
            return null;
        }
        try {
            String target = org.jawata.mcp.learn.ToolExperienceRecorder.target(name, arguments);
            if (target == null || target.isBlank()
                    || !precedentLedger.isOutstanding(sessionId, name, target)) {
                return null;
            }
            String reason = justification(arguments);
            if (reason != null) {
                // Paid. The agent MAY defect from precedent — it may not defect
                // SILENTLY. Clear the charge so the reason is owed only once.
                precedentLedger.clear(sessionId, name, target);
                measure(org.jawata.mcp.knowledge.QualityLedger::defected);
                log.info("Precedent defection on {} for target {} justified: {}",
                    name, target, reason);
                return null;
            }
            return ToolResponse.error("PRECEDENT_UNJUSTIFIED",
                "`" + name + "` was reverted or errored on " + target + " in a case like this,"
                    + " and that precedent was already surfaced to you. Using it anyway costs"
                    + " a written justification.",
                "Prefer what worked before; or re-call with precedentOverride=\"<one line:"
                    + " why this case is different>\". The reason is logged with the call.");
        } catch (Exception e) {
            log.error("Precedent charge failed before {} — proceeding UNCHARGED", name, e);
            return null;
        }
    }

    /** The written justification carried by the call, or {@code null} when absent/blank. */
    private static String justification(JsonNode arguments) {
        if (arguments == null || !arguments.hasNonNull("precedentOverride")) {
            return null;
        }
        String reason = arguments.get("precedentOverride").asText("").trim();
        return reason.isEmpty() ? null : reason;
    }

    /** Strips the choke's meta-argument so no tool ever sees it in its own schema. */
    private static JsonNode withoutOverride(JsonNode arguments) {
        if (arguments instanceof com.fasterxml.jackson.databind.node.ObjectNode obj
                && obj.has("precedentOverride")) {
            com.fasterxml.jackson.databind.node.ObjectNode copy = obj.deepCopy();
            copy.remove("precedentOverride");
            return copy;
        }
        return arguments;
    }

    /** Sprint 26a D2: the swappable retrieval seam (Sprint 27 → embeddings). */
    public void setPrecedentRetriever(org.jawata.mcp.learn.PrecedentRetriever retriever) {
        this.precedentRetriever = retriever;
    }

    /** v3.3.1: install the precedent ledger (application wiring / tests). */
    public void setPrecedentLedger(org.jawata.mcp.learn.PrecedentLedger ledger) {
        this.precedentLedger = ledger;
    }

    /** Sprint 27 D6: install the quality ledger (application wiring / tests). */
    public void setQualityLedger(org.jawata.mcp.knowledge.QualityLedger ledger) {
        this.qualityLedger = ledger;
    }

    /** The ledger the choke warns into — the outcome-after join reads it. */
    public org.jawata.mcp.learn.PrecedentLedger precedentLedger() {
        return precedentLedger;
    }

    /**
     * Run a measurement if one is installed, and never let it reach the caller:
     * the choke's behaviour must be identical with and without counting.
     */
    private void measure(
            java.util.function.Consumer<org.jawata.mcp.knowledge.QualityLedger> m) {
        org.jawata.mcp.knowledge.QualityLedger q = qualityLedger;
        if (q == null) {
            return;
        }
        try {
            m.accept(q);
        } catch (RuntimeException e) {
            log.warn("quality measurement failed; the call itself is unaffected", e);
        }
    }

    /** Sprint 26 (D1): runs the watch engine over the call's delta. Returns
     *  whether a smell finding was produced — the D3b gate's smell trigger. */
    private boolean watch(String sessionId, String name, java.util.List<String> syncDelta,
            ToolResponse response) {
        if (watchEngine == null || "find_quality_issue".equals(name)) {
            return false;
        }
        try {
            java.util.List<String> delta = new java.util.ArrayList<>(syncDelta);
            if (response.getData() instanceof Map<?, ?> map
                    && map.get("filesModified") instanceof List<?> files) {
                files.forEach(f -> delta.add(String.valueOf(f)));
            }
            java.util.Optional<String> findings = watchEngine.watch(sessionId, delta);
            findings.ifPresent(response::appendSteering);
            return findings.isPresent();
        } catch (Exception e) {
            log.error("Watch engine failed after {} — findings for this delta were lost", name, e);
            return false;
        }
    }

    /**
     * Sprint 26a (D3b): the architect-involvement gate — the deterministic rule
     * (smell | signature/hierarchy | over the LoC threshold) that replaces the
     * retired edit-switch. Appends the review steer; a plain edit passes silent.
     * Never fails the call.
     */
    private void architectGate(String name, JsonNode arguments, ToolResponse response,
            boolean smellFound) {
        if (architectGate == null) {
            return;
        }
        try {
            String steer = architectGate.evaluate(name, arguments, response, smellFound);
            if (steer != null) {
                response.appendSteering(steer);
            }
        } catch (Exception e) {
            log.error("Architect gate failed after {}", name, e);
        }
    }

    /** Sprint 26a D3b: install the deterministic architect-involvement gate. */
    public void setArchitectGate(org.jawata.mcp.learn.ArchitectGate gate) {
        this.architectGate = gate;
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
