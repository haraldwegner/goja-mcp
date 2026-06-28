package org.goja.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindDuplicateCodeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 14 Phase B.3 — {@code find_duplicate_code} clone-detection
 * contract tests. Drives against the simple-maven fixture's Animal.java
 * which has three structurally-identical method bodies (Animal.speak,
 * Animal.move, Dog.speak — all of shape
 * {@code void NAME() { System.out.println("…"); }}).
 */
class FindDuplicateCodeToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private FindDuplicateCodeTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new FindDuplicateCodeTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("detects obvious clones within project (Animal.speak / Animal.move / Dog.speak share normalized shape)")
    void detects_obvious_clone_within_project() {
        ObjectNode args = objectMapper.createObjectNode();
        // Use low minTokens so the short Animal.java methods qualify.
        args.put("minTokens", 5);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "find_duplicate_code must succeed; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("find_duplicate_code", data.get("operation"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
        assertNotNull(groups, "groups list must be present");

        // At least one group must contain BOTH Animal.speak and Animal.move
        // (their bodies are byte-identical except for the string literal,
        // which the normalizer collapses to STR). Other structurally-
        // identical methods may join the group from the wider fixture; we
        // only assert the speak+move pair lands together.
        boolean foundSpeakAndMoveTogether = groups.stream().anyMatch(g -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> instances = (List<Map<String, Object>>) g.get("instances");
            boolean hasSpeak = instances.stream().anyMatch(i -> "speak".equals(i.get("methodName")));
            boolean hasMove = instances.stream().anyMatch(i -> "move".equals(i.get("methodName")));
            return hasSpeak && hasMove;
        });
        assertTrue(foundSpeakAndMoveTogether,
            "expected Animal.speak and Animal.move to land in the same clone group; got groups=" + groups);
    }

    @Test
    @DisplayName("respects minTokens floor — short clones below the threshold are NOT grouped")
    void respects_minTokens_floor() {
        ObjectNode args = objectMapper.createObjectNode();
        // Set minTokens absurdly high so short Animal.java methods are excluded.
        args.put("minTokens", 1000);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
        assertTrue(groups.isEmpty(),
            "no method in simple-maven has 1000+ tokens; groups must be empty; got: " + groups);
    }

    @Test
    @DisplayName("dissimilar methods are not grouped (HelloWorld constructors vs setGreeting)")
    void dissimilar_methods_not_grouped() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("minTokens", 5);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");

        // No group should contain a method named "HelloWorld" (the
        // constructor) paired with one named "setGreeting" — they're
        // structurally distinct.
        boolean falseClone = groups.stream().anyMatch(g -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> instances = (List<Map<String, Object>>) g.get("instances");
            boolean hasCtor = instances.stream().anyMatch(i -> "HelloWorld".equals(i.get("methodName")));
            boolean hasSetter = instances.stream().anyMatch(i -> "setGreeting".equals(i.get("methodName")));
            return hasCtor && hasSetter;
        });
        assertTrue(!falseClone,
            "must not group dissimilar methods (HelloWorld ctor vs setGreeting); got groups=" + groups);
    }

    @Test
    @DisplayName("each clone-group instance carries the documented contract fields")
    void instanceShape_carriesContractFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("minTokens", 5);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
        assertTrue(!groups.isEmpty(), "must have at least one clone group at low minTokens");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> instances =
            (List<Map<String, Object>>) groups.get(0).get("instances");
        for (Map<String, Object> inst : instances) {
            assertNotNull(inst.get("filePath"), "instance must carry filePath");
            assertNotNull(inst.get("line"), "instance must carry line");
            assertNotNull(inst.get("methodName"), "instance must carry methodName");
            assertNotNull(inst.get("tokenCount"), "instance must carry tokenCount");
            assertNotNull(inst.get("similarity"), "instance must carry similarity");
            assertNotNull(inst.get("sourceProject"), "instance must carry sourceProject");
            assertEquals(1.0, ((Number) inst.get("similarity")).doubleValue(),
                "v1.8.0 MVP exact-match always yields similarity 1.0");
        }
    }

    @Test
    @DisplayName("clean-fixture invariant: even with no clones, the response shape is valid")
    void emptyResult_isStillValidShape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("minTokens", 1000);  // ensures no clones found
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("find_duplicate_code", data.get("operation"));
        assertEquals(0, ((Number) data.get("groupCount")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
        assertTrue(groups.isEmpty());
    }
}
