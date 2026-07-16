package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sprint 25 (spec D6a) — the ECLIPSE-RCP launch shape + {@code preset_args}.
 *
 * <p>The RCP fixture is jawata's OWN Equinox boot: a launcher script honoring
 * the eclipse launcher's argument contract (program args, then
 * {@code --launcher.appendVmargs -vmargs} + VM args) records its argv and
 * execs the very jar this test framework was booted from. The target is HELD
 * before its first instruction, so its {@code -data}/{@code -configuration}
 * are never even opened — the "prod config untouched" guarantee made
 * literal.</p>
 */
class RcpLaunchShapeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool tool;
    private ObjectMapper om;
    private String sessionId;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        sessions = new RuntimeSessionRegistry();
        tool = new DebugTool(() -> service, sessions);
        om = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (sessionId != null) {
            ObjectNode detach = om.createObjectNode();
            detach.put("action", "detach");
            detach.put("sessionId", sessionId);
            tool.execute(detach);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ok(ObjectNode args) {
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "refused: " + (r.getError() != null
            ? r.getError().getCode() + " / " + r.getError().getMessage() : "?"));
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("rcp shape: launcher gets program args + appendVmargs + preset behind -vmargs; held; scratch areas untouched; detach cleans the JFR repo")
    void rcpShape_launchesHeldUnderPreset_prodConfigUntouched() throws Exception {
        // jawata's own boot jar — the JVM running this test was started with -jar,
        // so its classpath IS that jar. If a future runner boots differently, the
        // fixture premise is gone: skip rather than fake it.
        String bootJar = System.getProperty("java.class.path");
        assumeTrue(bootJar != null && !bootJar.contains(java.io.File.pathSeparator)
                && bootJar.endsWith(".jar") && Files.exists(Path.of(bootJar)),
            "test framework was not booted from a single jar; RCP fixture premise absent");

        Path scratch = Files.createTempDirectory("jawata-rcp-shape-");
        Path argvLog = scratch.resolve("launcher-argv.txt");
        Path ini = scratch.resolve("fixture.ini");
        Files.writeString(ini, "-vmargs\n-Dfixture.ini.own.arg=true\n");
        Path configArea = scratch.resolve("config");
        Path workspaceArea = scratch.resolve("ws");

        // The fake native launcher: record argv, split at -vmargs per the eclipse
        // launcher contract, exec the boot jar under the VM args. Program args are
        // NOT forwarded (the held target never parses args anyway) — they are the
        // assertion surface in the argv log.
        Path launcher = scratch.resolve("fake-rcp-launcher.sh");
        Files.writeString(launcher, """
            #!/bin/bash
            printf '%s\\n' "$@" > "%LOG%"
            VMARGS=()
            seen=0
            for a in "$@"; do
              if [ "$a" = "-vmargs" ]; then seen=1; continue; fi
              if [ "$seen" = 1 ]; then VMARGS+=("$a"); fi
            done
            exec java "${VMARGS[@]}" -jar "%JAR%"
            """.replace("%LOG%", argvLog.toString()).replace("%JAR%", bootJar));
        Files.setPosixFilePermissions(launcher, Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));

        ObjectNode launch = om.createObjectNode();
        launch.put("action", "launch");
        launch.put("launcherPath", launcher.toString());
        launch.put("iniPath", ini.toString());
        launch.put("configArea", configArea.toString());
        launch.put("workspaceArea", workspaceArea.toString());
        launch.putArray("vmargsExtra").add("-Dstage5.rcp.probe=true");
        Map<String, Object> data = ok(launch);
        sessionId = (String) data.get("sessionId");
        assertNotNull(sessionId);
        assertNotNull(data.get("capabilities"), "capability report must be present");

        // The launcher received the eclipse-shaped command.
        List<String> argv = Files.readAllLines(argvLog);
        int vmargsAt = argv.indexOf("-vmargs");
        assertTrue(vmargsAt > 0, "-vmargs must be present: " + argv);
        List<String> program = argv.subList(0, vmargsAt);
        List<String> vmargs = argv.subList(vmargsAt + 1, argv.size());
        assertEquals(List.of(
                "--launcher.ini", ini.toString(),
                "-configuration", configArea.toString(),
                "-data", workspaceArea.toString(),
                "--launcher.appendVmargs"),
            program, "program args (before -vmargs), appendVmargs last");
        assertTrue(vmargs.get(0).startsWith("-agentlib:jdwp=") && vmargs.get(0).contains("suspend=y"),
            "the preset leads the -vmargs block, held variant: " + vmargs);
        assertTrue(vmargs.stream().anyMatch(a -> a.startsWith("-XX:FlightRecorderOptions=repository=")),
            "the JFR repository is pinned: " + vmargs);
        assertEquals("-Dstage5.rcp.probe=true", vmargs.get(vmargs.size() - 1),
            "vmargsExtra appends AFTER the preset");

        // Held before its first instruction: the target never parsed its args, so
        // the scratch areas were never created — the product's own configuration
        // provably untouched.
        assertFalse(Files.exists(configArea), "-configuration area must be untouched");
        assertFalse(Files.exists(workspaceArea), "-data area must be untouched");

        Path jfrRepo = Path.of(vmargs.stream()
            .filter(a -> a.startsWith("-XX:FlightRecorderOptions=repository="))
            .findFirst().orElseThrow()
            .substring("-XX:FlightRecorderOptions=repository=".length()));
        assertTrue(Files.exists(jfrRepo), "the pinned JFR repo exists while the session lives");

        // Detach kills the launched target and cleans the repo (the T2.6 guarantee).
        ObjectNode detach = om.createObjectNode();
        detach.put("action", "detach");
        detach.put("sessionId", sessionId);
        ok(detach);
        sessionId = null;
        assertFalse(Files.exists(jfrRepo), "detach must delete the pinned JFR repository");
        assertFalse(Files.exists(configArea) || Files.exists(workspaceArea),
            "still untouched after the full cycle");
    }

    @Test
    @DisplayName("preset_args: paste-ready blocks, suspend=n, ini block appends")
    void presetArgs_pasteReadyBlocks() {
        ObjectNode args = om.createObjectNode();
        args.put("action", "preset_args");
        Map<String, Object> data = ok(args);

        String line = (String) data.get("jvmArgsLine");
        assertNotNull(line);
        assertTrue(line.contains("suspend=n"), "a pasted preset must NEVER hold the program");
        assertFalse(line.contains("suspend=y"), line);
        assertTrue(line.contains("-agentlib:jdwp=") && line.contains("127.0.0.1"),
            "loopback debug agent: " + line);
        assertTrue(line.contains("jawata.devsim.preset"), "the preset marker: " + line);

        String ini = (String) data.get("eclipseIniBlock");
        assertTrue(ini.startsWith("--launcher.appendVmargs\n-vmargs\n"),
            "the ini block must APPEND, not replace: " + ini);
        for (String arg : line.split(" ")) {
            assertTrue(ini.contains(arg + "\n"), "each arg on its own ini line: " + arg);
        }

        @SuppressWarnings("unchecked")
        List<String> instructions = (List<String>) data.get("instructions");
        assertTrue(instructions.stream().anyMatch(i -> i.contains(".ini")),
            "eclipse paste instruction present");
        assertTrue(instructions.stream().anyMatch(i -> i.contains("attach")),
            "the attach-afterwards pointer present");
    }
}
