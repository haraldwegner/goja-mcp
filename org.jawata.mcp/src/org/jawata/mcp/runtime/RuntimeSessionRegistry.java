package org.jawata.mcp.runtime;

import com.sun.jdi.VirtualMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sprint 24 (D5) — the live debug sessions, by handle. Mirrors Sprint 23's
 * {@code TestSessionRegistry}: a bounded map, a handle the caller keeps, and no
 * session that outlives the server.
 *
 * <p>The bound matters. A debug session pins a JVM connection and, when we
 * launched the target, a whole process — leaking those is not a slow memory
 * creep, it is a machine full of orphaned JVMs. So the count is capped and the
 * oldest dead session is evicted before a new one is admitted.</p>
 */
public final class RuntimeSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSessionRegistry.class);

    /** A debugger is used one or two targets at a time; this is generous. */
    private static final int MAX_SESSIONS = 8;

    private final Map<String, RuntimeSession> sessions = new ConcurrentHashMap<>();

    public RuntimeSessionRegistry() {
        // A session must not survive the server that owns it. If the process dies
        // with a launched JVM still attached, that JVM becomes an orphan nobody
        // knows to kill.
        Runtime.getRuntime().addShutdownHook(new Thread(this::closeAll, "jawata-runtime-reap"));
    }

    /** Attach to a JVM already running on this machine. */
    public RuntimeSession attach(long pid) throws Exception {
        evictIfFull();
        VirtualMachine vm = JvmTargets.attach(pid);
        // Ask the running target what it can actually do (jcmd), rather than infer it from
        // our marker — an attached JVM is the normal case and it may have been prepared
        // without our marker at all. Sprint-24 audit (T2.7).
        Map<String, Object> capabilities = DevSimPreset.report(
            JvmTargets.systemProperties(pid), JvmTargets.jdiCapabilities(vm),
            CapabilityProbe.probe(pid));
        return admit(new RuntimeSession(newId(), RuntimeSession.Origin.ATTACHED,
            "pid " + pid, vm, null, capabilities, pid));
    }

    /** Launch a JVM under the dev/sim preset and connect to it. */
    public RuntimeSession launch(List<String> command, Path workingDirectory,
                                 List<String> extraJvmArgs) throws Exception {
        evictIfFull();

        // Pin the flight-recording repository to a dir WE own, so we know exactly where the
        // continuous recording writes its chunks and can delete them on teardown. Without
        // this, the preset's continuous JFR writes to a JVM-chosen dir under tmp that is
        // cleaned only on an ORDERLY exit — and a launched target is SIGKILLed, so on every
        // launch+detach the chunks were left behind, forever. D5's "no recording left behind"
        // was unverified and plausibly false. Sprint-24 audit (T2.6).
        Path jfrRepo = Files.createTempDirectory("jawata-jfr-repo-");
        List<String> jvmArgs = new ArrayList<>();
        jvmArgs.add("-XX:FlightRecorderOptions=repository=" + jfrRepo);
        if (extraJvmArgs != null) {
            jvmArgs.addAll(extraJvmArgs);
        }

        Process[] out = new Process[1];
        VirtualMachine vm;
        try {
            vm = JvmTargets.launch(command, workingDirectory, jvmArgs, out);
        } catch (Exception e) {
            // A launch that failed halfway must not leave the JVM — or its repo — behind.
            if (out[0] != null) {
                out[0].descendants().forEach(ProcessHandle::destroyForcibly);
                out[0].destroyForcibly();
            }
            deleteRecursively(jfrRepo);
            throw e;
        }
        Process process = out[0];
        // Do NOT ask a target that is held before its first instruction what it can do:
        // the attach channel is serviced by the target itself, so the query cannot be
        // answered — and the half-finished handshake leaves an .attach_pid file behind.
        // The capabilities come back UNKNOWN (never false), and RuntimeSession re-reads
        // them from the JVM the moment it is running and somebody asks.
        Map<String, Object> capabilities = DevSimPreset.report(
            Map.of(), JvmTargets.jdiCapabilities(vm));
        return admit(new RuntimeSession(newId(), RuntimeSession.Origin.LAUNCHED,
            String.join(" ", command), vm, process, capabilities, process.pid(), jfrRepo));
    }

    /**
     * Launch an eclipse-rcp target under the dev/sim preset and connect to it.
     * The command is the NATIVE LAUNCHER's: program args first, then
     * {@code --launcher.appendVmargs -vmargs} followed by the preset (held
     * before its first instruction), the pinned JFR repository, and the
     * caller's extra VM args — command-line {@code -vmargs} plus
     * {@code appendVmargs} means the ini's own vmargs stay and ours append.
     */
    public RuntimeSession launchRcp(Path launcherPath, List<String> programArgs,
                                    List<String> extraVmArgs, Path workingDirectory)
            throws Exception {
        evictIfFull();

        Path jfrRepo = Files.createTempDirectory("jawata-jfr-repo-");
        List<String> command = new ArrayList<>();
        command.add(launcherPath.toString());
        if (programArgs != null) {
            command.addAll(programArgs);
        }
        command.add("--launcher.appendVmargs");
        command.add("-vmargs");
        command.addAll(DevSimPreset.jvmArgsForLaunch());
        command.add("-XX:FlightRecorderOptions=repository=" + jfrRepo);
        if (extraVmArgs != null) {
            command.addAll(extraVmArgs);
        }

        Process[] out = new Process[1];
        VirtualMachine vm;
        try {
            vm = JvmTargets.launchRaw(command, workingDirectory, out);
        } catch (Exception e) {
            if (out[0] != null) {
                out[0].descendants().forEach(ProcessHandle::destroyForcibly);
                out[0].destroyForcibly();
            }
            deleteRecursively(jfrRepo);
            throw e;
        }
        Process process = out[0];
        Map<String, Object> capabilities = DevSimPreset.report(
            Map.of(), JvmTargets.jdiCapabilities(vm));
        return admit(new RuntimeSession(newId(), RuntimeSession.Origin.LAUNCHED,
            String.join(" ", command), vm, process, capabilities, process.pid(), jfrRepo));
    }

    /** Best-effort recursive delete of a launched session's JFR repository on teardown. */
    static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // A file we cannot delete is a best-effort miss, not a failure.
                }
            });
        } catch (Exception ignored) {
            // The dir may already be gone — fine.
        }
    }

    public Optional<RuntimeSession> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> described = new ArrayList<>();
        for (RuntimeSession session : sessions.values()) {
            described.add(session.describe());
        }
        return described;
    }

    /** End one session and forget it. */
    public boolean close(String sessionId) {
        RuntimeSession session = sessions.remove(sessionId);
        if (session == null) {
            return false;
        }
        session.close();
        return true;
    }

    /** End every session — on shutdown, and in tests. */
    public void closeAll() {
        for (String id : List.copyOf(sessions.keySet())) {
            try {
                close(id);
            } catch (Exception e) {
                log.warn("Closing session {} failed: {}", id, e.getMessage());
            }
        }
    }

    public int size() {
        return sessions.size();
    }

    private RuntimeSession admit(RuntimeSession session) {
        sessions.put(session.id, session);
        return session;
    }

    private void evictIfFull() {
        if (sessions.size() < MAX_SESSIONS) {
            return;
        }
        // Dead sessions first — they hold nothing but a handle.
        sessions.values().stream()
            .filter(s -> s.state() != RuntimeSession.State.LIVE)
            .findFirst()
            .ifPresent(dead -> close(dead.id));

        if (sessions.size() >= MAX_SESSIONS) {
            throw new IllegalStateException(
                "too many live debug sessions (" + sessions.size() + "). Detach one first — "
                    + "each session holds a JVM connection, and a launched target holds a "
                    + "whole process.");
        }
    }

    private static String newId() {
        return "dbg-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
