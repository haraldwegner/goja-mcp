package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.runtime.RuntimeSession;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.runtime.debug.DebugController;
import org.jawata.mcp.runtime.profile.GdbAdapter;
import org.jawata.mcp.runtime.profile.HsErrParser;
import org.jawata.mcp.runtime.profile.Jcmd;
import org.jawata.mcp.runtime.profile.JfrParser;
import org.jawata.mcp.runtime.profile.JmxClient;
import org.jawata.mcp.runtime.profile.LatencyCalculator;
import org.jawata.mcp.runtime.profile.ProfileParsers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.ObjectName;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Sprint 24 (D10) — the {@code profile} front door: process-level diagnostics
 * against a running JVM, without needing it to be a debug target.
 *
 * <p><b>How this differs from {@code debug}.</b> The interactive debugger talks
 * JDWP, which only exists on a JVM that was STARTED with a debug agent — a
 * structural, permanent decision (see {@code JvmTargets.NotDebuggable}). {@code
 * jcmd}, which this tool uses, needs none of that: it attaches to any same-user
 * JVM through the JDK's Dynamic Attach mechanism. Threads, heap, GC and native
 * memory are OS/JVM-process facts, not debug-channel facts.</p>
 *
 * <p><b>Scope of this stage.</b> This tool operates against the SAME sessions
 * {@code debug} does — {@code debug(action=launch|attach)} first, then the
 * {@code sessionId} it returns. That is a real, stated boundary: today, attaching
 * a session still requires the target to be JDWP-debuggable, even though {@code
 * jcmd} itself would work on a plain JVM. Reusing the Stage-7 session spine
 * (rather than inventing a second, jcmd-only attach path) keeps one lifecycle,
 * one artifact store, and one handle an agent hands to a subagent — and a
 * dev/sim-preset target is debuggable AND profilable at once, which is the
 * common case this stage serves.</p>
 */
public class ProfileTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ProfileTool.class);

    private static final List<String> ACTIONS = List.of(
        "threads", "deadlock", "histogram", "gc", "nmt", "heap_dump",
        "sample", "jfr_dump", "hotspots", "latency_seam",
        "incident_arm", "incident_status", "incident_get", "incident_disarm",
        "jmx_read", "log_level",
        "native_hs_err", "native_nmt", "native_handoff",
        "artifacts", "artifact_delete");

    private static final int DEFAULT_STACK_FRAMES = 20;
    private static final int DEFAULT_HISTOGRAM_LIMIT = 20;
    private static final int MAX_HISTOGRAM_LIMIT = 200;

    private static final int DEFAULT_SAMPLE_SECONDS = 5;
    private static final int MAX_SAMPLE_SECONDS = 30;
    /** How long past the recording's own duration we wait for its auto-dump to land. */
    private static final int SAMPLE_MARGIN_MILLIS = 2_000;
    private static final int DEFAULT_HOTSPOT_LIMIT = 20;
    private static final int MAX_HOTSPOT_LIMIT = 200;
    /** The continuous recording DevSimPreset starts on every dev/sim launch. */
    private static final String CONTINUOUS_RECORDING_NAME = "jawata";

    private static final int DEFAULT_LATENCY_SECONDS = 5;
    private static final int MAX_LATENCY_SECONDS = 30;
    /**
     * Poll the probe ring OFTEN — the ring keeps only the last ~500 events across all
     * probes, so at a few thousand calls/s (an order-flow seam) a 200ms gap between reads
     * let it overflow and silently drop samples BEFORE we read them, biasing the very tail
     * the measurement exists to catch. 20ms keeps the ring well drained; any residual loss
     * is now also detected and reported rather than hidden. Sprint-24 audit (T1.5).
     */
    private static final int LATENCY_POLL_MILLIS = 20;
    /** Generous — a hot seam under load easily exceeds the shared probe ring's 500-event cap. */
    private static final int LATENCY_PROBE_BUDGET = 200_000;
    /** Below this many paired samples, p999 is a near-extrapolation, not a measurement. */
    private static final int MIN_SAMPLES_FOR_P999 = 200;

    private static final int DEFAULT_JFR_SLICE_SECONDS = 15;
    private static final int DEFAULT_LOG_SLICE_LINES = 200;
    /**
     * Either debugger is a valid native_handoff adapter — they are driven with their OWN
     * argument shapes (they share none), and their own frame syntaxes are both parsed.
     */
    private static final String DEFAULT_ADAPTER_COMMAND = "gdb";
    private static final int ADAPTER_TIMEOUT_SECONDS = 30;
    /** An armed incident is cheap in-memory state; still bounded, same discipline as sessions. */
    private static final int MAX_ARMED_INCIDENTS = 50;
    /** How much of a non-JSON alarm declaration we quote back in the bundle summary. */
    private static final int MAX_DECLARED_TEXT_CHARS = 2_000;

    private final Map<String, ArmedIncident> incidents = new ConcurrentHashMap<>();

    /** One pending revert per (target, logger) — see {@link LogLevelBump}. */
    private final Map<String, LogLevelBump> logLevelBumps = new ConcurrentHashMap<>();
    /**
     * One bounded, daemon scheduler for every pending log-level revert. The old code started
     * a raw Thread per call — unbounded, and each carrying its own idea of what to revert to.
     */
    private final ScheduledExecutorService logLevelReverts =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "jawata-log-level-revert");
            thread.setDaemon(true);
            return thread;
        });

    private final RuntimeSessionRegistry sessions;
    private final RuntimeArtifactStore artifacts;

    public ProfileTool(Supplier<IJdtService> serviceSupplier, RuntimeSessionRegistry sessions) {
        this(serviceSupplier, sessions, new RuntimeArtifactStore());
    }

    public ProfileTool(Supplier<IJdtService> serviceSupplier, RuntimeSessionRegistry sessions,
                       RuntimeArtifactStore artifacts) {
        super(serviceSupplier);
        this.sessions = sessions;
        this.artifacts = artifacts;
    }

    /**
     * One {@code incident_arm} call's config, held between calls until the alarm file
     * appears and the bundle is captured. Cheap (no JVM resources held) — the session
     * itself is looked up fresh by id each time it is needed, never cached here.
     */
    private static final class ArmedIncident {
        final String sessionId;
        final Path alarmFile;
        final Path logFile;
        final boolean live;
        final int jfrSliceSeconds;
        volatile Map<String, Object> capturedSummary;

        ArmedIncident(String sessionId, Path alarmFile, Path logFile, boolean live, int jfrSliceSeconds) {
            this.sessionId = sessionId;
            this.alarmFile = alarmFile;
            this.logFile = logFile;
            this.live = live;
            this.jfrSliceSeconds = jfrSliceSeconds;
        }
    }

    @Override
    public String getName() {
        return "profile";
    }

    @Override
    public String getDescription() {
        return """
            Process-level diagnostics for a running JVM — threads, deadlocks, heap,
            GC state, native memory, heap dumps. Works on any session from
            debug(action=launch|attach); does not require or change debuggability.

            USAGE: profile(action="<action>", sessionId="<from debug launch|attach>", ...)

            - threads    — every OS thread the JVM knows about: name, id, daemon,
                           state, and its Java stack (bounded by maxFrames, default 20).
                           A process-level dump — it works even mid-breakpoint, unlike
                           debug(action=threads) which reads the suspended JDI view.
            - deadlock   — from the SAME dump `threads` would read, so the two can never
                           disagree. `deadlocked: false` when there is none — an absence
                           is an answer. When there IS one, the summary NAMES the wait
                           chain (who blocks whom), not just the raw thread list.
            - histogram  — the heap's class histogram, ranked by retained bytes, capped
                           at `limit` (default 20, max 200) — with the TRUE total class
                           count and totals reported alongside, so a capped list never
                           reads as the whole heap.
            - gc         — current heap/generation sizes (used/total per region) plus
                           Metaspace — a snapshot, not a trend; call it again to see one.
            - nmt        — native memory summary by category (reserved/committed). If
                           the target was not launched with -XX:NativeMemoryTracking
                           (it cannot be turned on after the fact), this says so plainly
                           — `enabled: false` and why — never a fabricated all-zero report.
            - heap_dump  — a full HPROF heap dump, ARTIFACT-STORED with provenance.
                           `live` (default true) dumps only reachable objects after a
                           full GC — usually far smaller and what you want for "what's
                           actually retained"; `live: false` includes unreachable
                           garbage too, for the rare case that matters.

            PROFILES THAT NAME SYMBOLS — Java Flight Recorder, parsed and ranked:
            - sample     — a BOUNDED, targeted recording: `durationSeconds` (default 5,
                           max 30) of CPU/allocation/lock sampling at the "profile" JFC
                           template's rate, artifact-stored when it completes. BLOCKS
                           for the duration — the call that waits is the call that
                           returns the data, same as debug(action=wait).
            - jfr_dump   — dump the CONTINUOUS recording the dev/sim preset already
                           runs (name "jawata"), ON DEMAND, mid-run — no new sampling
                           window, whatever it already captured. Refused, honestly, if
                           the target has no such recording (not preset-launched, or
                           Flight Recorder is disabled — `enabled: false` + why, same
                           shape as `nmt`'s capability-absent report).
            - hotspots   — rank a JFR artifact's methods for `dimension` = cpu | alloc
                           | lock | gc. cpu/alloc/lock are PER-METHOD rankings (the top
                           stack frame of each sample) — symbol-named
                           (`ClassName#methodName`, the same key get_call_hierarchy and
                           find_references take), paginated (`offset`/`limit`, default
                           20 max 200), with the TRUE total sample and method counts
                           reported alongside so a capped page never reads as the whole
                           picture. `samples` on each row is a SAMPLE count — how often
                           this method was caught on top of the stack — not an
                           instrumented call count; said plainly, not implied. `gc` has
                           no Java stack to rank a method BY (a GC pause is a fact about
                           the collector), so it reports pauseCount/totalPauseMillis/
                           maxPauseMillis instead of inventing an attribution JFR does
                           not provide.

            LATENCY AT A NAMED SEAM:
            - latency_seam — className + method: a JDT-RESOLVED seam (checked against
                           the WORKSPACE source before touching the live JVM — a wrong
                           name is refused with a compiler-accurate reason, not a JDI
                           error three steps later). Traces every call via the SAME
                           non-suspending method_trace probe `debug` uses (D8) — the
                           program is never stopped — for `durationSeconds` (default 5,
                           max 30; the call BLOCKS for it). Reports p50/p99/p999 TWICE:
                           `raw` (measured) and `corrected` (coordinated-omission
                           corrected — see below), millisecond resolution, stated as
                           such (this is a JDI event timestamp, not a nanosecond
                           instrument).

                           WHY TWO SETS OF PERCENTILES: most latency measurements are
                           CLOSED-LOOP — the next call starts only after the last one
                           finishes (this is the easy thing to build, and it is what
                           this stage's own fixture does). A closed-loop caller SKIPS
                           the calls a real, open-loop caller would still have issued
                           during a slow patch — so its raw trace under-reports its own
                           tail, worse the slower the stall. `corrected` backfills the
                           samples that silence hid (Gil Tene's fix, as HdrHistogram
                           implements it: for an observed value v against the expected
                           inter-call gap i, add phantom samples at v-i, v-2i, ... down
                           to i). Both numbers are reported so the correction's effect
                           is VISIBLE, never asserted. `expectedIntervalMillis` — pass it
                           if you know the target's intended call rate; omitted, it is
                           INFERRED as the median gap between observed call starts.
               Below 200 paired samples, p999 is flagged as unreliable
                           rather than reported as if it were not.

            INCIDENT BUNDLE ON ALARM — one declared signal, one local bundle:
            - incident_arm    — sessionId + alarmFile (a path the TARGET writes to when
                           IT decides something is wrong — the target declares what,
                           jawata only captures; same division as replay/D9). Optional
                           logFile (the target's own app log, sliced into the bundle),
                           live (heap-dump reachability, default true), jfrSliceSeconds
                           (default 15 — how much of the continuous recording's recent
                           past to keep). Returns immediately with an incidentId — this
                           does NOT block, because an alarm's timing is exactly what you
                           do not know in advance.
            - incident_status — poll this. The FIRST call after the alarm file appears
                           CAPTURES the bundle right there (JFR slice, thread dump, heap
                           histogram, a heap dump, a log slice, a replay descriptor, and
                           a summary naming the alarming symbol — SEVEN parts, all
                           artifact-stored with provenance) and reports `bundleReady`.
                           `fired: false` before that — an absence, not a failure.
            - incident_get    — the full bundle, once `bundleReady`. Refused, honestly,
                           if you ask before it exists.
            - incident_disarm — release a watch you no longer need. A bundle it already
                           captured STAYS in the artifact store; only the watch goes away.
                           The armed table is bounded, and when it is full of watches that
                           have never fired, arming another is REFUSED rather than silently
                           dropping one of the watches you already asked for — this is how
                           you make room.

            DECLARED JMX READS + RUNTIME LOG CONTROL:
            - jmx_read   — objectName + attribute: one MBean attribute, via the SAME
                           local JMX the dev/sim preset already enables (no new
                           capability, no authentication surface — loopback-only).
            - log_level  — loggerName ("" = root) + level, via the platform Logging
                           MBean. Optional expirySeconds: after that long the level
                           REVERTS — not to whatever it was when you made THIS call, but
                           to the level the logger had before jawata first touched it
                           (`revertsTo` says which). Bumping again while a revert is
                           pending moves the deadline; it does not change the
                           destination, so two overlapping bumps cannot leave the target
                           stranded at an intermediate level. Without expirySeconds the
                           change is permanent and any pending revert is cancelled — a
                           diagnostic verbosity bump should not outlive its reason, but
                           neither should it undo a level you deliberately set.
                           The timer runs in THIS server: if jawata restarts before the
                           expiry, the level stays as set (`revertPerformedBy` says so —
                           we cannot schedule work inside a JVM we do not own).

            NATIVE BOUNDARY TRIAGE — for a crash that killed the process (no live
            session, no sessionId; these three read a file, not a JVM):
            - native_hs_err  — hsErrFile: parse the JVM's own hs_err_pid<pid>.log
                           fatal-error report. HotSpot ALREADY correlates Java and
                           native frames for the crashing thread's own stack — the
                           combined frame list carries each frame's kind
                           (compiledJava/interpretedJava/vmInternal/vmStub/native),
                           and Java frames are symbol-named (`ClassName#methodName`,
                           the same key as everywhere else) while native/VM frames
                           carry library+offset and a resolved function name WHERE
                           HotSpot could resolve one (never invented when it could
                           not — e.g. a stripped library frame stays offset-only,
                           honestly). No external tool is needed for this — it is
                           the JDK's own diagnostic, always available.
            - native_nmt     — hsErrFile: the memory-tracking section EMBEDDED in
                           the crash report itself (only present if the crashed JVM
                           was launched with -XX:NativeMemoryTracking=summary — it
                           cannot be turned on post-mortem). Absent, honestly:
                           `enabled: false` + why, same shape as the live `nmt`
                           action's capability-absent report.
            - native_handoff — hsErrFile + optional adapterCommand (default "gdb";
                           "lldb" also understood) + optional corePath (else
                           inferred from the hs_err file's own "dumping to ..."
                           line): hands the crash to a CONFIGURED external debugger
                           for a deeper backtrace than hs_err alone carries,
                           correlating any frame whose function name matches a
                           symbol hs_err already resolved. An OPTIONAL adapter —
                           honest about platform support: `available: false` + why
                           when the command is not found on this machine, never a
                           silent skip.

            ARTIFACTS: artifacts / artifact_delete — heap dumps, JFR recordings (and
            anything debug produced) with provenance and an expiry; explicit delete,
            because these get large. Shares the store with `debug` — one place either
            front door can find what a session left behind.

            RARE OR LONG-RUNNING WATCHES: hand the sessionId to a SUBAGENT — the same
            handle debug(action=wait) hands off, so one session serves both doors
            without re-attaching.
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
        action.put("description", "Which profiling action to run.");
        properties.put("action", action);

        properties.put("sessionId", Map.of("type", "string",
            "description", "Every action but artifacts/artifact_delete: the session "
                + "handle (from debug(action=launch|attach))."));
        properties.put("maxFrames", Map.of("type", "integer",
            "description", "threads: max stack frames per thread (default "
                + DEFAULT_STACK_FRAMES + ")."));
        properties.put("limit", Map.of("type", "integer",
            "description", "histogram: max rows (default " + DEFAULT_HISTOGRAM_LIMIT + ", max "
                + MAX_HISTOGRAM_LIMIT + "). hotspots: max rows (default " + DEFAULT_HOTSPOT_LIMIT
                + ", max " + MAX_HOTSPOT_LIMIT + "). The true total is always reported too."));
        properties.put("live", Map.of("type", "boolean",
            "description", "heap_dump / incident_arm: reachable objects only after a full "
                + "GC (default true, smaller); false includes unreachable garbage too."));
        properties.put("artifactId", Map.of("type", "string",
            "description", "artifact_delete: which artifact to remove. hotspots: which "
                + "JFR artifact to rank (from action=sample or action=jfr_dump)."));
        properties.put("durationSeconds", Map.of("type", "integer",
            "description", "sample: how long to record (default " + DEFAULT_SAMPLE_SECONDS
                + ", max " + MAX_SAMPLE_SECONDS + "). latency_seam: how long to trace "
                + "(default " + DEFAULT_LATENCY_SECONDS + ", max " + MAX_LATENCY_SECONDS
                + "). Either call BLOCKS for this long."));
        properties.put("dimension", Map.of("type", "string",
            "enum", List.of("cpu", "alloc", "lock", "gc"),
            "description", "hotspots: which profile to rank by (default cpu)."));
        properties.put("className", Map.of("type", "string",
            "description", "latency_seam: the fully-qualified class (Outer$Inner for a "
                + "nested one) declaring the seam method — JDT-resolved against the "
                + "workspace source before the live JVM is touched."));
        properties.put("method", Map.of("type", "string",
            "description", "latency_seam: the seam method's name (no overload "
                + "disambiguation — all overloads on the class are traced together)."));
        properties.put("expectedIntervalMillis", Map.of("type", "integer",
            "description", "latency_seam: the target's intended gap between calls, for "
                + "the coordinated-omission correction. Omitted: inferred as the median "
                + "observed gap between call starts."));
        properties.put("offset", Map.of("type", "integer",
            "description", "hotspots: pagination offset (default 0)."));
        properties.put("alarmFile", Map.of("type", "string",
            "description", "incident_arm: the path the TARGET writes to when it declares "
                + "an alarm. jawata watches for it; the target decides what and when."));
        properties.put("logFile", Map.of("type", "string",
            "description", "incident_arm: the target's own application log, if any — the "
                + "last lines are sliced into the bundle. Omit if there is none."));
        properties.put("jfrSliceSeconds", Map.of("type", "integer",
            "description", "incident_arm: how much of the continuous recording's recent "
                + "past to keep in the bundle (default " + DEFAULT_JFR_SLICE_SECONDS + ")."));
        properties.put("incidentId", Map.of("type", "string",
            "description", "incident_status / incident_get / incident_disarm: the handle from "
                + "action=incident_arm."));
        properties.put("objectName", Map.of("type", "string",
            "description", "jmx_read: the MBean's ObjectName (e.g. "
                + "\"java.lang:type=Memory\")."));
        properties.put("attribute", Map.of("type", "string",
            "description", "jmx_read: the attribute to read."));
        properties.put("loggerName", Map.of("type", "string",
            "description", "log_level: the logger's name; \"\" (default) is the root logger."));
        properties.put("level", Map.of("type", "string",
            "description", "log_level: the new level (e.g. FINE, INFO, WARNING) — the "
                + "java.util.logging.Level constant names."));
        properties.put("expirySeconds", Map.of("type", "integer",
            "description", "log_level: after this long, the level automatically REVERTS "
                + "to what it was. Omitted: the change is permanent until set again."));
        properties.put("hsErrFile", Map.of("type", "string",
            "description", "native_hs_err / native_nmt / native_handoff: path to the "
                + "hs_err_pid<pid>.log the crashed JVM wrote. No sessionId — the process "
                + "that crashed is gone."));
        properties.put("adapterCommand", Map.of("type", "string",
            "description", "native_handoff: the external debugger command (default \""
                + DEFAULT_ADAPTER_COMMAND + "\"; \"lldb\" also understood)."));
        properties.put("corePath", Map.of("type", "string",
            "description", "native_handoff: path to the core dump. Omitted: inferred from "
                + "the hs_err file's own \"dumping to ...\" line."));

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
                case "threads" -> threads(arguments);
                case "deadlock" -> deadlock(arguments);
                case "histogram" -> histogram(arguments);
                case "gc" -> gc(arguments);
                case "nmt" -> nmt(arguments);
                case "heap_dump" -> heapDump(arguments);
                case "sample" -> sample(arguments);
                case "jfr_dump" -> jfrDump(arguments);
                case "hotspots" -> hotspots(arguments);
                case "latency_seam" -> latencySeam(service, arguments);
                case "incident_arm" -> incidentArm(arguments);
                case "incident_status" -> incidentStatus(arguments);
                case "incident_get" -> incidentGet(arguments);
                case "incident_disarm" -> incidentDisarm(arguments);
                case "jmx_read" -> jmxRead(arguments);
                case "log_level" -> logLevel(arguments);
                case "native_hs_err" -> nativeHsErr(arguments);
                case "native_nmt" -> nativeNmt(arguments);
                case "native_handoff" -> nativeHandoff(arguments);
                case "artifacts" -> listArtifacts();
                case "artifact_delete" -> deleteArtifact(arguments);
                default -> ToolResponse.invalidParameter("action",
                    "Unknown action '" + action + "'. Allowed: " + ACTIONS);
            };
        } catch (Jcmd.JcmdException e) {
            return ToolResponse.error("JCMD_FAILED", e.getMessage(),
                "The target JVM did not answer jcmd — check it is still running "
                    + "(profile requires the same-user process; debug(action=status) "
                    + "confirms the session is live).");
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private RuntimeSession sessionOf(JsonNode arguments) throws Jcmd.JcmdException {
        String sessionId = getStringParam(arguments, "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            throw new Jcmd.JcmdException("This action needs a sessionId "
                + "(from debug(action=launch|attach)).");
        }
        return sessions.get(sessionId).orElseThrow(() -> new Jcmd.JcmdException(
            "No session '" + sessionId + "'. Open one with debug(action=launch) "
                + "or debug(action=attach) — profile reuses the same sessions debug does."));
    }

    private ToolResponse threads(JsonNode arguments) throws Jcmd.JcmdException {
        RuntimeSession session = sessionOf(arguments);
        int maxFrames = getIntParam(arguments, "maxFrames", DEFAULT_STACK_FRAMES);
        String raw = Jcmd.run(session.pid(), "Thread.print");
        Map<String, Object> parsed = ProfileParsers.parseThreadDump(raw, Math.max(1, maxFrames));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.id);
        data.put("threads", parsed.get("threads"));
        data.put("threadCount", parsed.get("threadCount"));
        @SuppressWarnings("unchecked")
        Map<String, Object> deadlock = (Map<String, Object>) parsed.get("deadlock");
        if (Boolean.TRUE.equals(deadlock.get("deadlocked"))) {
            data.put("deadlockWarning", "This dump ALSO shows a deadlock — see "
                + "profile(action=deadlock) for the wait chain.");
        }
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(parsed.get("threadCount") instanceof Number n ? n.intValue() : 0)
            .build());
    }

    private ToolResponse deadlock(JsonNode arguments) throws Jcmd.JcmdException {
        RuntimeSession session = sessionOf(arguments);
        String raw = Jcmd.run(session.pid(), "Thread.print");
        // Reuse the SAME parse as `threads` so the two actions can never disagree —
        // slice out only the deadlock section for this action's response.
        Map<String, Object> parsed = ProfileParsers.parseThreadDump(raw, 1);
        Map<String, Object> deadlock = new LinkedHashMap<>(
            (Map<String, Object>) parsed.get("deadlock"));
        deadlock.put("sessionId", session.id);
        return ToolResponse.success(deadlock, ResponseMeta.builder()
            .steering(Boolean.TRUE.equals(deadlock.get("deadlocked"))
                ? null
                : "No deadlock in this dump — an absence, not a failure to look; call "
                    + "again to check at a later moment.")
            .build());
    }

    private ToolResponse histogram(JsonNode arguments) throws Jcmd.JcmdException {
        RuntimeSession session = sessionOf(arguments);
        int limit = Math.min(MAX_HISTOGRAM_LIMIT,
            Math.max(1, getIntParam(arguments, "limit", DEFAULT_HISTOGRAM_LIMIT)));
        String raw = Jcmd.run(session.pid(), "GC.class_histogram");
        Map<String, Object> parsed = ProfileParsers.parseHistogram(raw, limit);
        Map<String, Object> data = new LinkedHashMap<>(parsed);
        data.put("sessionId", session.id);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(parsed.get("totalClasses") instanceof Number n ? n.intValue() : 0)
            .returnedCount(parsed.get("returnedRows") instanceof Number n ? n.intValue() : 0)
            .steering(Boolean.TRUE.equals(parsed.get("truncated"))
                ? "Capped at " + limit + " of " + parsed.get("totalClasses") + " classes — "
                    + "raise `limit` for more; totalInstances/totalBytes above cover ALL "
                    + "classes, not just the returned rows."
                : null)
            .build());
    }

    private ToolResponse gc(JsonNode arguments) throws Jcmd.JcmdException {
        RuntimeSession session = sessionOf(arguments);
        String raw = Jcmd.run(session.pid(), "GC.heap_info");
        Map<String, Object> parsed = ProfileParsers.parseHeapInfo(raw);
        Map<String, Object> data = new LinkedHashMap<>(parsed);
        data.put("sessionId", session.id);
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    private ToolResponse nmt(JsonNode arguments) throws Jcmd.JcmdException {
        RuntimeSession session = sessionOf(arguments);
        String raw = Jcmd.run(session.pid(), "VM.native_memory", "summary");
        Map<String, Object> parsed = ProfileParsers.parseNativeMemory(raw);
        Map<String, Object> data = new LinkedHashMap<>(parsed);
        data.put("sessionId", session.id);
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering(Boolean.FALSE.equals(parsed.get("enabled"))
                ? "Capability absent, not zero usage — see `why`."
                : null)
            .build());
    }

    private ToolResponse heapDump(JsonNode arguments) throws Exception {
        RuntimeSession session = sessionOf(arguments);
        boolean live = getBooleanParam(arguments, "live", true);

        String artifactId = artifacts.newArtifactId("heap-dump");
        Path dir = artifacts.createArtifactDir(artifactId);
        Path dumpFile = dir.resolve("heap.hprof");

        if (live) {
            Jcmd.run(session.pid(), "GC.heap_dump", dumpFile.toString());
        } else {
            Jcmd.run(session.pid(), "GC.heap_dump", "-all", dumpFile.toString());
        }

        long bytes = Files.exists(dumpFile) ? Files.size(dumpFile) : 0;
        artifacts.writeManifest(artifactId, Map.of(
            "kind", "heap-dump",
            "sessionId", session.id,
            "live", live,
            "files", List.of("heap.hprof"),
            "bytes", bytes));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.id);
        data.put("artifactId", artifactId);
        data.put("live", live);
        data.put("bytes", bytes);
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("Stored, not just a path — see it again with profile(action=artifacts) "
                + "or debug(action=artifacts); delete it explicitly, it will not expire itself "
                + "for a week.")
            .build());
    }

    /** Both jfr_dump and hotspots look for the recording under this name in an artifact's dir. */
    private static final String JFR_FILE_NAME = "recording.jfr";

    private ToolResponse sample(JsonNode arguments) throws Exception {
        RuntimeSession session = sessionOf(arguments);
        int durationSeconds = Math.min(MAX_SAMPLE_SECONDS,
            Math.max(1, getIntParam(arguments, "durationSeconds", DEFAULT_SAMPLE_SECONDS)));

        String artifactId = artifacts.newArtifactId("jfr-sample");
        Path dir = artifacts.createArtifactDir(artifactId);
        Path jfrFile = dir.resolve(JFR_FILE_NAME);

        String recordingName = artifactId;
        String started = Jcmd.run(session.pid(), "JFR.start", "name=" + recordingName,
            "settings=profile", "duration=" + durationSeconds + "s", "filename=" + jfrFile);
        Map<String, Object> capabilityAbsent = flightRecorderAbsentReport(started);
        if (capabilityAbsent != null) {
            // Nothing will ever be written here — do not leave an empty, manifest-less
            // directory behind on every refused call (Sprint-24 audit).
            artifacts.delete(artifactId);
            return ToolResponse.success(capabilityAbsent, ResponseMeta.builder()
                .steering("Capability absent, not empty data — see `why`.")
                .build());
        }

        // JFR.start returns immediately; the file exists (empty) from the moment the
        // recording starts and gets its real content only once `duration` elapses and
        // the recording auto-dumps. BLOCKING here — same principle as debug(action=wait):
        // the call that waits is the call that returns real data, not a placeholder.
        try {
            Thread.sleep(durationSeconds * 1000L + SAMPLE_MARGIN_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        long bytes = Files.exists(jfrFile) ? Files.size(jfrFile) : 0;
        artifacts.writeManifest(artifactId, Map.of(
            "kind", "jfr-sample",
            "sessionId", session.id,
            "durationSeconds", durationSeconds,
            "settings", "profile",
            "files", List.of(JFR_FILE_NAME),
            "bytes", bytes));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.id);
        data.put("artifactId", artifactId);
        data.put("durationSeconds", durationSeconds);
        data.put("bytes", bytes);
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("Rank it with profile(action=hotspots, artifactId=\"" + artifactId + "\").")
            .build());
    }

    private ToolResponse jfrDump(JsonNode arguments) throws Exception {
        RuntimeSession session = sessionOf(arguments);

        String artifactId = artifacts.newArtifactId("jfr-dump");
        Path dir = artifacts.createArtifactDir(artifactId);
        Path jfrFile = dir.resolve(JFR_FILE_NAME);

        String result = Jcmd.run(session.pid(), "JFR.dump",
            "name=" + CONTINUOUS_RECORDING_NAME, "filename=" + jfrFile);
        Map<String, Object> capabilityAbsent = flightRecorderAbsentReport(result);
        if (capabilityAbsent != null) {
            return ToolResponse.success(capabilityAbsent, ResponseMeta.builder()
                .steering("Capability absent, not empty data — see `why`.")
                .build());
        }
        if (result.toLowerCase(java.util.Locale.ROOT).contains("no recordings")) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", session.id);
            data.put("enabled", false);
            data.put("why", "No recording named '" + CONTINUOUS_RECORDING_NAME + "' is running "
                + "on this target — it was not launched under the dev/sim preset (which starts "
                + "one continuously). Use profile(action=sample) for a fresh, targeted capture "
                + "instead of dumping a continuous recording that does not exist here.");
            return ToolResponse.success(data, ResponseMeta.builder()
                .steering("Capability absent, not empty data — see `why`.")
                .build());
        }

        long bytes = Files.exists(jfrFile) ? Files.size(jfrFile) : 0;
        artifacts.writeManifest(artifactId, Map.of(
            "kind", "jfr-dump",
            "sessionId", session.id,
            "recordingName", CONTINUOUS_RECORDING_NAME,
            "files", List.of(JFR_FILE_NAME),
            "bytes", bytes));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.id);
        data.put("artifactId", artifactId);
        data.put("bytes", bytes);
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("Rank it with profile(action=hotspots, artifactId=\"" + artifactId + "\").")
            .build());
    }

    private ToolResponse hotspots(JsonNode arguments) throws Exception {
        String artifactId = getStringParam(arguments, "artifactId");
        if (artifactId == null || artifactId.isBlank()) {
            return ToolResponse.invalidParameter("artifactId",
                "hotspots needs an artifactId (from action=sample or action=jfr_dump).");
        }
        if (!artifacts.exists(artifactId)) {
            return ToolResponse.symbolNotFound("No artifact '" + artifactId + "'.");
        }
        Path jfrFile = artifacts.root().resolve(artifactId).resolve(JFR_FILE_NAME);
        if (!Files.exists(jfrFile)) {
            return ToolResponse.error("NOT_A_JFR_ARTIFACT",
                "Artifact '" + artifactId + "' has no " + JFR_FILE_NAME + " — it was not "
                    + "produced by action=sample or action=jfr_dump.",
                "Use action=artifacts to see what kind each artifact is.");
        }

        String dimension = getStringParam(arguments, "dimension", "cpu");
        int limit = Math.min(MAX_HOTSPOT_LIMIT,
            Math.max(1, getIntParam(arguments, "limit", DEFAULT_HOTSPOT_LIMIT)));
        int offset = Math.max(0, getIntParam(arguments, "offset", 0));

        Map<String, Object> data;
        if ("gc".equals(dimension)) {
            data = new LinkedHashMap<>(JfrParser.gcPauses(jfrFile));
            data.put("dimension", "gc");
        } else if (JfrParser.DIMENSION_EVENTS.containsKey(dimension)) {
            data = new LinkedHashMap<>(JfrParser.hotspots(jfrFile, dimension, offset, limit));
        } else {
            return ToolResponse.invalidParameter("dimension",
                "Unknown dimension '" + dimension + "'. One of cpu, alloc, lock, gc.");
        }
        data.put("artifactId", artifactId);

        boolean truncated = Boolean.TRUE.equals(data.get("truncated"));
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(data.get("totalMethods") instanceof Number n ? n.intValue() : 0)
            .returnedCount(data.get("returnedRows") instanceof Number n ? n.intValue() : 0)
            .steering(truncated
                ? "Capped at " + limit + " of " + data.get("totalMethods") + " methods — raise "
                    + "`limit` or page with `offset`; totalSamples above covers ALL of them, "
                    + "not just the returned rows."
                : null)
            .build());
    }

    private ToolResponse latencySeam(IJdtService service, JsonNode arguments) throws Exception {
        RuntimeSession session = sessionOf(arguments);
        String className = getStringParam(arguments, "className");
        String methodName = getStringParam(arguments, "method");
        if (className == null || className.isBlank() || methodName == null || methodName.isBlank()) {
            return ToolResponse.invalidParameter("method",
                "latency_seam needs className + method.");
        }

        // JDT-RESOLVED SEAM: confirmed against the WORKSPACE SOURCE before the live JVM
        // is ever touched — a wrong name is a compiler-accurate refusal here, not a JDI
        // error three steps later after a probe install failed for an opaque reason.
        IType type = service.findType(className);
        boolean methodExists = type != null
            && java.util.Arrays.stream(type.getMethods()).map(IMethod::getElementName)
                .anyMatch(methodName::equals);
        if (!methodExists) {
            return ToolResponse.error("SEAM_NOT_FOUND",
                "No method '" + methodName + "' found on '" + className
                    + "' in the workspace source.",
                "Check the class/method name; get_type_members or find_references can confirm it.");
        }

        int durationSeconds = Math.min(MAX_LATENCY_SECONDS,
            Math.max(1, getIntParam(arguments, "durationSeconds", DEFAULT_LATENCY_SECONDS)));
        Integer expectedIntervalParam = optionalInt(arguments, "expectedIntervalMillis");

        // Reuses D8's method_trace probe AS IS (no changes to DebugController): it only
        // class-filters, so entries/exits from OTHER methods in the same class arrive
        // too — filtered out below by matching the event's own recorded method name.
        DebugController debugger = session.debugger();
        Map<String, Object> armed = armProbeWaitingForClassLoad(debugger, className, methodName);
        String probeId = (String) armed.get("probeId");

        // The shared probe-event ring (DebugController, D8) caps at 500 events total —
        // a hot seam under load would blow past that in well under a second. Poll and
        // accumulate into OUR OWN unbounded list instead of reading once at the end;
        // "sequence" is monotonic per probe, so it is a safe de-dupe key across polls.
        List<Map<String, Object>> collected = new ArrayList<>();
        Set<Object> seenSequences = new HashSet<>();
        long deadline = System.currentTimeMillis() + durationSeconds * 1000L;
        Map<String, Object> probeReport;
        try {
            while (System.currentTimeMillis() < deadline) {
                drainNewEvents(debugger, probeId, seenSequences, collected);
                Thread.sleep(LATENCY_POLL_MILLIS);
            }
            drainNewEvents(debugger, probeId, seenSequences, collected);
            // Read the probe's TRUE event count BEFORE clearing it — the only honest
            // denominator for "did we see every event?". The ring keeps only the last ~500.
            probeReport = debugger.probeReport(probeId).orElse(Map.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            debugger.clearProbe(probeId);
        }

        collected.sort(java.util.Comparator.comparingLong(
            e -> ((Number) e.get("sequence")).longValue()));

        // Pair entry/exit PER THREAD with a stack — handles recursion/reentrancy safely,
        // and events from OTHER methods (the class-filter's over-capture) are excluded
        // by name before they ever reach the stack.
        Map<Long, Deque<Long>> openByThread = new LinkedHashMap<>();
        List<Long> latenciesMillis = new ArrayList<>();
        List<Long> callStarts = new ArrayList<>();
        for (Map<String, Object> event : collected) {
            if (!methodName.equals(event.get("method"))) {
                continue;
            }
            long threadId = ((Number) event.get("threadId")).longValue();
            long atMillis = ((Number) event.get("atMillis")).longValue();
            if ("entry".equals(event.get("trace"))) {
                openByThread.computeIfAbsent(threadId, k -> new ArrayDeque<>()).push(atMillis);
                callStarts.add(atMillis);
            } else if ("exit".equals(event.get("trace"))) {
                Deque<Long> stack = openByThread.get(threadId);
                if (stack != null && !stack.isEmpty()) {
                    long entryMillis = stack.pop();
                    latenciesMillis.add(atMillis - entryMillis);
                }
            }
        }

        if (latenciesMillis.isEmpty()) {
            return ToolResponse.error("NO_CALLS_OBSERVED",
                "The probe was armed for " + durationSeconds + "s and captured zero completed "
                    + "calls to " + className + "#" + methodName + ".",
                "Confirm the target is actually calling this method during the window "
                    + "(debug(action=status) shows whether the session is running).");
        }

        long expectedInterval = expectedIntervalParam != null
            ? expectedIntervalParam
            : LatencyCalculator.inferExpectedInterval(callStarts);

        LatencyCalculator.Percentiles raw = LatencyCalculator.percentilesOf(latenciesMillis);
        List<Long> corrected = LatencyCalculator.applyCoordinatedOmissionCorrection(
            latenciesMillis, expectedInterval);
        LatencyCalculator.Percentiles correctedPct = LatencyCalculator.percentilesOf(corrected);

        // HONESTY ABOUT WHAT WE MISSED. `sequence` is monotonic 1..N per probe, so a GAP in
        // the sequence numbers we observed is an event the bounded ring evicted before we read
        // it — and that loss is NOT random: a surviving exit can pair with an earlier call's
        // entry, fabricating an inflated tail. Loss is therefore (highest sequence we saw −
        // how many distinct ones we saw), NOT (probe's final count − observed): the latter
        // counts events that fired AFTER our last read, which are merely past the window, not
        // lost, and would flag a phantom 1-event "loss" on every calm run. If the probe hit
        // its budget, the window also silently covered less than `durationSeconds`. Both were
        // invisible in v2.13.0 though the data to detect them was right here.
        long maxSequence = collected.stream()
            .mapToLong(e -> ((Number) e.get("sequence")).longValue())
            .max().orElse(0);
        long eventsObserved = collected.size();
        long eventsLost = Math.max(0, maxSequence - eventsObserved);
        long eventsGenerated = probeReport.get("events") instanceof Number n ? n.longValue() : maxSequence;
        boolean budgetHit = Boolean.TRUE.equals(probeReport.get("stopped"));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.id);
        data.put("seam", className + "#" + methodName);
        data.put("durationSeconds", durationSeconds);
        data.put("resolution", "milliseconds");
        data.put("sampleCount", latenciesMillis.size());
        data.put("eventsGenerated", eventsGenerated);
        data.put("eventsObserved", eventsObserved);
        data.put("eventsLost", eventsLost);
        if (budgetHit) {
            data.put("windowTruncated", true);
            data.put("windowTruncatedReason", probeReport.get("stoppedReason"));
        }
        data.put("expectedIntervalMillis", expectedInterval);
        data.put("raw", Map.of("p50Millis", raw.p50(), "p99Millis", raw.p99(),
            "p999Millis", raw.p999()));
        data.put("corrected", Map.of("p50Millis", correctedPct.p50(), "p99Millis", correctedPct.p99(),
            "p999Millis", correctedPct.p999(), "syntheticSamplesAdded",
            corrected.size() - latenciesMillis.size()));
        boolean p999Unreliable = latenciesMillis.size() < MIN_SAMPLES_FOR_P999;
        if (p999Unreliable) {
            data.put("p999Unreliable", true);
        }
        boolean lossy = eventsLost > 0 || budgetHit;
        if (lossy) {
            data.put("percentilesReliable", false);
        }

        String steering;
        if (budgetHit) {
            steering = "The probe hit its event budget and stopped BEFORE the window ended, so "
                + "these percentiles cover only the first part of the run, not all "
                + durationSeconds + "s. Raise the seam's budget or shorten durationSeconds for a "
                + "measurement that spans the whole window.";
        } else if (eventsLost > 0) {
            steering = eventsLost + " of " + eventsGenerated + " trace events were dropped from "
                + "the bounded event ring before they could be read — this seam fires faster "
                + "than the ring drains, and the loss BIASES THE TAIL (a surviving exit can "
                + "pair with an earlier call's entry). Treat these percentiles as indicative, "
                + "not exact; a shorter/less-hot window loses nothing.";
        } else if (p999Unreliable) {
            steering = "Only " + latenciesMillis.size() + " paired call(s) observed — p999 here "
                + "is close to an extrapolation from the single slowest sample, not a stable "
                + "measurement. Trace longer (raise durationSeconds) for a trustworthy p999.";
        } else {
            steering = "Every trace event was captured (eventsLost:0). `corrected` accounts for "
                + "coordinated omission (see the tool description); compare it against `raw` "
                + "rather than reading either alone.";
        }
        return ToolResponse.success(data, ResponseMeta.builder().steering(steering).build());
    }

    /**
     * Arm a method_trace probe, tolerating the window right after {@code resume()}
     * where the target class has been told to run but has not finished LOADING yet.
     *
     * <p>{@code setProbe} has no deferred-install for probes (unlike breakpoints,
     * which already bind the moment a class loads) — found live: immediately after
     * {@code debug(action=resume)}, arming a probe on the very class whose {@code
     * main()} is about to execute failed with {@code TYPE_NOT_LOADED} every time,
     * because {@code resume()} does not wait for anything inside the target before
     * returning. Rather than change Stage 10's shipped, already-tested {@code
     * DebugController} for a gap only THIS caller has hit, the wait is contained
     * here: retry the exact same call for up to 3s. A class that genuinely never
     * loads still fails, just not falsely, this fast.</p>
     */
    private Map<String, Object> armProbeWaitingForClassLoad(DebugController debugger,
                                                            String className, String methodName)
            throws Exception {
        long deadline = System.currentTimeMillis() + 3_000L;
        while (true) {
            try {
                return debugger.setProbe("method_trace", className, null, methodName, null,
                    List.of(), LATENCY_PROBE_BUDGET);
            } catch (DebugController.DebugException e) {
                if (!"TYPE_NOT_LOADED".equals(e.code) || System.currentTimeMillis() >= deadline) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    private Integer optionalInt(JsonNode arguments, String field) {
        JsonNode node = arguments.get(field);
        return node == null || node.isNull() ? null : node.asInt();
    }

    /**
     * Read only the probe events NOT already seen (by "sequence", monotonic per probe)
     * and append them, in arrival order, to {@code collected}.
     */
    private void drainNewEvents(DebugController debugger, String probeId,
                                Set<Object> seenSequences, List<Map<String, Object>> collected) {
        for (Map<String, Object> event : debugger.probeEvents(probeId)) {
            if (seenSequences.add(event.get("sequence"))) {
                collected.add(event);
            }
        }
    }

    /**
     * jcmd's JFR commands answer "Flight Recorder is disabled." with exit 0 — a soft
     * failure in the text, not a process error. Null when the target answered normally;
     * a ready-to-return capability-absent payload otherwise.
     */
    private Map<String, Object> flightRecorderAbsentReport(String jcmdOutput) {
        if (!jcmdOutput.toLowerCase(java.util.Locale.ROOT).contains("flight recorder is disabled")) {
            return null;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("enabled", false);
        data.put("why", "This JVM was started with -XX:-FlightRecorder — Flight Recorder cannot "
            + "be turned on after launch. Relaunch it without that flag (the dev/sim preset "
            + "enables it by default).");
        return data;
    }

    // --------------------------------------------------- D13: incident bundle on alarm

    private ToolResponse incidentArm(JsonNode arguments) {
        RuntimeSession session;
        try {
            session = sessionOf(arguments);
        } catch (Jcmd.JcmdException e) {
            return ToolResponse.invalidParameter("sessionId", e.getMessage());
        }
        String alarmFileParam = getStringParam(arguments, "alarmFile");
        if (alarmFileParam == null || alarmFileParam.isBlank()) {
            return ToolResponse.invalidParameter("alarmFile",
                "incident_arm needs alarmFile — the path the TARGET writes to when it "
                    + "declares an alarm.");
        }
        String logFileParam = getStringParam(arguments, "logFile");
        boolean live = getBooleanParam(arguments, "live", true);
        int jfrSliceSeconds = Math.max(1, getIntParam(arguments, "jfrSliceSeconds",
            DEFAULT_JFR_SLICE_SECONDS));

        // A REAL bound. The old form evicted an already-captured incident if one happened to
        // exist and then put() unconditionally — so 50 armed watchers that had never fired
        // (precisely the state a bound exists for) grew to 51, 52, … Now: reclaim the slots
        // whose bundles are already safely on disk, and if there are none, REFUSE — because
        // the alternative, silently evicting a live watcher, is a watcher that stops watching
        // without saying so, which is the worst answer available. Sprint-24 audit (T1.10).
        if (incidents.size() >= MAX_ARMED_INCIDENTS) {
            int reclaimed = 0;
            for (Map.Entry<String, ArmedIncident> entry : new ArrayList<>(incidents.entrySet())) {
                if (incidents.size() < MAX_ARMED_INCIDENTS) {
                    break;
                }
                if (entry.getValue().capturedSummary != null && incidents.remove(entry.getKey()) != null) {
                    reclaimed++;
                }
            }
            log.debug("reclaimed {} captured incident slots", reclaimed);
            if (incidents.size() >= MAX_ARMED_INCIDENTS) {
                return ToolResponse.error("TOO_MANY_ARMED_INCIDENTS",
                    MAX_ARMED_INCIDENTS + " incidents are armed and none has fired, so there is "
                        + "no slot to reclaim. Arming another would mean silently dropping one "
                        + "of the watches you already asked for.",
                    "Release one you no longer need with profile(action=incident_disarm, "
                        + "incidentId=…) — profile(action=artifacts) shows what has already "
                        + "been captured.");
            }
        }

        String incidentId = "incident-" + UUID.randomUUID().toString().substring(0, 8);
        incidents.put(incidentId, new ArmedIncident(session.id, Path.of(alarmFileParam),
            logFileParam == null || logFileParam.isBlank() ? null : Path.of(logFileParam),
            live, jfrSliceSeconds));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incidentId", incidentId);
        data.put("sessionId", session.id);
        data.put("armed", true);
        data.put("alarmFile", alarmFileParam);
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("Non-blocking — poll profile(action=incident_status, incidentId=\""
                + incidentId + "\"). The first poll after the alarm fires captures the bundle.")
            .build());
    }

    private ToolResponse incidentStatus(JsonNode arguments) throws Exception {
        ArmedIncident incident = armedIncidentOf(arguments);
        if (incident == null) {
            return ToolResponse.symbolNotFound(
                "No armed incident '" + getStringParam(arguments, "incidentId") + "'.");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incidentId", getStringParam(arguments, "incidentId"));

        if (incident.capturedSummary != null) {
            data.put("fired", true);
            data.put("bundleReady", true);
            data.put("artifactId", incident.capturedSummary.get("artifactId"));
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }

        if (!Files.exists(incident.alarmFile)) {
            data.put("fired", false);
            data.put("bundleReady", false);
            return ToolResponse.success(data, ResponseMeta.builder()
                .steering("No alarm yet — an absence, not a failure to look. Poll again.")
                .build());
        }

        // THE ALARM JUST FIRED. Capture exactly ONCE — even if several callers poll at the
        // same instant, which is the ADVERTISED usage (this tool's own description tells you
        // to hand the sessionId to a subagent for a long watch). Unsynchronized, both callers
        // passed the null check and both captured: two bundles, two heap dumps against a JVM
        // already in distress, one artifactId orphaned. Sprint-24 audit (T1.9).
        Map<String, Object> summary;
        synchronized (incident) {
            if (incident.capturedSummary == null) {
                java.util.Optional<RuntimeSession> session = sessions.get(incident.sessionId);
                if (session.isEmpty()) {
                    return ToolResponse.error("SESSION_TARGET_GONE",
                        "The alarm fired, but session '" + incident.sessionId + "' is no longer "
                            + "open — its process facts (threads, heap, JFR) cannot be captured "
                            + "after the fact.",
                        "Nothing to do — the window to capture this incident has passed.");
                }
                incident.capturedSummary = captureIncidentBundle(session.get(), incident);
            }
            summary = incident.capturedSummary;
        }

        data.put("fired", true);
        data.put("bundleReady", true);
        data.put("artifactId", summary.get("artifactId"));
        data.put("alarmSymbol", summary.get("alarmSymbol"));
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering("Bundle captured — profile(action=incident_get, incidentId=\""
                + getStringParam(arguments, "incidentId") + "\") for all seven parts.")
            .build());
    }

    private ToolResponse incidentGet(JsonNode arguments) {
        ArmedIncident incident = armedIncidentOf(arguments);
        if (incident == null) {
            return ToolResponse.symbolNotFound(
                "No armed incident '" + getStringParam(arguments, "incidentId") + "'.");
        }
        if (incident.capturedSummary == null) {
            return ToolResponse.error("INCIDENT_NOT_FIRED",
                "This incident has not fired yet — there is no bundle to return.",
                "Poll profile(action=incident_status) until bundleReady:true.");
        }
        return ToolResponse.success(incident.capturedSummary, ResponseMeta.builder().build());
    }

    /** Release an armed watch — the way out of a full incident table, and of a watch you no longer want. */
    private ToolResponse incidentDisarm(JsonNode arguments) {
        String incidentId = getStringParam(arguments, "incidentId");
        if (incidentId == null || incidentId.isBlank()) {
            return ToolResponse.invalidParameter("incidentId",
                "incident_disarm needs the incidentId from action=incident_arm.");
        }
        ArmedIncident removed = incidents.remove(incidentId);
        if (removed == null) {
            return ToolResponse.symbolNotFound("No armed incident '" + incidentId + "'.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("incidentId", incidentId);
        data.put("disarmed", true);
        // Disarming never destroys evidence: a bundle already captured stays in the store.
        if (removed.capturedSummary != null) {
            data.put("capturedBundleRetained", removed.capturedSummary.get("artifactId"));
        }
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering(removed.capturedSummary != null
                ? "The watch is released; its captured bundle remains in the artifact store."
                : "The watch is released. It had not fired, so there is nothing to keep.")
            .build());
    }

    private ArmedIncident armedIncidentOf(JsonNode arguments) {
        String incidentId = getStringParam(arguments, "incidentId");
        if (incidentId == null || incidentId.isBlank()) {
            return null;
        }
        return incidents.get(incidentId);
    }

    /**
     * The seven parts, captured in one pass the instant an alarm fires: a JFR slice,
     * a thread dump, a heap histogram, a heap dump, a log slice, a replay descriptor,
     * and the summary tying them together — naming the alarming symbol the TARGET
     * declared (jawata captures; it does not infer what went wrong).
     */
    private Map<String, Object> captureIncidentBundle(RuntimeSession session, ArmedIncident incident)
            throws Exception {
        String artifactId = artifacts.newArtifactId("incident");
        try {
            return captureIncidentBundleInto(artifactId, session, incident);
        } catch (Exception e) {
            // A capture that dies part-way (the target vanishes between the thread dump and
            // the heap dump, say) used to leave its directory behind with no manifest: never
            // listed, never deletable by id, never expired — an invisible, permanent leak,
            // one per failed poll, potentially with a multi-GB heap.hprof inside. Clean up
            // after ourselves and let the failure be the answer. Sprint-24 audit (T1.8).
            if (artifacts.delete(artifactId)) {
                log.debug("removed the partial capture {} after it failed", artifactId);
            }
            throw e;
        }
    }

    private Map<String, Object> captureIncidentBundleInto(
            String artifactId, RuntimeSession session, ArmedIncident incident) throws Exception {
        ObjectMapper json = new ObjectMapper();
        Map<String, Object> alarmPayload = new LinkedHashMap<>();
        try {
            Map<?, ?> parsed = json.readValue(incident.alarmFile.toFile(), Map.class);
            parsed.forEach((k, v) -> alarmPayload.put(String.valueOf(k), v));
        } catch (Exception e) {
            // The target is in trouble, not on trial: it may declare its alarm however it
            // likes, and an unparseable declaration must never cost us the capture — the
            // process is failing NOW and this is the only moment we get. Record what it
            // actually said, bounded, and capture the evidence regardless.
            //
            // Map.of() REJECTS a null value (NullPointerException), so the original form of
            // this very fallback crashed instead of falling back, and wedged the incident it
            // exists to save — every later poll re-threw and no bundle was ever captured.
            // Found by the Sprint-24 audit; see IncidentBundleTest#aNonJsonAlarmIsCaptured…
            alarmPayload.put("symbol", null);
            alarmPayload.put("reason",
                "the alarm file could not be parsed as JSON: " + e.getMessage());
            alarmPayload.put("declaredText", readBoundedText(incident.alarmFile));
        }

        Path dir = artifacts.createArtifactDir(artifactId);
        long pid = session.pid();

        List<String> partsPresent = new ArrayList<>();
        List<String> partsAbsent = new ArrayList<>();

        // 1. JFR slice — the continuous recording's recent past, bounded by maxage.
        Map<String, Object> jfrPart = new LinkedHashMap<>();
        try {
            String jfrResult = Jcmd.run(pid, "JFR.dump", "name=" + CONTINUOUS_RECORDING_NAME,
                "maxage=" + incident.jfrSliceSeconds + "s", "filename=" + dir.resolve("jfr-slice.jfr"));
            Map<String, Object> absent = flightRecorderAbsentReport(jfrResult);
            if (absent != null || jfrResult.toLowerCase(java.util.Locale.ROOT).contains("no recordings")) {
                jfrPart.put("present", false);
                jfrPart.put("why", absent != null ? absent.get("why")
                    : "no continuous recording named '" + CONTINUOUS_RECORDING_NAME + "' on this target");
                partsAbsent.add("jfrSlice");
            } else {
                jfrPart.put("present", true);
                jfrPart.put("file", "jfr-slice.jfr");
                partsPresent.add("jfrSlice");
            }
        } catch (Jcmd.JcmdException e) {
            jfrPart.put("present", false);
            jfrPart.put("why", e.getMessage());
            partsAbsent.add("jfrSlice");
        }

        // 2. Thread dump.
        String threadRaw = Jcmd.run(pid, "Thread.print");
        Files.writeString(dir.resolve("threads.txt"), threadRaw);
        partsPresent.add("threadDump");

        // 3. Heap histogram.
        String histRaw = Jcmd.run(pid, "GC.class_histogram");
        Map<String, Object> histogram = ProfileParsers.parseHistogram(histRaw, 50);
        json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("histogram.json").toFile(), histogram);
        partsPresent.add("heapHistogram");

        // 4. Heap dump (configured: live or all, same contract as action=heap_dump).
        Path heapFile = dir.resolve("heap.hprof");
        if (incident.live) {
            Jcmd.run(pid, "GC.heap_dump", heapFile.toString());
        } else {
            Jcmd.run(pid, "GC.heap_dump", "-all", heapFile.toString());
        }
        partsPresent.add("heapDump");

        // 5. Log slice — the target's own application log, if it declared one.
        Map<String, Object> logPart = new LinkedHashMap<>();
        if (incident.logFile != null && Files.exists(incident.logFile)) {
            List<String> allLines = Files.readAllLines(incident.logFile);
            List<String> tail = allLines.subList(
                Math.max(0, allLines.size() - DEFAULT_LOG_SLICE_LINES), allLines.size());
            Files.write(dir.resolve("log-slice.txt"), tail);
            logPart.put("present", true);
            logPart.put("file", "log-slice.txt");
            logPart.put("lines", tail.size());
            partsPresent.add("logSlice");
        } else {
            logPart.put("present", false);
            logPart.put("why", incident.logFile == null
                ? "no logFile was configured on incident_arm" : "the configured logFile does not exist");
            partsAbsent.add("logSlice");
        }

        // 6. Replay descriptor — what it takes to relaunch this scenario, not an automated
        // replay (D9's replay/invariant capture is a distinct, JATS-scoped mechanism).
        Map<String, Object> replayDescriptor = new LinkedHashMap<>();
        replayDescriptor.put("sessionId", session.id);
        replayDescriptor.put("target", session.target);
        replayDescriptor.put("capturedAtMillis", System.currentTimeMillis());
        json.writerWithDefaultPrettyPrinter()
            .writeValue(dir.resolve("replay-descriptor.json").toFile(), replayDescriptor);
        partsPresent.add("replayDescriptor");

        // 7. Summary — the headline facts, INCLUDING the alarming symbol, in the response
        // itself (never just a path the caller has to go open to find out what happened).
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("artifactId", artifactId);
        summary.put("sessionId", session.id);
        summary.put("alarmSymbol", alarmPayload.get("symbol"));
        summary.put("alarmReason", alarmPayload.get("reason"));
        if (alarmPayload.get("declaredText") != null) {
            summary.put("alarmDeclaredText", alarmPayload.get("declaredText"));
        }
        summary.put("capturedAtMillis", System.currentTimeMillis());
        summary.put("partsPresent", partsPresent);
        if (!partsAbsent.isEmpty()) {
            summary.put("partsAbsent", partsAbsent);
        }
        summary.put("jfr", jfrPart);
        summary.put("log", logPart);
        json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("summary.json").toFile(), summary);

        // A LinkedHashMap, not Map.of: the symbol is genuinely absent when the target did
        // not name one, and String.valueOf(null) would write the LITERAL string "null" into
        // the manifest — a fabricated symbol, findable by search. An absence stays an absence.
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("kind", "incident");
        manifest.put("sessionId", session.id);
        if (alarmPayload.get("symbol") != null) {
            manifest.put("alarmSymbol", alarmPayload.get("symbol"));
        }
        manifest.put("partsPresent", partsPresent);
        artifacts.writeManifest(artifactId, manifest);

        return summary;
    }

    /** The target's own words when its alarm was not JSON — bounded, and never fatal to read. */
    private static String readBoundedText(Path file) {
        try {
            String text = Files.readString(file);
            return text.length() <= MAX_DECLARED_TEXT_CHARS
                ? text.strip()
                : text.substring(0, MAX_DECLARED_TEXT_CHARS).strip() + "… (truncated)";
        } catch (Exception e) {
            return "(the alarm file could not be read back: " + e.getMessage() + ")";
        }
    }

    // --------------------------------------------------- D13: declared JMX reads

    private ToolResponse jmxRead(JsonNode arguments) throws Exception {
        RuntimeSession session = sessionOf(arguments);
        String objectNameParam = getStringParam(arguments, "objectName");
        String attribute = getStringParam(arguments, "attribute");
        if (objectNameParam == null || attribute == null) {
            return ToolResponse.invalidParameter("attribute",
                "jmx_read needs objectName + attribute.");
        }

        Object value;
        try {
            ObjectName objectName = new ObjectName(objectNameParam);
            value = JmxClient.withConnection(session.pid(),
                mbs -> mbs.getAttribute(objectName, attribute));
        } catch (javax.management.InstanceNotFoundException e) {
            return ToolResponse.error("MBEAN_NOT_FOUND",
                "No MBean '" + objectNameParam + "' on this target.", null);
        } catch (javax.management.AttributeNotFoundException e) {
            return ToolResponse.error("ATTRIBUTE_NOT_FOUND",
                "MBean '" + objectNameParam + "' has no attribute '" + attribute + "'.", null);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.id);
        data.put("objectName", objectNameParam);
        data.put("attribute", attribute);
        data.put("value", summarizeJmxValue(value));
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    /** Best-effort structuring of an arbitrary MBean attribute value — never a bare toString(). */
    private Object summarizeJmxValue(Object value) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof javax.management.openmbean.CompositeData composite) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (String key : composite.getCompositeType().keySet()) {
                fields.put(key, summarizeJmxValue(composite.get(key)));
            }
            return fields;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> items = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                items.add(summarizeJmxValue(java.lang.reflect.Array.get(value, i)));
            }
            return items;
        }
        return value.toString();
    }

    // --------------------------------------------------- D13: runtime log-level control

    private ToolResponse logLevel(JsonNode arguments) throws Exception {
        RuntimeSession session = sessionOf(arguments);
        String loggerName = getStringParam(arguments, "loggerName", "");
        String level = getStringParam(arguments, "level");
        if (level == null || level.isBlank()) {
            return ToolResponse.invalidParameter("level", "log_level needs a level.");
        }
        Integer expirySeconds = optionalInt(arguments, "expirySeconds");
        long pid = session.pid();

        String previous = JmxClient.withConnection(pid, mbs -> (String) mbs.invoke(
            JmxClient.LOGGING_MBEAN, "getLoggerLevel",
            new Object[] {loggerName}, new String[] {"java.lang.String"}));
        JmxClient.withConnection(pid, mbs -> {
            mbs.invoke(JmxClient.LOGGING_MBEAN, "setLoggerLevel",
                new Object[] {loggerName, level}, new String[] {"java.lang.String", "java.lang.String"});
            return null;
        });

        String bumpKey = pid + " " + loggerName;
        String revertsTo = null;

        if (expirySeconds != null && expirySeconds > 0) {
            // ONE pending revert per (target, logger), anchored to the level that was in
            // force before jawata touched this logger AT ALL.
            //
            // Each call used to capture "whatever the level is right now" as its own revert
            // target and start its own timer, so a second bump while the first was pending
            // (INFO → FINE → FINEST) reverted to INFO on the first timer and then BACK TO
            // FINE on the second — one reverter restoring another's temporary level, leaving
            // the target verbose indefinitely. That is the precise opposite of this action's
            // promise. Sprint-24 audit (T1.11).
            final String observedNow = previous;
            LogLevelBump bump = logLevelBumps.compute(bumpKey, (key, pending) -> {
                String baseline = pending != null ? pending.baseline : observedNow;
                if (pending != null && pending.revert != null) {
                    pending.revert.cancel(false);   // supersede it; the baseline carries over
                }
                return new LogLevelBump(baseline);
            });
            revertsTo = bump.baseline;

            bump.revert = logLevelReverts.schedule(
                () -> revertLogLevel(bumpKey, bump, pid, loggerName, bump.baseline),
                expirySeconds, TimeUnit.SECONDS);
        } else {
            // Permanent: the caller is taking ownership of this logger's level. Any pending
            // revert must be dropped, or it would later undo the level they just asked to keep.
            LogLevelBump superseded = logLevelBumps.remove(bumpKey);
            if (superseded != null && superseded.revert != null) {
                superseded.revert.cancel(false);
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", session.id);
        data.put("loggerName", loggerName);
        data.put("previousLevel", previous);
        data.put("newLevel", level);
        if (expirySeconds != null) {
            data.put("expirySeconds", expirySeconds);
            data.put("revertsTo", revertsTo);
            // Stated, not silent: the timer lives in THIS process. We cannot schedule work
            // inside a JVM we do not own, so a jawata restart before the expiry leaves the
            // level exactly as set — the operator needs to know that, not discover it.
            data.put("revertPerformedBy", "this jawata server — if it restarts before the "
                + "expiry, the level stays as set");
        }
        return ToolResponse.success(data, ResponseMeta.builder()
            .steering(expirySeconds != null
                ? "Reverts to '" + revertsTo + "' (the level before jawata first touched this "
                    + "logger) in " + expirySeconds + "s. A further bump before then moves the "
                    + "deadline; it does NOT change what it reverts to."
                : "PERMANENT until set again — no expirySeconds was given. Any pending revert "
                    + "on this logger has been cancelled.")
            .build());
    }

    /** The level a logger had before jawata first bumped it, and the one timer that restores it. */
    private static final class LogLevelBump {
        final String baseline;
        volatile ScheduledFuture<?> revert;

        LogLevelBump(String baseline) {
            this.baseline = baseline;
        }
    }

    private void revertLogLevel(String bumpKey, LogLevelBump bump, long pid,
                                String loggerName, String baseline) {
        try {
            JmxClient.withConnection(pid, mbs -> {
                mbs.invoke(JmxClient.LOGGING_MBEAN, "setLoggerLevel",
                    new Object[] {loggerName, baseline},
                    new String[] {"java.lang.String", "java.lang.String"});
                return null;
            });
            log.info("log_level expiry: {} reverted to {}",
                loggerName.isEmpty() ? "<root>" : loggerName, baseline);
        } catch (Exception e) {
            // The target may simply be gone — that is fine, and it is not an error worth
            // failing anything over. It must not pass silently either.
            log.warn("log_level expiry: could not revert {} to {}: {}",
                loggerName.isEmpty() ? "<root>" : loggerName, baseline, e.getMessage());
        } finally {
            logLevelBumps.remove(bumpKey, bump);   // only if this bump is still the current one
        }
    }

    // --------------------------------------------------- D14: native boundary triage

    private Path resolveHsErrFile(JsonNode arguments) throws Exception {
        String hsErrFile = getStringParam(arguments, "hsErrFile");
        if (hsErrFile == null || hsErrFile.isBlank()) {
            throw new IllegalArgumentException("needs hsErrFile — the path to the crashed "
                + "JVM's hs_err_pid<pid>.log. There is no sessionId here: the process that "
                + "crashed is gone.");
        }
        Path path = Path.of(hsErrFile);
        if (!Files.exists(path)) {
            throw new java.io.FileNotFoundException("No hs_err file at '" + hsErrFile + "'.");
        }
        return path;
    }

    private ToolResponse nativeHsErr(JsonNode arguments) {
        try {
            Path hsErrFile = resolveHsErrFile(arguments);
            Map<String, Object> parsed = HsErrParser.parse(hsErrFile);
            return ToolResponse.success(parsed, ResponseMeta.builder()
                .steering("native_nmt reads just the memory-tracking section; native_handoff "
                    + "hands this crash to a configured gdb/lldb for a deeper backtrace.")
                .build());
        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("hsErrFile", "native_hs_err " + e.getMessage());
        } catch (java.io.FileNotFoundException e) {
            return ToolResponse.error("HSERR_FILE_NOT_FOUND", e.getMessage(), null);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private ToolResponse nativeNmt(JsonNode arguments) {
        try {
            Path hsErrFile = resolveHsErrFile(arguments);
            Map<String, Object> parsed = HsErrParser.parse(hsErrFile);
            Map<String, Object> data = new LinkedHashMap<>(
                (Map<String, Object>) parsed.get("nativeMemoryTracking"));
            data.put("hsErrFile", hsErrFile.toString());
            if (parsed.get("hostMemory") != null) {
                data.put("hostMemory", parsed.get("hostMemory"));
            }
            return ToolResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("hsErrFile", "native_nmt " + e.getMessage());
        } catch (java.io.FileNotFoundException e) {
            return ToolResponse.error("HSERR_FILE_NOT_FOUND", e.getMessage(), null);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private ToolResponse nativeHandoff(JsonNode arguments) {
        try {
            Path hsErrFile = resolveHsErrFile(arguments);
            String adapterCommand = getStringParam(arguments, "adapterCommand", DEFAULT_ADAPTER_COMMAND);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hsErrFile", hsErrFile.toString());
            data.put("adapterCommand", adapterCommand);

            if (!GdbAdapter.isAvailable(adapterCommand)) {
                data.put("available", false);
                data.put("why", "'" + adapterCommand + "' was not found (or did not run) on "
                    + "this machine. native_hs_err already gives the crashing thread's own "
                    + "Java/native frames without any external tool — this adapter is an "
                    + "OPTIONAL step beyond that baseline.");
                return ToolResponse.success(data);
            }
            data.put("available", true);

            Map<String, Object> hsErrParsed = HsErrParser.parse(hsErrFile);
            String corePathParam = getStringParam(arguments, "corePath");
            Path corePath = corePathParam != null && !corePathParam.isBlank()
                ? Path.of(corePathParam)
                : hsErrParsed.get("coreDumpPath") != null
                    ? Path.of((String) hsErrParsed.get("coreDumpPath"))
                    : null;

            if (corePath == null || !Files.exists(corePath)) {
                data.put("correlated", false);
                data.put("why", corePath == null
                    ? "no corePath was given and the hs_err file named none"
                    : "no core dump file at '" + corePath + "' — core dumping may be disabled "
                        + "on this machine (ulimit -c / core_pattern).");
                return ToolResponse.success(data);
            }

            Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
            String backtrace = GdbAdapter.runBacktrace(adapterCommand, javaBinary, corePath,
                ADAPTER_TIMEOUT_SECONDS);
            List<Map<String, Object>> threads = GdbAdapter.parseBacktrace(backtrace);

            Set<String> hsErrResolvedSymbols = new HashSet<>();
            for (Map<String, Object> frame : (List<Map<String, Object>>) hsErrParsed.get("nativeFrames")) {
                if (frame.get("resolvedSymbol") != null) {
                    hsErrResolvedSymbols.add((String) frame.get("resolvedSymbol"));
                }
            }
            // hs_err spells a resolved frame "Unsafe_PutInt+0xa4"; gdb spells the same frame
            // "Unsafe_PutInt". Comparing them verbatim (as v2.13.0 did) can never match.
            int correlatedCount = GdbAdapter.correlate(threads, hsErrResolvedSymbols);

            data.put("correlated", true);
            data.put("corePath", corePath.toString());
            data.put("threads", threads);
            data.put("correlatedFrameCount", correlatedCount);
            return ToolResponse.success(data);
        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("hsErrFile", "native_handoff " + e.getMessage());
        } catch (java.io.FileNotFoundException e) {
            return ToolResponse.error("HSERR_FILE_NOT_FOUND", e.getMessage(), null);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse listArtifacts() {
        List<Map<String, Object>> all = artifacts.describeAll();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("artifacts", all);
        data.put("count", all.size());
        data.put("root", artifacts.root().toString());
        return ToolResponse.success(data, ResponseMeta.builder()
            .returnedCount(all.size())
            .steering("Shared with debug's artifacts — delete with "
                + "profile(action=artifact_delete, artifactId=…); expired ones are pruned.")
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
}
