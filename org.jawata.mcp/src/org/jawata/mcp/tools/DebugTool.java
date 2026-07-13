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
        "artifacts", "artifact_delete");

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

            ARTIFACTS: artifacts / artifact_delete — what a session left behind (replay
            captures, dumps), with provenance and an expiry. Explicit delete, because
            these get large.

            WHAT THIS DOES TO THE TARGET: a debug session can suspend threads and
            change state in the JVM it attaches to. It is meant for a development or
            simulation machine. Attaching it anywhere else is your professional
            judgment.

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
            "description", "wait: how long to wait for a hit (default 5000, max 60000). A "
                + "timeout is a normal answer — 'nothing yet' — not an error."));
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
        return debuggerOf(session);
    }

    /** Every session journals its hits, from the first one — see {@link #hitStreamOf}. */
    private DebugController debuggerOf(RuntimeSession session) {
        DebugController debugger = session.debugger();
        debugger.journalHitsTo(hitStreamOf(session));
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
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("The thread is SUSPENDED at this point. Read it with "
                + "action=snapshot (threadId above), then action=step or action=resume. "
                + "Every other thread is still running.")
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
            + "it keeps running.");
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
                + "running has already run past whatever you wanted to see.");
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
        debuggerOf(session.get());
        Map<String, Object> described = new LinkedHashMap<>(session.get().describe());
        described.put("hitStream", hitStreamOf(session.get()).toString());
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

    private ToolResponse sessionResponse(RuntimeSession session, String note) {
        // Open the journal NOW, before the caller can arm anything — so a watcher can be
        // attached to an existing file rather than racing its creation.
        debuggerOf(session);

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
