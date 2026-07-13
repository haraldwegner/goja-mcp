package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.DevSimPreset;
import org.jawata.mcp.runtime.JvmTargets;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.runtime.RuntimeSession;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.runtime.debug.DebugController;
import org.jawata.mcp.runtime.debug.JdiValues;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 24 — the {@code debug} front door: one tool, the whole interactive
 * debugger behind it (Stage 7 opens the session; Stages 8–11 add breakpoints,
 * inspection, mutation, probes and replay).
 *
 * <p><b>What this can do to a JVM.</b> A debug session can suspend threads and
 * change state in the program it attaches to. It is meant for a development or
 * simulation machine. Attaching it to anything else — a shared test environment,
 * or worse — is the operator's professional judgment, not a thing jawata gates:
 * production runs no agent and exposes no debug channel, so the dangerous action
 * is not refused, it is unreachable. (D17 states this in the README too.)</p>
 */
public class DebugTool extends AbstractTool {

    private static final List<String> ACTIONS = List.of(
        "discover", "launch", "attach", "status", "detach", "cancel",
        "breakpoint_set", "breakpoint_clear", "breakpoint_list",
        "wait", "threads", "snapshot", "evaluate", "step", "resume", "instances",
        "set_value", "force_return", "pop_frame", "redefine", "mutations",
        "probe_set", "probe_clear", "probe_list", "probe_read",
        "replay", "artifacts", "artifact_delete");

    private static final List<String> PROBE_KINDS =
        List.of("field_watch", "method_trace", "logpoint");

    private static final List<String> BREAKPOINT_KINDS = List.of(
        "line", "method", "conditional", "hit_count", "exception",
        "field_access", "field_write");

    /**
     * How long {@code wait} may hold the call open.
     *
     * <p><b>Blocking IS the wait primitive.</b> An agent is only ever driven by tool
     * results: nothing an MCP server pushes can re-invoke an agent that is not already
     * in a turn. So "notify me when it hits" cannot exist — the call that waits is the
     * call that returns the hit.</p>
     *
     * <p>The ceiling is set by the transport's idle window, not by us: an HTTP/SSE MCP
     * server may hold a call open for about five minutes before the client aborts it as
     * idle (stdio allows far longer). Four minutes leaves headroom. A wait that needs
     * longer simply calls again — <b>and loses nothing</b>, because the breakpoint keeps
     * the thread suspended until somebody resumes it. The program sits there waiting for
     * you, which is precisely what a notification could not promise.</p>
     */
    private static final long MAX_WAIT_MILLIS = 240_000;
    private static final long DEFAULT_WAIT_MILLIS = 30_000;

    private final RuntimeSessionRegistry sessions;
    private final RuntimeArtifactStore artifacts;

    public DebugTool(Supplier<IJdtService> serviceSupplier, RuntimeSessionRegistry sessions) {
        this(serviceSupplier, sessions, new RuntimeArtifactStore());
    }

    public DebugTool(Supplier<IJdtService> serviceSupplier, RuntimeSessionRegistry sessions,
                     RuntimeArtifactStore artifacts) {
        super(serviceSupplier);
        this.sessions = sessions;
        this.artifacts = artifacts;
    }

    @Override
    public String getName() {
        return "debug";
    }

    @Override
    public String getDescription() {
        return """
            Interactive debugging of a local JVM — attach to a running program, or
            launch one, and work with it live.

            USAGE: debug(action="<action>", ...)

            - discover — the local JVMs, each flagged `debuggable` or not. A JVM that
                         started WITHOUT a debug agent can never be given one, so it can
                         never be attached to — that is a fact of the JVM, not a policy.
            - attach   — attach to one. Needs: pid. Refuses, with the reason, if the
                         target was not started debuggable. Returns a sessionId and what
                         the JVM can actually do.
            - launch   — start a JVM under the dev/sim preset, attached and HELD BEFORE ITS
                         FIRST INSTRUCTION. Arm breakpoints, then action=resume to start
                         the program — otherwise it has already run past what you wanted
                         to see. Needs: mainClass + classpath. Optional: args, jvmArgs,
                         workingDirectory.
            - status   — one session, or all of them (omit sessionId).
            - detach   — end a session. A JVM you LAUNCHED is killed; a JVM you
                         ATTACHED to is released and keeps running — it was never
                         yours.
            - cancel   — alias for detach.

            BREAKPOINTS (all need sessionId):
            - breakpoint_set   — kind = line | method | conditional | hit_count |
                                 exception | field_access | field_write.
                                 Needs className; plus line (line/conditional/hit_count),
                                 method (method), field (the two field kinds),
                                 condition (conditional), hitCount (hit_count).
                                 A class that is not loaded YET does not fail — the
                                 breakpoint is deferred and reports `bound: false` with
                                 the reason, and binds the moment the class loads.
            - breakpoint_list / breakpoint_clear — as they read.

            STOPPING AND LOOKING:
            - wait     — BLOCK until the next breakpoint hit, and return it. Returns the
                         instant it fires; if nothing fires, waits up to timeoutMillis
                         (default 30s, max 240s) and then answers "nothing yet" — which is
                         an answer, not an error.

                         NOTHING IS EVER MISSED. A hit SUSPENDS the thread and leaves it
                         suspended: the program stops and waits for you, indefinitely. So
                         a wait that times out loses nothing — call again and the hit is
                         still there. (This is why no push notification is needed, and why
                         one would not help: an agent only ever runs on tool results.)

                         WAITING FOR SOMETHING RARE — a condition that takes an hour, a
                         bug that shows up on the ten-thousandth message? Hand the session
                         to a SUBAGENT: it arms the breakpoints, starts the program, and
                         loops `wait` in its own context while the main loop does other
                         work. The sessionId is the whole handle it needs.
            - threads  — every thread and what it is doing. A RUNNING thread reports NO
                         stack and says why: a stack read from a running thread is a
                         race, not a reading.
            - snapshot — one SUSPENDED thread: stack, and one frame's arguments, locals
                         and `this`, expanded to bounds you set (depth / maxItems /
                         maxBytes). Every bound that bites is reported on the node it
                         truncated — a silently truncated object graph reads like a
                         complete one.
            - evaluate — a Java expression in a suspended frame, INCLUDING calling
                         methods on the target's own objects. An expression form this
                         evaluator does not implement is refused BY NAME rather than
                         quietly evaluated as something else.
            - instances— live instances of a type, paged. Past the enumeration cap the
                         exact count comes from the JVM's heap histogram — a cap
                         reported as a count would be a lie with a number on it.

            MOVING ON:
            - step     — mode = in | over | out | to_line (to_line needs className+line).
                         The thread runs; poll `wait` for where it lands.
            - resume   — one thread (threadId), or every suspended thread.

            TESTING A HYPOTHESIS — changing the running program to see what happens:
            - set_value    — overwrite a local or a field. Needs threadId, name, expression
                             ("what if this were 0?").
            - force_return — abandon the current method and return a value to its caller NOW.
                             The rest of the method does not run — side effects included.
            - pop_frame    — put the thread back at the call site, about to make the call
                             again. Rewinds the STACK, not the world: side effects the frame
                             already had are still done.
            - redefine     — replace a class's bytecode (needs className + classFile). Method
                             BODIES only — adding or removing a method or field is refused,
                             plainly. Methods already on the stack keep their old code until
                             re-entered.
            - mutations    — everything this session changed, in order.

            WATCHING WITHOUT STOPPING — probes (this is what you use on a live simulation,
            where suspending the world is exactly what you cannot do):
            - probe_set   — kind = field_watch (every read and write of a field) |
                            method_trace (entry and exit, with the RETURN VALUE) |
                            logpoint (a line, optionally capturing expressions).
                            The program KEEPS RUNNING at full speed. Optional budget
                            (default 1000 events), after which the probe stops itself and
                            says so — a probe on a hot path would otherwise stream forever.
            - probe_read  — the values it has streamed. They also land in `hitStream`, so a
                            file monitor gets them as they happen.
            - probe_list / probe_clear — as they read.

            THE ONE HONEST CATCH: a JDI event carries only what the JVM puts in it. A field's
            value and a method's return value are FREE — they ride in the event, and nothing
            stops. But LOCALS can only be read from a stopped thread, so a logpoint with
            `capture` DOES stop the thread — briefly, resuming itself at once. Such a probe
            reports `perturbs: true`. If you are hunting a race, that matters: use
            field_watch or method_trace, which never stop anything.

            ONCE YOU MUTATE, IT IS NOT THE SAME PROGRAM. A conclusion drawn after a mutation
            is a conclusion about a program you edited. `mutations` is how you (and whoever
            reads your report) can tell which is which — so use it, and say what you changed.

            REPLAY WITH AN INVARIANT — the one that finds the bug for you:
            - replay — launch a program, declare what must ALWAYS be true, and stop at the
                       FIRST moment it is not. Needs: mainClass, classpath, invariant (a
                       boolean Java expression), className + line (where to check it).
                       Optional: capture (expressions to record), args, timeoutMillis.

                       The invariant is armed as its own negation, so the program runs at
                       speed and stops only when it is actually broken. What comes back is
                       the FIRST violation — stack, locals, and your captured expressions —
                       stored as an artifact with its provenance. The thread is left
                       SUSPENDED right there, so you can snapshot, evaluate and step from
                       the exact moment things went wrong, not from the wreckage afterwards.

                       If the invariant HELD for the whole run, it says so — which is an
                       answer, not a failure.

            ARTIFACTS: artifacts / artifact_delete — what a session left behind (replay
            captures, dumps), with provenance and an expiry. Explicit delete, because
            these get large.

            CLOSING THE LOOP: a hit names the class and the method, and that IS the key the
            static tools take — symbol="com.example.Foo#bar" goes straight into
            get_call_hierarchy / find_references / analyze. Never search for something the
            running program has just told you.

            ────────────────────────────────────────────────────────────────────────────
            WHAT THIS DOES TO THE TARGET — read this once.

            1. A debug session SUSPENDS THREADS AND CHANGES STATE in the JVM it is attached
               to. Breakpoints stop it; set_value, force_return, pop_frame and redefine
               rewrite it. This is not observation — it is intervention.

            2. It is meant for a DEVELOPMENT OR SIMULATION machine. That is not a
               disclaimer, it is the design: a JVM can only be debugged if it was STARTED
               with a debug agent, and one cannot be added later. A production JVM that was
               not launched for debugging is not protected by policy — it is unreachable.

            3. Where a JVM *is* debuggable — a test or staging box, say — pointing this at
               it is YOUR PROFESSIONAL JUDGMENT, and jawata will not second-guess it. If
               you do not know what suspending that JVM would do, that is the answer.
            ────────────────────────────────────────────────────────────────────────────

            The dev/sim launch preset is one host-controlled switch that prepares a
            JVM for the whole toolkit: loopback debug port, continuous bounded flight
            recording, local JMX, native-memory tracking, profiler readiness, and a
            quiet console.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", ACTIONS);
        action.put("description", "Which debug action to run.");
        properties.put("action", action);

        properties.put("pid", Map.of("type", "integer",
            "description", "attach: the JVM to attach to (from action=discover)."));
        properties.put("sessionId", Map.of("type", "string",
            "description", "status/detach/cancel: the session handle."));
        properties.put("mainClass", Map.of("type", "string",
            "description", "launch: the class to run."));
        properties.put("classpath", Map.of("type", "string",
            "description", "launch: the target's classpath."));
        properties.put("args", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "launch: program arguments."));
        properties.put("jvmArgs", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "launch: extra JVM flags, ON TOP of the dev/sim preset."));
        properties.put("workingDirectory", Map.of("type", "string",
            "description", "launch: the target's working directory."));

        properties.put("kind", Map.of("type", "string", "enum", BREAKPOINT_KINDS,
            "description", "breakpoint_set: which kind of breakpoint."));
        properties.put("className", Map.of("type", "string",
            "description", "breakpoint_set / step(to_line) / instances: the fully-qualified "
                + "class (a nested class is Outer$Inner). For kind=exception, the exception "
                + "class."));
        properties.put("line", Map.of("type", "integer",
            "description", "breakpoint_set (line/conditional/hit_count) and step(to_line): "
                + "the source line."));
        properties.put("method", Map.of("type", "string",
            "description", "breakpoint_set kind=method: the method to break on entry of."));
        properties.put("field", Map.of("type", "string",
            "description", "breakpoint_set kind=field_access|field_write: the field to watch."));
        properties.put("condition", Map.of("type", "string",
            "description", "breakpoint_set kind=conditional: a boolean Java expression, "
                + "evaluated in the frame that hit. A condition that cannot be evaluated "
                + "STOPS and reports the error — it is never silently treated as false."));
        properties.put("hitCount", Map.of("type", "integer",
            "description", "breakpoint_set kind=hit_count: stop on the Nth occurrence."));
        properties.put("breakpointId", Map.of("type", "string",
            "description", "breakpoint_clear: which breakpoint."));
        properties.put("threadId", Map.of("type", "integer",
            "description", "snapshot/evaluate/step/resume: the suspended thread (from a hit "
                + "or from action=threads). resume: omit to resume every suspended thread."));
        properties.put("frameIndex", Map.of("type", "integer",
            "description", "snapshot/evaluate: which stack frame (0 = the top, where it "
                + "stopped). Default 0."));
        properties.put("expression", Map.of("type", "string",
            "description", "evaluate: the Java expression to evaluate in that frame."));
        properties.put("mode", Map.of("type", "string",
            "enum", List.of("in", "over", "out", "to_line"),
            "description", "step: which step."));
        properties.put("timeoutMillis", Map.of("type", "integer",
            "description", "wait: how long to BLOCK waiting for a hit (default 30000, max "
                + "240000). A timeout is a normal answer — 'nothing yet' — not an error, and "
                + "nothing is lost: the hit keeps the thread suspended until you collect it. "
                + "replay: how long to run before giving up (default 60000); there, 'no "
                + "violation yet, still running' is NOT the same answer as 'the invariant "
                + "held', and the response says which it is."));
        properties.put("depth", Map.of("type", "integer",
            "description", "snapshot/instances: how deep to expand objects (default 3)."));
        properties.put("maxItems", Map.of("type", "integer",
            "description", "snapshot: array elements / fields per level (default 20)."));
        properties.put("maxBytes", Map.of("type", "integer",
            "description", "snapshot: string clip length (default 4096)."));
        properties.put("offset", Map.of("type", "integer",
            "description", "instances: page offset (default 0)."));
        properties.put("limit", Map.of("type", "integer",
            "description", "instances: page size (default 10, max 50)."));
        properties.put("artifactId", Map.of("type", "string",
            "description", "artifact_delete: which artifact to remove."));
        properties.put("name", Map.of("type", "string",
            "description", "set_value: the local or field to overwrite."));
        properties.put("classFile", Map.of("type", "string",
            "description", "redefine: path to the compiled .class with the new method bodies."));
        properties.put("probeId", Map.of("type", "string",
            "description", "probe_clear / probe_read: which probe."));
        properties.put("capture", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "probe_set kind=logpoint: expressions to evaluate at each pass. "
                + "NOTE: capturing reads a frame, which STOPS the thread briefly — the probe "
                + "reports perturbs:true. Without capture, nothing is stopped."));
        properties.put("invariant", Map.of("type", "string",
            "description", "replay: a boolean Java expression that must ALWAYS hold (e.g. "
                + "\"balance >= 0\"). Armed as its own negation, so the program runs at speed "
                + "and stops only where it is actually broken."));
        properties.put("budget", Map.of("type", "integer",
            "description", "probe_set: stop after this many events (default 1000). The probe "
                + "then disables itself and says so — what you have is the first N, not all."));

        schema.put("properties", properties);
        schema.put("required", List.of("action"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String action = getStringParam(arguments, "action");
        if (action == null || action.isBlank()) {
            return ToolResponse.invalidParameter("action", "action is required; one of " + ACTIONS);
        }
        try {
            return switch (action) {
                case "discover" -> discover();
                case "attach" -> attach(arguments);
                case "launch" -> launch(arguments);
                case "status" -> status(arguments);
                case "detach", "cancel" -> detach(arguments);
                case "breakpoint_set" -> breakpointSet(arguments);
                case "breakpoint_clear" -> breakpointClear(arguments);
                case "breakpoint_list" -> breakpointList(arguments);
                case "wait" -> awaitHit(arguments);
                case "threads" -> threads(arguments);
                case "snapshot" -> snapshot(arguments);
                case "evaluate" -> evaluate(arguments);
                case "step" -> step(arguments);
                case "resume" -> resume(arguments);
                case "instances" -> instances(arguments);
                case "set_value" -> setValue(arguments);
                case "force_return" -> forceReturn(arguments);
                case "pop_frame" -> popFrame(arguments);
                case "redefine" -> redefine(arguments);
                case "mutations" -> mutations(arguments);
                case "probe_set" -> probeSet(arguments);
                case "probe_clear" -> probeClear(arguments);
                case "probe_list" -> probeList(arguments);
                case "probe_read" -> probeRead(arguments);
                case "replay" -> replay(arguments);
                case "artifacts" -> listArtifacts();
                case "artifact_delete" -> deleteArtifact(arguments);
                default -> ToolResponse.invalidParameter("action",
                    "Unknown action '" + action + "'. Allowed: " + ACTIONS);
            };
        } catch (DebugController.DebugException e) {
            // A debugger's "no" is a RESULT — the caller acts on the code, not on a
            // stack trace. THREAD_NOT_SUSPENDED, CAPABILITY_ABSENT and the rest are
            // facts about the target, not failures of ours.
            return ToolResponse.error(e.code, e.getMessage(), hintFor(e.code));
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private static String hintFor(String code) {
        return switch (code) {
            case "THREAD_NOT_SUSPENDED" -> "Set a breakpoint, then debug(action=wait) for a "
                + "hit. The hit names the thread you can then read.";
            case "CAPABILITY_ABSENT" -> "The capability report on the session (action=status) "
                + "says what this JVM can actually do.";
            case "SESSION_TARGET_GONE" -> "The target JVM is no longer there. Launch or "
                + "attach again.";
            case "EVALUATION_AMBIGUOUS_OVERLOAD" -> "Cast an argument to pick the overload.";
            case "EVALUATION_UNSUPPORTED" -> "Rewrite the expression using the supported "
                + "forms, or read the value with action=snapshot instead.";
            case "BREAKPOINT_NO_CODE_AT_LINE" -> "Pick a line with a statement on it; "
                + "jawata's analyze/inspect tools give you the exact lines of a method.";
            default -> null;
        };
    }

    /** Every interactive action needs a live session — resolved once, honestly. */
    private DebugController debuggerOf(JsonNode arguments) throws DebugController.DebugException {
        String sessionId = getStringParam(arguments, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new DebugController.DebugException("SESSION_REQUIRED",
                "This action needs a sessionId (from debug(action=launch|attach)).");
        }
        RuntimeSession session = sessions.get(sessionId)
            .orElseThrow(() -> new DebugController.DebugException("SESSION_UNKNOWN",
                "No debug session '" + sessionId + "'. Open one with "
                    + "debug(action=launch) or debug(action=attach)."));
        return debuggerOf(session, getStringParam(arguments, "projectKey"));
    }

    /** Every session journals its hits, from the first one — see {@link #hitStreamOf}. */
    private DebugController debuggerOf(RuntimeSession session) {
        return debuggerOf(session, null);
    }

    private DebugController debuggerOf(RuntimeSession session, String projectKey) {
        DebugController debugger = session.debugger();
        debugger.journalHitsTo(hitStreamOf(session));

        // Stamp every event with where it came from. A hit that says
        // {class, method, projectKey} can be handed STRAIGHT to
        // get_call_hierarchy(symbol="Class#method") — no search, no guessing the workspace.
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sessionId", session.id);
        if (projectKey != null && !projectKey.isBlank()) {
            context.put("projectKey", projectKey);
        }
        debugger.setContext(context);
        return debugger;
    }

    private Path hitStreamOf(RuntimeSession session) {
        return artifacts.root().resolve("sessions").resolve(session.id).resolve("hits.jsonl");
    }

    private ToolResponse breakpointSet(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String kind = getStringParam(arguments, "kind");
        if (kind == null || !BREAKPOINT_KINDS.contains(kind)) {
            return ToolResponse.invalidParameter("kind",
                "breakpoint_set needs a kind: " + BREAKPOINT_KINDS);
        }
        String className = getStringParam(arguments, "className");
        if (className == null || className.isBlank()) {
            return ToolResponse.invalidParameter("className",
                "breakpoint_set needs the fully-qualified class (Outer$Inner for a nested one).");
        }
        Integer line = optionalInt(arguments, "line");
        Integer hitCount = optionalInt(arguments, "hitCount");

        Map<String, Object> breakpoint = debugger.setBreakpoint(kind, className, line,
            getStringParam(arguments, "method"), getStringParam(arguments, "field"),
            getStringParam(arguments, "condition"), hitCount);

        return ToolResponse.success(breakpoint, ResponseMeta.builder()
            .steering(Boolean.TRUE.equals(breakpoint.get("bound"))
                ? "Armed. debug(action=wait) blocks up to timeoutMillis for the hit."
                : "DEFERRED, not failed — the class is not loaded yet. It will bind when it "
                    + "loads; breakpoint_list shows when 'bound' flips to true.")
            .build());
    }

    private ToolResponse breakpointClear(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String breakpointId = getStringParam(arguments, "breakpointId");
        if (breakpointId == null || breakpointId.isBlank()) {
            return ToolResponse.invalidParameter("breakpointId",
                "breakpoint_clear needs a breakpointId (see breakpoint_list).");
        }
        boolean cleared = debugger.clearBreakpoint(breakpointId);
        if (!cleared) {
            return ToolResponse.symbolNotFound("No breakpoint '" + breakpointId + "'.");
        }
        return ToolResponse.success(Map.of("breakpointId", breakpointId, "cleared", true),
            ResponseMeta.builder().build());
    }

    private ToolResponse breakpointList(JsonNode arguments) throws Exception {
        List<Map<String, Object>> breakpoints = debuggerOf(arguments).listBreakpoints();
        return ToolResponse.success(
            Map.of("breakpoints", breakpoints, "count", breakpoints.size()),
            ResponseMeta.builder().returnedCount(breakpoints.size()).build());
    }

    private ToolResponse awaitHit(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long timeout = Math.min(MAX_WAIT_MILLIS,
            Math.max(0, getIntParam(arguments, "timeoutMillis", (int) DEFAULT_WAIT_MILLIS)));
        Optional<Map<String, Object>> hit = debugger.awaitHit(timeout);

        if (hit.isEmpty()) {
            // Not an error. Nothing has hit yet, and saying so is the honest answer.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hit", false);
            data.put("waitedMillis", timeout);
            data.put("breakpointsArmed", debugger.listBreakpoints().size());
            data.put("targetAlive", debugger.isLive());
            data.put("nothingLost", true);
            return ToolResponse.success(data, ResponseMeta.builder()
                .steering("Nothing hit in that window — and nothing was lost: a hit suspends "
                    + "the thread and holds it, so calling wait again picks it up whenever it "
                    + "comes. If the condition is rare, hand this sessionId to a subagent and "
                    + "let it wait. If it should have hit by now, check breakpoint_list — one "
                    + "showing bound:false has not armed yet.")
                .build());
        }
        Map<String, Object> data = new LinkedHashMap<>(hit.get());
        data.put("hit", true);
        data.put("morePending", debugger.pendingHits());

        // THE LOOP CLOSES HERE. The hit already names the class and the method — which is
        // exactly the key every static tool takes. No search is needed, and none should be
        // run: a search would only re-derive what the running program just told us.
        String symbol = data.get("class") + "#" + data.get("method");
        data.put("symbol", symbol);

        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("The thread is SUSPENDED here. Read it with action=snapshot (threadId "
                + "above), then step or resume; every other thread is still running. AND YOU "
                + "ALREADY HAVE THE KEY: symbol=\"" + symbol + "\" goes straight into "
                + "get_call_hierarchy / find_references / analyze — do NOT search for it, the "
                + "running program has just told you exactly where it is.")
            .build());
    }

    private ToolResponse threads(JsonNode arguments) throws Exception {
        return ToolResponse.success(debuggerOf(arguments).threads(),
            ResponseMeta.builder().build());
    }

    private ToolResponse snapshot(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        if (threadId < 0) {
            return ToolResponse.invalidParameter("threadId",
                "snapshot needs the threadId of a SUSPENDED thread (a hit names one; "
                    + "action=threads lists them).");
        }
        Map<String, Object> snapshot = debugger.snapshot(threadId,
            getIntParam(arguments, "frameIndex", 0),
            getIntParam(arguments, "depth", JdiValues.DEFAULT_DEPTH),
            getIntParam(arguments, "maxItems", JdiValues.DEFAULT_MAX_ITEMS),
            getIntParam(arguments, "maxBytes", JdiValues.DEFAULT_MAX_BYTES));
        return ToolResponse.success(snapshot, ResponseMeta.builder()
            .steering("Anything marked truncated was cut by a bound you can raise "
                + "(depth / maxItems / maxBytes) — it is not the end of the object.")
            .build());
    }

    private ToolResponse evaluate(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        String expression = getStringParam(arguments, "expression");
        if (threadId < 0) {
            return ToolResponse.invalidParameter("threadId",
                "evaluate needs the threadId of a SUSPENDED thread.");
        }
        if (expression == null || expression.isBlank()) {
            return ToolResponse.invalidParameter("expression",
                "evaluate needs a Java expression.");
        }
        Map<String, Object> evaluated = debugger.evaluate(threadId,
            getIntParam(arguments, "frameIndex", 0), expression);
        return ToolResponse.success(evaluated, ResponseMeta.builder()
            .steering("Evaluated IN the target JVM — a method call here really ran there.")
            .build());
    }

    private ToolResponse step(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        if (threadId < 0) {
            return ToolResponse.invalidParameter("threadId",
                "step needs the threadId of a SUSPENDED thread.");
        }
        String mode = getStringParam(arguments, "mode");
        if (mode == null || mode.isBlank()) {
            return ToolResponse.invalidParameter("mode", "step needs a mode: in, over, out, "
                + "or to_line.");
        }
        Map<String, Object> stepped;
        if ("to_line".equals(mode)) {
            String className = getStringParam(arguments, "className");
            Integer line = optionalInt(arguments, "line");
            if (className == null || line == null) {
                return ToolResponse.invalidParameter("className",
                    "step(mode=to_line) needs className and line.");
            }
            stepped = debugger.stepToLine(threadId, className, line);
        } else {
            stepped = debugger.step(threadId, mode);
        }
        return ToolResponse.success(stepped, ResponseMeta.builder()
            .steering("Poll debug(action=wait) for where the thread lands.")
            .build());
    }

    private ToolResponse resume(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        return ToolResponse.success(debugger.resume(threadId < 0 ? null : threadId),
            ResponseMeta.builder().build());
    }

    private ToolResponse instances(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String className = getStringParam(arguments, "className");
        if (className == null || className.isBlank()) {
            return ToolResponse.invalidParameter("className",
                "instances needs a fully-qualified class (Outer$Inner for a nested one).");
        }
        Map<String, Object> found = debugger.instances(className,
            getIntParam(arguments, "offset", 0),
            getIntParam(arguments, "limit", 10),
            getIntParam(arguments, "depth", 2));
        return ToolResponse.success(found, ResponseMeta.builder()
            .steering("'countSource' says how the count was obtained — a live enumeration is "
                + "exact up to the cap; past it the exact number comes from the heap "
                + "histogram. A floor is labelled as one.")
            .build());
    }

    private ToolResponse setValue(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        String name = getStringParam(arguments, "name");
        String expression = getStringParam(arguments, "expression");
        if (threadId < 0 || name == null || expression == null) {
            return ToolResponse.invalidParameter("name",
                "set_value needs threadId, name (a local or field), and expression (the new "
                    + "value).");
        }
        Map<String, Object> mutation = debugger.setValue(threadId,
            getIntParam(arguments, "frameIndex", 0), name, expression);
        return ToolResponse.success(mutation, ResponseMeta.builder()
            .steering("The program has been CHANGED. Whatever it does from here, it does as an "
                + "edited program — say so when you report what you found. "
                + "debug(action=mutations) lists everything this session changed.")
            .build());
    }

    private ToolResponse forceReturn(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        String expression = getStringParam(arguments, "expression");
        if (threadId < 0 || expression == null) {
            return ToolResponse.invalidParameter("expression",
                "force_return needs threadId and expression (the value to return).");
        }
        return ToolResponse.success(debugger.forceReturn(threadId, expression),
            ResponseMeta.builder()
                .steering("The rest of that method did NOT run. The thread is suspended in the "
                    + "caller — snapshot it to see what the caller now believes.")
                .build());
    }

    private ToolResponse popFrame(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        long threadId = longParam(arguments, "threadId", -1);
        if (threadId < 0) {
            return ToolResponse.invalidParameter("threadId",
                "pop_frame needs the threadId of a SUSPENDED thread.");
        }
        return ToolResponse.success(
            debugger.popFrame(threadId, getIntParam(arguments, "frameIndex", 0)),
            ResponseMeta.builder()
                .steering("The stack is rewound — the world is not. Anything that frame already "
                    + "wrote, sent or printed has still happened.")
                .build());
    }

    private ToolResponse redefine(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String className = getStringParam(arguments, "className");
        String classFile = getStringParam(arguments, "classFile");
        if (className == null || classFile == null) {
            return ToolResponse.invalidParameter("classFile",
                "redefine needs className and classFile (a compiled .class holding the new "
                    + "method bodies).");
        }
        Path file = Path.of(classFile);
        if (!Files.isRegularFile(file)) {
            return ToolResponse.invalidParameter("classFile",
                "No such .class file: " + classFile);
        }
        return ToolResponse.success(debugger.redefine(className, Files.readAllBytes(file)),
            ResponseMeta.builder()
                .steering("Methods already on the stack keep their OLD code until they are "
                    + "re-entered — so the change shows up on the next call, not this one.")
                .build());
    }

    private ToolResponse mutations(JsonNode arguments) throws Exception {
        List<Map<String, Object>> changed = debuggerOf(arguments).mutations();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mutations", changed);
        data.put("count", changed.size());
        data.put("programIsUnmodified", changed.isEmpty());
        return ToolResponse.success(data, ResponseMeta.builder()
            .returnedCount(changed.size())
            .steering(changed.isEmpty()
                ? "Nothing has been changed — what this program does, it does on its own."
                : "This program has been EDITED " + changed.size() + " time(s). Any finding "
                    + "from here on describes the edited program; report it that way.")
            .build());
    }

    private ToolResponse probeSet(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String kind = getStringParam(arguments, "kind");
        if (kind == null || !PROBE_KINDS.contains(kind)) {
            return ToolResponse.invalidParameter("kind", "probe_set needs a kind: " + PROBE_KINDS);
        }
        String className = getStringParam(arguments, "className");
        if (className == null || className.isBlank()) {
            return ToolResponse.invalidParameter("className",
                "probe_set needs the fully-qualified class to watch.");
        }
        Map<String, Object> probe = debugger.setProbe(kind, className,
            optionalInt(arguments, "line"), getStringParam(arguments, "method"),
            getStringParam(arguments, "field"), stringList(arguments, "capture"),
            optionalInt(arguments, "budget"));

        return ToolResponse.success(probe, ResponseMeta.builder()
            .steering(Boolean.TRUE.equals(probe.get("perturbs"))
                ? "NOTE: this probe captures expressions, which means reading a frame, which "
                    + "means STOPPING the thread — briefly, resuming itself at once, but it "
                    + "perturbs. If you are hunting a race, prefer field_watch or "
                    + "method_trace: they read what the event already carries and stop nothing."
                : "The program keeps running at full speed — nothing is suspended. Read the "
                    + "values with probe_read, or watch `hitStream` and be notified as they "
                    + "stream.")
            .build());
    }

    private ToolResponse probeClear(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String probeId = getStringParam(arguments, "probeId");
        if (probeId == null || probeId.isBlank()) {
            return ToolResponse.invalidParameter("probeId", "probe_clear needs a probeId.");
        }
        if (!debugger.clearProbe(probeId)) {
            return ToolResponse.symbolNotFound("No probe '" + probeId + "'.");
        }
        return ToolResponse.success(Map.of("probeId", probeId, "cleared", true),
            ResponseMeta.builder().build());
    }

    private ToolResponse probeList(JsonNode arguments) throws Exception {
        List<Map<String, Object>> all = debuggerOf(arguments).listProbes();
        return ToolResponse.success(Map.of("probes", all, "count", all.size()),
            ResponseMeta.builder().returnedCount(all.size()).build());
    }

    private ToolResponse probeRead(JsonNode arguments) throws Exception {
        DebugController debugger = debuggerOf(arguments);
        String probeId = getStringParam(arguments, "probeId");
        List<Map<String, Object>> events = debugger.probeEvents(probeId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("events", events);
        data.put("returned", events.size());
        // Say what this window IS. The probe may have seen far more than we kept.
        debugger.listProbes().stream()
            .filter(p -> probeId == null || probeId.equals(p.get("probeId")))
            .findFirst()
            .ifPresent(p -> {
                data.put("totalSeen", p.get("events"));
                if (Boolean.TRUE.equals(p.get("stopped"))) {
                    data.put("probeStopped", p.get("stoppedReason"));
                }
            });
        return ToolResponse.success(data, ResponseMeta.builder()
            .returnedCount(events.size())
            .steering("`totalSeen` is how many events the probe saw; `returned` is how many we "
                + "kept. If they differ, this is a WINDOW on the stream, not the stream.")
            .build());
    }

    /**
     * Launch a replay, declare an invariant, and capture the FIRST moment it breaks.
     *
     * <p>The invariant is armed as its own negation on a conditional breakpoint: the program
     * runs at speed and stops only where it is actually broken. That is the difference
     * between "here is a log of everything that happened" and "here is the moment it went
     * wrong, with the program still standing in it".</p>
     */
    private ToolResponse replay(JsonNode arguments) throws Exception {
        String mainClass = getStringParam(arguments, "mainClass");
        String classpath = getStringParam(arguments, "classpath");
        String invariant = getStringParam(arguments, "invariant");
        String className = getStringParam(arguments, "className");
        Integer line = optionalInt(arguments, "line");

        if (mainClass == null || classpath == null || invariant == null
                || className == null || line == null) {
            return ToolResponse.invalidParameter("invariant",
                "replay needs mainClass, classpath, invariant (a boolean expression), and "
                    + "className + line (where to check it).");
        }

        List<String> command = new ArrayList<>(List.of("-cp", classpath, mainClass));
        command.addAll(stringList(arguments, "args"));
        RuntimeSession session = sessions.launch(command, null, List.of());
        DebugController debugger = debuggerOf(session);

        // Armed as the NEGATION: we do not want every event, we want the broken one.
        Map<String, Object> armed = debugger.setBreakpoint("conditional", className, line,
            null, null, "!(" + invariant + ")", null);

        debugger.resume(null);   // start the held program

        long timeout = Math.min(MAX_WAIT_MILLIS,
            Math.max(1_000, getIntParam(arguments, "timeoutMillis", 60_000)));
        Optional<Map<String, Object>> violation = debugger.awaitHit(timeout);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.id);
        result.put("invariant", invariant);
        result.put("checkedAt", className + ":" + line);

        if (violation.isEmpty()) {
            // The invariant HELD (or the run did not reach a violation in time). Both are
            // answers — and they are DIFFERENT answers, so we do not merge them.
            boolean ended = !debugger.isLive();
            result.put("violated", false);
            result.put("programEnded", ended);
            result.put("conclusion", ended
                ? "The replay ran to completion and the invariant held at every check."
                : "No violation within " + timeout + "ms, and the program is STILL RUNNING — "
                    + "so this is not yet 'the invariant holds'. Wait longer, or check that "
                    + "the invariant and its location are what you meant.");
            return ToolResponse.success(result, ResponseMeta.builder()
                .steering(ended
                    ? "The invariant held. The session is still open if you want to look."
                    : "Inconclusive, not clean — say so if you report it.")
                .build());
        }

        // THE FIRST VIOLATION. Disarm at once: every later event would break it too, and a
        // second capture would make "the first" a lie.
        debugger.clearBreakpoint((String) armed.get("breakpointId"));

        Map<String, Object> hit = violation.get();
        long threadId = ((Number) hit.get("threadId")).longValue();

        Map<String, Object> capture = new LinkedHashMap<>(hit);
        capture.put("state", debugger.snapshot(threadId, 0,
            getIntParam(arguments, "depth", JdiValues.DEFAULT_DEPTH),
            JdiValues.DEFAULT_MAX_ITEMS, JdiValues.DEFAULT_MAX_BYTES));

        Map<String, Object> captured = new LinkedHashMap<>();
        for (String expression : stringList(arguments, "capture")) {
            try {
                captured.put(expression, debugger.evaluate(threadId, 0, expression).get("summary"));
            } catch (DebugController.DebugException e) {
                captured.put(expression, e.code + ": " + e.getMessage());
            }
        }
        capture.put("captured", captured);

        // Store it with its provenance — a capture whose origin is unknown is evidence of
        // nothing.
        String artifactId = artifacts.newArtifactId("replay");
        Path dir = artifacts.createArtifactDir(artifactId);
        Files.writeString(dir.resolve("capture.json"),
            new com.fasterxml.jackson.databind.ObjectMapper()
                .writerWithDefaultPrettyPrinter().writeValueAsString(capture));
        artifacts.writeManifest(artifactId, Map.of(
            "kind", "replay",
            "sessionId", session.id,
            "target", mainClass,
            "invariant", invariant,
            "checkedAt", className + ":" + line,
            "violated", true,
            "files", List.of("capture.json")));

        result.put("violated", true);
        result.put("firstViolation", capture);
        result.put("artifactId", artifactId);
        result.put("threadId", threadId);
        return ToolResponse.success(result, ResponseMeta.builder()
            .steering("This is the FIRST violation — the breakpoint was disarmed the moment it "
                + "fired, so no later one can be mistaken for it. The thread is SUSPENDED "
                + "right there: snapshot, evaluate and step from the moment it broke, rather "
                + "than reasoning backwards from the wreckage. sessionId " + session.id + ".")
            .build());
    }

    private ToolResponse listArtifacts() {
        List<Map<String, Object>> all = artifacts.describeAll();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("artifacts", all);
        data.put("count", all.size());
        data.put("root", artifacts.root().toString());
        return ToolResponse.success(data, ResponseMeta.builder()
            .returnedCount(all.size())
            .steering("These are on your disk and some are large. Delete with "
                + "debug(action=artifact_delete, artifactId=…); expired ones are pruned.")
            .build());
    }

    private ToolResponse deleteArtifact(JsonNode arguments) {
        String artifactId = getStringParam(arguments, "artifactId");
        if (artifactId == null || artifactId.isBlank()) {
            return ToolResponse.invalidParameter("artifactId",
                "artifact_delete needs an artifactId (see action=artifacts).");
        }
        boolean deleted = artifacts.delete(artifactId);
        if (!deleted) {
            return ToolResponse.symbolNotFound("No artifact '" + artifactId + "'.");
        }
        return ToolResponse.success(Map.of("artifactId", artifactId, "deleted", true),
            ResponseMeta.builder().build());
    }

    private Integer optionalInt(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        return node == null || node.isNull() ? null : node.asInt();
    }

    private long longParam(JsonNode arguments, String field, long fallback) {
        JsonNode node = arguments.get(field);
        return node == null || node.isNull() ? fallback : node.asLong();
    }

    private ToolResponse discover() {
        List<Map<String, Object>> jvms = JvmTargets.discover();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("jvms", jvms);
        data.put("count", jvms.size());
        return ToolResponse.success(data, ResponseMeta.builder()
            .returnedCount(jvms.size())
            .steering(jvms.isEmpty()
                ? "No other local JVMs are running. Use action=launch to start one."
                : "Attach with debug(action=attach, pid=…) — but only where debuggable=true. "
                    + "A JVM that started without a debug agent can NEVER be given one; "
                    + "debuggability is decided at launch and cannot be retrofitted.")
            .build());
    }

    private ToolResponse attach(JsonNode arguments) throws Exception {
        int pid = getIntParam(arguments, "pid", -1);
        if (pid <= 0) {
            return ToolResponse.invalidParameter("pid",
                "attach needs the pid of a local JVM (see action=discover).");
        }
        RuntimeSession session;
        try {
            session = sessions.attach(pid);
        } catch (JvmTargets.NotDebuggable e) {
            // Not a crash and not our failure: this JVM can never be debugged, and the
            // caller needs to know that rather than retry.
            return ToolResponse.error("JVM_NOT_DEBUGGABLE", e.getMessage(),
                "Debuggability is decided at launch. Start the target with "
                    + "debug(action=launch) — the dev/sim preset prepares it — or add "
                    + "-agentlib:jdwp to however it is launched.");
        }
        return sessionResponse(session, "Attached. This JVM is not ours — detach releases it, "
            + "it keeps running.", getStringParam(arguments, "projectKey"));
    }

    private ToolResponse launch(JsonNode arguments) throws Exception {
        String mainClass = getStringParam(arguments, "mainClass");
        String classpath = getStringParam(arguments, "classpath");
        if (mainClass == null || mainClass.isBlank()) {
            return ToolResponse.invalidParameter("mainClass", "launch needs a mainClass.");
        }
        if (classpath == null || classpath.isBlank()) {
            return ToolResponse.invalidParameter("classpath", "launch needs a classpath.");
        }
        List<String> command = new ArrayList<>(List.of("-cp", classpath, mainClass));
        command.addAll(stringList(arguments, "args"));

        String workingDirectory = getStringParam(arguments, "workingDirectory");
        RuntimeSession session = sessions.launch(
            command,
            workingDirectory == null ? null : Path.of(workingDirectory),
            stringList(arguments, "jvmArgs"));
        return sessionResponse(session,
            "Launched under the dev/sim preset, and HELD BEFORE ITS FIRST INSTRUCTION. "
                + "This JVM is ours — detach kills it. Arm your breakpoints now, then "
                + "debug(action=resume) to start the program: a target that is already "
                + "running has already run past whatever you wanted to see.",
            getStringParam(arguments, "projectKey"));
    }

    private ToolResponse status(JsonNode arguments) {
        String sessionId = getStringParam(arguments, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            List<Map<String, Object>> all = sessions.list();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessions", all);
            data.put("count", all.size());
            return ToolResponse.success(data,
                ResponseMeta.builder().returnedCount(all.size()).build());
        }
        Optional<RuntimeSession> session = sessions.get(sessionId);
        if (session.isEmpty()) {
            return ToolResponse.symbolNotFound("No debug session '" + sessionId + "'.");
        }
        // Opens the journal if it is not open yet: every path that hands back a session must
        // name its hit stream, or an agent that never called launch cannot find it.
        DebugController debugger = debuggerOf(session.get());
        Map<String, Object> described = new LinkedHashMap<>(session.get().describe());
        described.put("hitStream", hitStreamOf(session.get()).toString());
        // The session always discloses what it changed. A debugged program is no longer the
        // program you started, and a status that hides that invites a false conclusion.
        List<Map<String, Object>> changed = debugger.mutations();
        described.put("mutationCount", changed.size());
        described.put("programIsUnmodified", changed.isEmpty());
        if (!changed.isEmpty()) {
            described.put("mutations", changed);
        }
        return ToolResponse.success(described, ResponseMeta.builder()
            .steering("Every hit is appended as one JSON line to `hitStream` — point a file "
                + "monitor at it and each breakpoint wakes you, no polling.")
            .build());
    }

    private ToolResponse detach(JsonNode arguments) {
        String sessionId = getStringParam(arguments, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return ToolResponse.invalidParameter("sessionId", "detach needs a sessionId.");
        }
        Optional<RuntimeSession> session = sessions.get(sessionId);
        if (session.isEmpty()) {
            return ToolResponse.symbolNotFound("No debug session '" + sessionId + "'.");
        }
        RuntimeSession.Origin origin = session.get().origin;
        sessions.close(sessionId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("closed", true);
        data.put("outcome", origin == RuntimeSession.Origin.LAUNCHED
            ? "terminated (we launched it)"
            : "released (it keeps running — it was never ours)");
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    private ToolResponse sessionResponse(RuntimeSession session, String note, String projectKey) {
        // Open the journal NOW, before the caller can arm anything — so a watcher can be
        // attached to an existing file rather than racing its creation.
        debuggerOf(session, projectKey);

        Map<String, Object> data = new LinkedHashMap<>(session.describe());
        data.put("note", note);
        data.put("hitStream", hitStreamOf(session).toString());
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("BE WOKEN BY BREAKPOINTS, DO NOT POLL FOR THEM: every hit is appended "
                + "as one JSON line to `hitStream`. Point your harness's file monitor at it "
                + "(e.g. `tail -f <hitStream>`) and each breakpoint becomes a notification — "
                + "the session stays live and you stay idle in between. jawata cannot push to "
                + "you over MCP (a server only speaks when spoken to), but your own harness "
                + "can wake you, and this is the channel it watches. `wait` remains available "
                + "for the simple case: it blocks and returns the next hit. "
                + "Capabilities are read from the JVM, never assumed — hot-swap and pop-frame "
                + "are not universal. Preset capabilities: " + DevSimPreset.CAPABILITIES + ".")
            .build());
    }

    private List<String> stringList(JsonNode arguments, String field) {
        List<String> values = new ArrayList<>();
        JsonNode node = arguments.get(field);
        if (node != null && node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        }
        return values;
    }
}
