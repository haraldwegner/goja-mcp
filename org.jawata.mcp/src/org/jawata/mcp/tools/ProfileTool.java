package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.runtime.RuntimeSession;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.runtime.profile.Jcmd;
import org.jawata.mcp.runtime.profile.JfrParser;
import org.jawata.mcp.runtime.profile.ProfileParsers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final List<String> ACTIONS = List.of(
        "threads", "deadlock", "histogram", "gc", "nmt", "heap_dump",
        "sample", "jfr_dump", "hotspots",
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
            "description", "heap_dump: reachable objects only after a full GC (default "
                + "true, smaller); false includes unreachable garbage too."));
        properties.put("artifactId", Map.of("type", "string",
            "description", "artifact_delete: which artifact to remove. hotspots: which "
                + "JFR artifact to rank (from action=sample or action=jfr_dump)."));
        properties.put("durationSeconds", Map.of("type", "integer",
            "description", "sample: how long to record (default " + DEFAULT_SAMPLE_SECONDS
                + ", max " + MAX_SAMPLE_SECONDS + "). The call BLOCKS for this long."));
        properties.put("dimension", Map.of("type", "string",
            "enum", List.of("cpu", "alloc", "lock", "gc"),
            "description", "hotspots: which profile to rank by (default cpu)."));
        properties.put("offset", Map.of("type", "integer",
            "description", "hotspots: pagination offset (default 0)."));

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
