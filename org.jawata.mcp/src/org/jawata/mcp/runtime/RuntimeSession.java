package org.jawata.mcp.runtime;

import com.sun.jdi.VirtualMachine;
import org.jawata.mcp.runtime.debug.DebugController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Sprint 24 (D5) — one live conversation with one JVM. Held by the registry,
 * addressed by handle, and — the part that matters — always torn down.
 *
 * <p>A session knows how it came to exist, because teardown differs: a JVM we
 * LAUNCHED is ours to kill; a JVM we ATTACHED to is someone else's program and
 * we only ever let go of it. Killing a process we did not start would be the
 * worst kind of surprise.</p>
 */
public final class RuntimeSession {

    private static final Logger log = LoggerFactory.getLogger(RuntimeSession.class);

    /** How the session began — and therefore how it must end. */
    public enum Origin {
        /** We started this JVM. On teardown we kill it and its children. */
        LAUNCHED,
        /** Someone else's JVM. On teardown we detach and leave it running. */
        ATTACHED
    }

    public enum State { LIVE, DETACHED, TERMINATED }

    public final String id;
    public final Origin origin;
    public final long startedMillis = System.currentTimeMillis();
    public final String target;

    private final VirtualMachine vm;
    /** Only for LAUNCHED sessions; null when we attached to a foreign JVM. */
    private final Process process;
    private volatile Map<String, Object> capabilities;
    private final long attachedPid;
    /** The flight-recording repository WE pinned for a launched JVM — deleted on teardown; null for attach. */
    private final java.nio.file.Path jfrRepository;

    private volatile State state = State.LIVE;
    private volatile DebugController debugger;

    RuntimeSession(String id, Origin origin, String target, VirtualMachine vm,
                   Process process, Map<String, Object> capabilities, long attachedPid) {
        this(id, origin, target, vm, process, capabilities, attachedPid, null);
    }

    RuntimeSession(String id, Origin origin, String target, VirtualMachine vm,
                   Process process, Map<String, Object> capabilities, long attachedPid,
                   java.nio.file.Path jfrRepository) {
        this.id = id;
        this.origin = origin;
        this.target = target;
        this.vm = vm;
        this.process = process;
        this.capabilities = capabilities;
        this.attachedPid = attachedPid;
        this.jfrRepository = jfrRepository;
    }

    public VirtualMachine vm() {
        return vm;
    }

    /**
     * The interactive debugger for this session, created on first use.
     *
     * <p>Lazy on purpose: the debugger starts an event-pump thread that owns the JDI
     * event queue for the session's whole life. A session that only ever reads
     * capabilities should not pay for one, and — more importantly — nothing else may
     * ever read that queue, so there is exactly one, created here.</p>
     */
    public synchronized DebugController debugger() {
        if (debugger == null) {
            debugger = new DebugController(vm, pid(), origin == Origin.LAUNCHED);
        }
        return debugger;
    }

    /**
     * True while a JVM we launched is still held before its first instruction.
     *
     * <p>A launched target starts suspended so that breakpoints can be armed before any
     * code runs. Until it is started, "the program has not printed anything yet" is not
     * a symptom — it is the state.</p>
     */
    public boolean awaitingStart() {
        DebugController active = debugger;
        return origin == Origin.LAUNCHED && (active == null || active.isAwaitingStart());
    }

    public State state() {
        return state;
    }

    /**
     * What this JVM can do — read from the JVM, and re-read once it can answer.
     *
     * <p>A target held before its first instruction cannot service an attach query, so
     * its preset capabilities come back UNKNOWN rather than false. The moment it is
     * running and somebody asks, we get the real answer and keep it.</p>
     */
    public Map<String, Object> capabilities() {
        if (Boolean.TRUE.equals(capabilities.get("capabilitiesUnread")) && !awaitingStart()) {
            Map<String, String> properties = JvmTargets.systemProperties(pid());
            if (!properties.isEmpty()) {
                // Now that it is running it can be ASKED (jcmd), not just inferred from the
                // marker. This is where a launched target's real capabilities arrive. (T2.7)
                capabilities = DevSimPreset.report(properties, JvmTargets.jdiCapabilities(vm),
                    CapabilityProbe.probe(pid()));
            }
        }
        return capabilities;
    }

    /**
     * The pid of the JVM on the other end, or -1 when we genuinely cannot know.
     *
     * <p>We launched it (we hold the {@link Process}) or we attached to it by pid — in
     * both cases the number is known, not inferred. If it ever is not, we return -1
     * rather than invent one: an invented pid would be handed to {@code jcmd}, and
     * that lands on somebody else's process.</p>
     */
    public long pid() {
        if (process != null) {
            return process.pid();
        }
        return attachedPid;
    }

    public Map<String, Object> describe() {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("sessionId", id);
        described.put("origin", origin.name().toLowerCase());
        described.put("state", state.name().toLowerCase());
        described.put("target", target);
        if (pid() > 0) {
            described.put("pid", pid());
        }
        described.put("upSeconds", (System.currentTimeMillis() - startedMillis) / 1000);
        if (awaitingStart()) {
            described.put("awaitingStart", true);
            described.put("note", "The program is held BEFORE its first instruction. Arm your "
                + "breakpoints, then debug(action=resume) to start it — so nothing you "
                + "wanted to see has already gone past.");
        }
        // Where THIS session's continuous flight recording writes its chunks — a dir we own
        // and delete on teardown (D5's "no recording left behind"). Present only for a JVM we
        // launched; an attached foreign JVM's recording, if any, is not ours to place or remove.
        if (jfrRepository != null) {
            described.put("recordingRepository", jfrRepository.toString());
        }
        described.put("capabilities", capabilities());
        return described;
    }

    /**
     * End the session. A JVM we launched is killed (process tree and all); a JVM we
     * attached to is released and keeps running — it was never ours.
     *
     * <p>Idempotent, and it never throws: teardown that can fail is teardown that
     * leaks.</p>
     */
    public void close() {
        if (state != State.LIVE) {
            return;
        }
        // Before anything else: release whatever the debugger suspended. Disposing the
        // connection to a JVM frozen at our breakpoint would strand it there forever —
        // and for an ATTACHED session that is somebody else's running program.
        DebugController active = debugger;
        if (active != null) {
            try {
                active.close();
            } catch (Exception e) {
                log.warn("closing the debugger for {} failed: {}", id, e.getMessage());
            }
        }
        try {
            vm.dispose();
        } catch (Exception e) {
            // The VM may already be gone — that is a fine outcome for a dispose.
            log.debug("Disposing the JDI connection for {} failed: {}", id, e.getMessage());
        }
        if (origin == Origin.LAUNCHED && process != null) {
            try {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("Reaping the launched JVM for {} failed: {}", id, e.getMessage());
            }
            state = State.TERMINATED;
        } else {
            state = State.DETACHED;
        }
        // The flight-recording repository WE pinned for a launched JVM is ours to remove.
        // We just SIGKILLed the JVM (destroyForcibly), so no shutdown hook ran and nothing
        // else will ever clean its chunks — D5's "no recording left behind" depends on this.
        // No-op for an attached session (jfrRepository is null). Sprint-24 audit (T2.6).
        RuntimeSessionRegistry.deleteRecursively(jfrRepository);
    }
}
