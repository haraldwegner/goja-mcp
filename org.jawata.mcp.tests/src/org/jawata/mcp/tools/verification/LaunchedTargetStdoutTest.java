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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 audit (2026-07-14, T1.6) — a launched target that writes MORE than a pipe buffer
 * to stdout must keep running. The launcher read stdout only until the JDWP banner and then
 * stopped, so a program past ~64KB of output blocked in {@code write()} forever — and a
 * {@code debug(action=replay)} on a real JATS journal replay that logs per event (the D9
 * flagship case) would stall before reaching its first violation and report, honestly but
 * wrongly, "no violation yet — inconclusive".
 *
 * <p>The proof is a breakpoint placed INSIDE the loop that runs only AFTER the ~1 MB flood.
 * It can hit if and only if the target got past the flood — i.e. if and only if stdout was
 * being drained.</p>
 */
class LaunchedTargetStdoutTest {

    private static final String CHATTY = "com.example.debug.ChattyTarget";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool tool;
    private ObjectMapper om;
    private String sessionId;
    private List<String> source;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        tool = new DebugTool(() -> service, sessions);
        om = new ObjectMapper();

        Path file = service.getProjectRoot()
            .resolve("src/main/java/com/example/debug/ChattyTarget.java");
        source = Files.readAllLines(file);

        Path classes = Files.createTempDirectory("jawata-chatty-");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", classes.toString(), file.toString()),
            "the chatty fixture must compile");

        ObjectNode launch = action("launch");
        launch.put("mainClass", CHATTY);
        launch.put("classpath", classes.toString());
        ToolResponse launched = tool.execute(launch);
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        sessionId = (String) data(launched).get("sessionId");
    }

    @AfterEach
    void tearDown() {
        sessions.closeAll();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode action(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    private ObjectNode onSession(String name) {
        ObjectNode args = action(name);
        args.put("sessionId", sessionId);
        return args;
    }

    private int lineOf(String snippet) {
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i).contains(snippet)) {
                return i + 1;
            }
        }
        throw new AssertionError("the fixture no longer contains: " + snippet);
    }

    @Test
    @DisplayName("a target that floods stdout still reaches the code AFTER the flood")
    void aChattyTargetIsNotThrottledByAnUndrainedPipe() {
        // A breakpoint inside the post-flood loop. It can ONLY hit if the ~1 MB of stdout
        // before it drained; on an undrained pipe the target is wedged in println() and this
        // line is never reached.
        ObjectNode bp = onSession("breakpoint_set");
        bp.put("kind", "line");
        bp.put("className", CHATTY);
        bp.put("line", lineOf("counter++;"));
        assertTrue(tool.execute(bp).isSuccess());

        assertTrue(tool.execute(onSession("resume")).isSuccess());

        ObjectNode wait = onSession("wait");
        wait.put("timeoutMillis", 20_000);
        ToolResponse r = tool.execute(wait);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> hit = data(r);
        assertEquals(Boolean.TRUE, hit.get("hit"),
            "the breakpoint AFTER the stdout flood must be reached — if it is not, the target "
                + "is blocked in write() on a pipe nobody is draining: " + hit);
        assertEquals("main", hit.get("method"), "got: " + hit);
    }
}
