package org.jawata.mcp.tools.verification;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (Stage 6) — THE JDI-LAYER SPIKE. Before a line of debugger code is
 * written, prove the chain works headless in OUR runtime: launch a JVM with the
 * debug agent, attach, set a line breakpoint, hit it, and read a frame.
 *
 * <p>The structural finding came first: the whole Eclipse JDI stack
 * ({@code jdt.debug}, {@code debug.core}, {@code jdt.launching}) is ALREADY in
 * the dist — {@code jdt.launching} is already in our Require-Bundle, and the
 * other two ride in as its dependencies. {@code jdt.debug} requires no UI
 * bundle, and it carries the expression-evaluation engine
 * ({@code org.eclipse.jdt.debug.eval}) that D6 needs. Microsoft's
 * {@code java-debug} is a DAP adapter layered ON TOP of that same
 * {@code jdt.debug} — adopting it would add jars to obtain a protocol
 * translation we would throw away, since we speak MCP.
 *
 * <p>What remained was the question a manifest cannot answer: does the JDI
 * chain actually run inside our OSGi runtime? This test is that answer.</p>
 */
class DebugSpikeTest {

    /** JDWP prints this on startup when it binds a port for us. */
    private static final Pattern LISTENING =
        Pattern.compile("Listening for transport dt_socket at address: (\\d+)");

    @Test
    @DisplayName("SPIKE: launch with JDWP, attach, hit a line breakpoint, read the frame")
    void attachHitBreakpointReadFrame() throws Exception {
        Path work = Files.createTempDirectory("jawata-jdi-spike-");
        Path source = work.resolve("SpikeTarget.java");
        Files.writeString(source, """
            public class SpikeTarget {
                public static void main(String[] args) throws Exception {
                    int spun = 0;
                    for (int i = 0; i < 100; i++) {
                        spun = tick(i);          // the seam
                        Thread.sleep(20);
                    }
                    System.out.println("done " + spun);
                }

                static int tick(int i) {
                    int doubled = i * 2;         // LINE 12 — the breakpoint
                    return doubled;
                }
            }
            """);
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler()
            .run(null, null, null, "-g", "-d", work.toString(), source.toString());
        assertEquals(0, rc, "the spike target must compile");

        Process target = null;
        VirtualMachine vm = null;
        try {
            // Launch under the debug agent, suspended, letting the JVM pick the port.
            target = new ProcessBuilder(
                javaBin(), "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
                "-cp", work.toString(), "SpikeTarget")
                .redirectErrorStream(true)
                .start();

            int port = awaitPort(target);
            assertTrue(port > 0, "JDWP must announce its port");

            // ATTACH.
            vm = attach(port);
            assertNotNull(vm, "attach must yield a VM");
            assertTrue(vm.canBeModified(), "a debuggable VM: " + vm.name());

            // Ask to be told when our class loads, then breakpoint inside it.
            ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter("SpikeTarget");
            prepare.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            prepare.enable();
            vm.resume();

            ReferenceType spikeTarget = awaitClassPrepare(vm);
            assertNotNull(spikeTarget, "SpikeTarget must load");

            List<Location> atLine12 = spikeTarget.locationsOfLine(12);
            assertTrue(!atLine12.isEmpty(), "line 12 must be executable: " + spikeTarget.name());

            BreakpointRequest breakpoint =
                vm.eventRequestManager().createBreakpointRequest(atLine12.get(0));
            breakpoint.setSuspendPolicy(EventRequest.SUSPEND_ALL);
            breakpoint.enable();
            vm.resume();

            // HIT.
            BreakpointEvent hit = awaitBreakpoint(vm);
            assertNotNull(hit, "the breakpoint must be hit");
            assertEquals(12, hit.location().lineNumber(), "stopped where we asked");
            assertEquals("tick", hit.location().method().name(), "in the method we named");

            // READ THE FRAME — arguments and locals, the substance of D6.
            ThreadReference thread = hit.thread();
            StackFrame frame = thread.frame(0);
            LocalVariable argument = frame.visibleVariableByName("i");
            assertNotNull(argument, "the argument is visible (compiled with -g)");
            int i = ((com.sun.jdi.IntegerValue) frame.getValue(argument)).value();
            assertTrue(i >= 0 && i < 100, "a real argument value: " + i);

            assertEquals("SpikeTarget.tick", hit.location().declaringType().name()
                + "." + hit.location().method().name());
        } finally {
            if (vm != null) {
                try {
                    vm.dispose();
                } catch (Exception ignored) {
                    // the VM may already be gone; the reap below is what matters
                }
            }
            if (target != null) {
                target.descendants().forEach(ProcessHandle::destroyForcibly);
                target.destroyForcibly();
                target.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    // ------------------------------------------------------------- plumbing

    private static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** JDWP announces its port on stdout; a launch that never binds is a failure. */
    private static int awaitPort(Process target) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        BufferedReader out = new BufferedReader(new InputStreamReader(target.getInputStream()));
        while (System.currentTimeMillis() < deadline) {
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

    private static VirtualMachine attach(int port) throws Exception {
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

    private static ReferenceType awaitClassPrepare(VirtualMachine vm) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            EventSet events = vm.eventQueue().remove(1000);
            if (events == null) {
                continue;
            }
            for (Event event : events) {
                if (event instanceof ClassPrepareEvent prepared) {
                    return prepared.referenceType();
                }
            }
            events.resume();
        }
        return null;
    }

    private static BreakpointEvent awaitBreakpoint(VirtualMachine vm) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            EventSet events = vm.eventQueue().remove(1000);
            if (events == null) {
                continue;
            }
            for (Event event : events) {
                if (event instanceof BreakpointEvent hit) {
                    return hit;
                }
            }
            events.resume();
        }
        return null;
    }
}
