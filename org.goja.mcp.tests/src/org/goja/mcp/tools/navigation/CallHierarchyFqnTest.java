package org.goja.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.GetCallHierarchyTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a P2-b — get_call_hierarchy(direction=incoming) accepts an FQN member
 * symbol instead of file:line, mirroring find_references. On the retarget fixture,
 * Service.v1 is called from two sites in Client.
 */
class CallHierarchyFqnTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetCallHierarchyTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("retarget");
        tool = new GetCallHierarchyTool(() -> service);
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("incoming by FQN symbol returns the callers — no file:line needed")
    void incoming_bySymbol_returnsCallers() {
        ObjectNode args = mapper.createObjectNode();
        args.put("direction", "incoming");
        args.put("symbol", "com.example.Service#v1");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "FQN member addressing must resolve; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("v1", data.get("method"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> callers = (List<Map<String, Object>>) data.get("callers");
        assertFalse(callers.isEmpty(), "Service.v1 has callers in Client: " + data);
        assertTrue(((Number) data.get("totalCallers")).intValue() >= 1);
    }

    @Test
    @DisplayName("incoming by an unknown FQN symbol is a clean not-found")
    void incoming_bySymbol_unknown() {
        ObjectNode args = mapper.createObjectNode();
        args.put("direction", "incoming");
        args.put("symbol", "com.example.Nope#nope");
        assertFalse(tool.execute(args).isSuccess(), "an unresolvable symbol must not succeed");
    }
}
