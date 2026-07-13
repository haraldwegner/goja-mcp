package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.DevSimPreset;
import org.jawata.mcp.runtime.JvmTargets;
import org.jawata.mcp.runtime.RuntimeSession;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;

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

    private static final List<String> ACTIONS =
        List.of("discover", "launch", "attach", "status", "detach", "cancel");

    private final RuntimeSessionRegistry sessions;

    public DebugTool(Supplier<IJdtService> serviceSupplier, RuntimeSessionRegistry sessions) {
        super(serviceSupplier);
        this.sessions = sessions;
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
            - launch   — start a JVM under the dev/sim preset and attach to it.
                         Needs: mainClass + classpath. Optional: args, jvmArgs,
                         workingDirectory.
            - status   — one session, or all of them (omit sessionId).
            - detach   — end a session. A JVM you LAUNCHED is killed; a JVM you
                         ATTACHED to is released and keeps running — it was never
                         yours.
            - cancel   — alias for detach.

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
                default -> ToolResponse.invalidParameter("action",
                    "Unknown action '" + action + "'. Allowed: " + ACTIONS);
            };
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
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
                : "Attach with debug(action=attach, pid=…). A JVM without a debug agent "
                    + "gets one loaded into it.")
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
            "Launched under the dev/sim preset. This JVM IS ours — detach kills it.");
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
        return ToolResponse.success(session.get().describe(), ResponseMeta.builder().build());
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
        Map<String, Object> data = new LinkedHashMap<>(session.describe());
        data.put("note", note);
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("The capabilities are read from the JVM itself, not assumed: "
                + "hot-swap and pop-frame are not universal. Preset capabilities: "
                + DevSimPreset.CAPABILITIES + ".")
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
