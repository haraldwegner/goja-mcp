package org.goja.mcp.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.FindQualityIssueTool;
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
 * Sprint 20 — the family filter on {@code find_quality_issue}. Families are
 * catalog registration tags ({@code quality}/{@code fowler}/{@code solid}); a
 * {@code family} run merges every detector in that family, and a kind requested
 * under the wrong family is rejected. At Stage 0 the {@code solid} family is
 * exactly the four tagged Fowler kinds (the SOLID detectors arrive in Stages 1–4).
 */
class FamilyProjectionTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("schema advertises the three families")
    void schema_lists_families() {
        Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
        Map<String, Object> family = (Map<String, Object>) props.get("family");
        assertEquals(List.of("quality", "fowler", "solid"), family.get("enum"));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("family=solid runs the tagged set and merges findings")
    void solid_family_runs_the_tagged_set() {
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "solid");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "family=solid must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<String> kinds = (List<String>) data.get("kinds");
        // the four tagged Fowler kinds (re-framed, not re-detected) are always in solid;
        // the net-new SOLID detectors (dip, then isp/srp_cohesion/lsp) join as stages land.
        assertTrue(kinds.containsAll(List.of(
                "incomplete_delegation", "refused_bequest", "divergent_change", "shotgun_surgery")),
            "solid family must include the four tagged Fowler kinds: " + kinds);
        assertTrue(kinds.contains("dip"), "dip joined the solid family in Stage 1: " + kinds);
        assertTrue(data.containsKey("findings"), "family run merges a findings list");
    }

    @Test
    @DisplayName("a kind outside the requested family is rejected")
    void kind_outside_family_rejected() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "naming");
        args.put("family", "solid");
        assertFalse(tool.execute(args).isSuccess(), "naming is not in the solid family");
    }

    @Test
    @DisplayName("an unknown family, and neither kind nor family, are rejected")
    void invalid_requests_rejected() {
        ObjectNode bogus = mapper.createObjectNode();
        bogus.put("family", "nope");
        assertFalse(tool.execute(bogus).isSuccess(), "unknown family rejected");

        ObjectNode empty = mapper.createObjectNode();
        assertFalse(tool.execute(empty).isSuccess(), "neither kind nor family rejected");
    }

    @Test
    @DisplayName("a single kind still works (no family)")
    void single_kind_still_works() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "naming");
        assertTrue(tool.execute(args).isSuccess(), "single-kind path unchanged");
    }
}
