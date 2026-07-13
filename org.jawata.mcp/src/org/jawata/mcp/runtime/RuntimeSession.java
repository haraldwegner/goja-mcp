package org.jawata.mcp.runtime;

import com.sun.jdi.VirtualMachine;
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
    private final Map<String, Object> capabilities;

    private volatile State state = State.LIVE;

    RuntimeSession(String id, Origin origin, String target, VirtualMachine vm,
                   Process process, Map<String, Object> capabilities) {
        this.id = id;
        this.origin = origin;
        this.target = target;
        this.vm = vm;
        this.process = process;
        this.capabilities = capabilities;
    }

    public VirtualMachine vm() {
        return vm;
    }

    public State state() {
        return state;
    }

    public Map<String, Object> capabilities() {
        return capabilities;
    }

    /** The pid of the JVM on the other end, when we can know it. */
    public long pid() {
        if (process != null) {
            return process.pid();
        }
        try {
            // JDI names the target "…[pid]" on HotSpot; absent that, we simply do not
            // claim to know — an invented pid is worse than an absent one.
            return -1;
        } catch (Exception e) {
            return -1;
        }
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
        described.put("capabilities", capabilities);
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
    }
}
