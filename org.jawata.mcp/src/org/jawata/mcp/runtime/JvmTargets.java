package org.jawata.mcp.runtime;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 24 (D5) — finding, starting, and connecting to the JVMs on this
 * machine. The three ways a debug session begins.
 *
 * <p>Attaching is the default: ORB/JATS runs long headless sessions on the sim
 * box, and the interesting state is the state it is already in. Launching exists
 * for fixtures and replays.</p>
 */
public final class JvmTargets {

    private static final Logger log = LoggerFactory.getLogger(JvmTargets.class);

    /** JDWP announces its port on startup when we let the JVM choose one. */
    private static final Pattern LISTENING =
        Pattern.compile("Listening for transport dt_socket at address: (\\d+)");

    private JvmTargets() {
    }

    /**
     * The local JVMs, as the JDK itself sees them. Our own process is excluded —
     * offering to debug the debugger is a trap, not a feature.
     */
    public static List<Map<String, Object>> discover() {
        List<Map<String, Object>> found = new ArrayList<>();
        long self = ProcessHandle.current().pid();
        for (com.sun.tools.attach.VirtualMachineDescriptor vmd
                : com.sun.tools.attach.VirtualMachine.list()) {
            try {
                long pid = Long.parseLong(vmd.id());
                if (pid == self) {
                    continue;
                }
                Map<String, Object> jvm = new LinkedHashMap<>();
                jvm.put("pid", pid);
                jvm.put("displayName", vmd.displayName());
                // Say up front whether this one can be attached to at all. A JVM that
                // started without a debug agent can never be given one, so offering it
                // without saying so invites a failure we could have predicted.
                boolean debuggable = debugAddress(pid) != null;
                jvm.put("debuggable", debuggable);
                if (!debuggable) {
                    jvm.put("why", "started without a debug agent; one cannot be added to a "
                        + "running JVM. Relaunch it under the dev/sim preset to debug it.");
                }
                found.add(jvm);
            } catch (NumberFormatException e) {
                log.debug("Skipping a JVM with a non-numeric id: {}", vmd.id());
            }
        }
        return found;
    }

    /** Launch a JVM under the dev/sim preset and connect to it. */
    public static VirtualMachine launch(List<String> command, Path workingDirectory,
                                        List<String> extraJvmArgs, Process[] outProcess)
            throws Exception {
        List<String> full = new ArrayList<>();
        full.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        // Held before its first instruction: the caller arms breakpoints, THEN starts it.
        // See DevSimPreset#jvmArgsForLaunch — a launched target that is already running
        // has already run past whatever you wanted to see.
        full.addAll(DevSimPreset.jvmArgsForLaunch());
        if (extraJvmArgs != null) {
            full.addAll(extraJvmArgs);
        }
        full.addAll(command);

        ProcessBuilder builder = new ProcessBuilder(full).redirectErrorStream(true);
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        Process process = builder.start();
        outProcess[0] = process;

        BufferedReader out =
            new BufferedReader(new InputStreamReader(process.getInputStream()));
        int port = awaitPort(process, out);
        if (port <= 0) {
            process.destroyForcibly();
            throw new IllegalStateException(
                "the launched JVM never announced a debug port — it is not debuggable");
        }
        // Keep draining stdout/stderr for the life of the target. A pipe holds ~64KB; a
        // program that writes past that with nobody reading blocks in write() FOREVER —
        // so a chatty target (a JATS replay logging per event, say) would stall the moment
        // its output filled the buffer, before doing anything the debugger came to see.
        // We stopped reading at the JDWP banner and never resumed. Sprint-24 audit (T1.6).
        drainForLife(process, out);
        return attachToPort(port);
    }

    /** Read and discard the target's output until it exits, so its stdout pipe never fills. */
    private static void drainForLife(Process process, BufferedReader alreadyOpen) {
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = alreadyOpen) {
                while (reader.readLine() != null) {
                    // Discarded on purpose: the target's console is its own (the dev/sim
                    // preset quiets JIT/GC logging); we drain only to keep the pipe flowing.
                    // If a future feature wants this output, tee it to the session here.
                }
            } catch (IOException e) {
                log.debug("launched target output stream ended: {}", e.getMessage());
            }
        }, "jawata-target-stdout-drain");
        drain.setDaemon(true);
        drain.start();
    }

    /**
     * Attach to a JVM already running on this machine — <b>if it was started with
     * the debug agent</b>.
     *
     * <p>It cannot be given one now. OpenJDK's JDWP agent has no
     * {@code Agent_OnAttach} entry point, so debuggability is decided at launch and
     * cannot be retrofitted: a JVM that started without {@code -agentlib:jdwp} will
     * never be debuggable, for as long as it lives. (Verified against the runtime,
     * Sprint 24 Stage 7 — the JVM says so itself.)</p>
     *
     * <p>That is why the dev/sim preset exists, and it is also what makes this
     * sprint's safety model structural rather than merely stated: a production JVM,
     * started without the preset, is not debuggable AT ALL. The dangerous action is
     * not refused by policy — it is unreachable by construction.</p>
     */
    public static VirtualMachine attach(long pid) throws Exception {
        String address = debugAddress(pid);
        if (address == null) {
            throw new NotDebuggable(pid);
        }
        return attachToPort(Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)));
    }

    /** A JVM that was never prepared for a debugger, and now never can be. */
    public static final class NotDebuggable extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public NotDebuggable(long pid) {
            super("JVM " + pid + " was not started with a debug agent, and one cannot be "
                + "added to a running JVM (OpenJDK's JDWP agent has no attach entry point). "
                + "Debuggability is decided at launch: start it under the dev/sim preset "
                + "(debug(action=launch)), or add -agentlib:jdwp to however it is started.");
        }
    }

    /** Where this JVM is listening for a debugger, or null if it never can. */
    public static String debugAddress(long pid) {
        try {
            com.sun.tools.attach.VirtualMachine attached =
                com.sun.tools.attach.VirtualMachine.attach(String.valueOf(pid));
            try {
                String address = attached.getAgentProperties()
                    .getProperty("sun.jdwp.listenerAddress");
                return address != null && address.contains(":") ? address : null;
            } finally {
                attached.detach();
            }
        } catch (Exception e) {
            log.debug("Probing pid {} for a debug agent failed: {}", pid, e.getMessage());
            return null;
        }
    }

    /** The target's own system properties — what it says about itself. */
    public static Map<String, String> systemProperties(long pid) {
        Map<String, String> properties = new LinkedHashMap<>();
        try {
            com.sun.tools.attach.VirtualMachine attached =
                com.sun.tools.attach.VirtualMachine.attach(String.valueOf(pid));
            try {
                attached.getSystemProperties()
                    .forEach((k, v) -> properties.put(String.valueOf(k), String.valueOf(v)));
            } finally {
                attached.detach();
            }
        } catch (Exception e) {
            log.debug("Reading system properties of pid {} failed: {}", pid, e.getMessage());
        }
        return properties;
    }

    /** What the debugger can actually do on this VM — asked, never assumed. */
    public static Map<String, Boolean> jdiCapabilities(VirtualMachine vm) {
        Map<String, Boolean> can = new LinkedHashMap<>();
        can.put("canRedefineClasses", vm.canRedefineClasses());
        can.put("canPopFrames", vm.canPopFrames());
        can.put("canWatchFieldAccess", vm.canWatchFieldAccess());
        can.put("canWatchFieldModification", vm.canWatchFieldModification());
        can.put("canForceEarlyReturn", vm.canForceEarlyReturn());
        can.put("canGetInstanceInfo", vm.canGetInstanceInfo());
        return can;
    }

    static VirtualMachine attachToPort(int port) throws Exception {
        AttachingConnector socket = Bootstrap.virtualMachineManager().attachingConnectors()
            .stream()
            .filter(c -> "dt_socket".equals(c.transport().name()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no dt_socket attaching connector"));

        Map<String, Connector.Argument> args = socket.defaultArguments();
        args.get("hostname").setValue("127.0.0.1");
        args.get("port").setValue(String.valueOf(port));
        return socket.attach(args);
    }

    private static int awaitPort(Process process, BufferedReader out) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && process.isAlive()) {
            String line = out.readLine();
            if (line == null) {
                break;
            }
            Matcher m = LISTENING.matcher(line);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }
}
