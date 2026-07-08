package org.goja.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.AnalyzeTool;
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
 * Sprint 22a P1-c — the composed encapsulation audit catches the setter
 * false-pass that {@code find_field_writes} alone misses.
 */
class EncapsulationAuditTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("encapsulation");
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("analyze(kind=encapsulation) flags a private field mutated through its public setter")
    void composedAudit_flagsSetterFalsePass() {
        AnalyzeTool analyze = new AnalyzeTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "encapsulation");
        args.put("typeName", "com.example.Account");

        ToolResponse r = analyze.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();

        assertTrue(((Number) data.get("leakingFields")).intValue() >= 1,
            "at least the balance field must be flagged; data=" + data);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) data.get("fields");
        Map<String, Object> balance = fields.stream()
            .filter(f -> "balance".equals(f.get("field")))
            .findFirst().orElseThrow(() -> new AssertionError("no balance field report: " + fields));

        // The composed audit's verdict.
        assertEquals(Boolean.TRUE, balance.get("encapsulationLeak"),
            "balance is mutated by an external caller of setBalance: " + balance);
        assertTrue(((Number) balance.get("pokeSetCount")).intValue() >= 1,
            "poke-set must be non-empty: " + balance);

        @SuppressWarnings("unchecked")
        List<String> mutators = (List<String>) balance.get("mutatingMethods");
        assertTrue(mutators.contains("setBalance"), "setBalance is the mutator: " + balance);

        @SuppressWarnings("unchecked")
        List<String> externalCallers = (List<String>) balance.get("externalMutatorCallers");
        assertFalse(externalCallers.isEmpty(),
            "the external caller (External) of the setter must be reported: " + balance);

        // The find_field_writes blind spot: no direct external writer exists —
        // balance is only ever written inside Account. The audit still flags it.
        @SuppressWarnings("unchecked")
        List<String> directExternalWriters = (List<String>) balance.get("directExternalWriters");
        assertTrue(directExternalWriters.isEmpty(),
            "balance has no direct external writer (find_field_writes would say internal-only): " + balance);
    }
}
