package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.InspectTool;
import org.jawata.mcp.tools.SearchSymbolsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D3 + D4) — the two halves of turning search into memory.
 *
 * <p><b>D3:</b> a search that lands an exact-name hit teaches its own address,
 * so a found symbol becomes remembered knowledge instead of a search repeated
 * every session. <b>D4:</b> a fresh session can ask for the workspace's
 * landmarks — the head start a human has from having worked here.</p>
 */
class KeyTeachingAndLandmarksTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper om;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        om = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    // ------------------------------------------------------------------ D3

    @Test
    @DisplayName("D3: an EXACT-name hit teaches the direct address")
    void exactHitTeachesTheAddress() {
        SearchSymbolsTool tool = new SearchSymbolsTool(() -> service);
        ObjectNode args = om.createObjectNode();
        args.put("query", "Calculator");
        args.put("kind", "Class");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String steering = r.getMeta().getSteering();
        assertNotNull(steering, "an exact hit must teach its address");
        assertTrue(steering.contains("symbol=\"com.example.Calculator\""),
            "it names the FQN to use next time: " + steering);
        assertTrue(steering.contains("survives file moves"),
            "and says WHY the name is the key: " + steering);
    }

    @Test
    @DisplayName("D3: a WILDCARD sweep teaches nothing — there is no single address")
    void wildcardTeachesNothing() {
        SearchSymbolsTool tool = new SearchSymbolsTool(() -> service);
        ObjectNode args = om.createObjectNode();
        args.put("query", "Calc*");
        args.put("kind", "Class");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        String steering = r.getMeta() == null ? null : r.getMeta().getSteering();
        assertTrue(steering == null || !steering.contains("Address this directly"),
            "a wildcard sweep has no one address to teach: " + steering);
    }

    @Test
    @DisplayName("D3: the teach line is computed from the rows, not guessed")
    void teachesOnlyWhatItFound() {
        // No exact row -> nothing taught (the helper's own contract).
        assertNull(SearchSymbolsTool.teachTheAddress("Calculator",
            List.of(Map.of("name", "CalculatorTest", "qualifiedName", "com.example.CalculatorTest"))),
            "a near-miss is not an exact hit");
        // A member hit addresses Type#member — with the DECLARING TYPE'S FULL NAME, which is
        // the shape production actually emits (`containingTypeQualified`).
        String member = SearchSymbolsTool.teachTheAddress("add",
            List.of(Map.of("name", "add",
                "containingType", "Calculator",
                "containingTypeQualified", "com.example.Calculator")));
        assertNotNull(member);
        assertTrue(member.contains("com.example.Calculator#add"), "got: " + member);
    }

    @Test
    @DisplayName("D3: THE ADDRESS IT TEACHES ACTUALLY RESOLVES — a member hit, end to end")
    void theTaughtMemberAddressResolves() {
        // Sprint-24 audit: for a METHOD or FIELD hit the teach line was built from
        // `containingType`, which production fills with the declaring type's SIMPLE name
        // (getElementName()). So searching for `add` taught symbol="Calculator#add" — which
        // FqnResolver cannot resolve, and whose first use answered with a misleading
        // "SYMBOL_RELOCATED / it appears to have moved". The unit test above did not catch it
        // because it hand-built a row carrying a QUALIFIED containingType, a shape production
        // never emits: it asserted a fiction.
        //
        // This project's own recorded rule (experience store aebb1dad): any feature that
        // hands an agent a name to reuse must verify that name resolves through the SAME
        // path the agent will use. So that is what this test does — it takes what the tool
        // teaches and feeds it straight back in.
        SearchSymbolsTool search = new SearchSymbolsTool(() -> service);
        ObjectNode args = om.createObjectNode();
        args.put("query", "add");
        args.put("kind", "Method");

        ToolResponse r = search.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        String steering = r.getMeta().getSteering();
        assertNotNull(steering, "an exact member hit must teach its address: " + data(r));

        java.util.regex.Matcher taught = java.util.regex.Pattern
            .compile("symbol=\"([^\"]+)\"").matcher(steering);
        assertTrue(taught.find(), "the steering must carry a symbol= address: " + steering);
        String address = taught.group(1);
        assertTrue(address.contains("#"), "a member address is Type#member: " + address);
        assertTrue(address.contains("."),
            "and it carries the declaring type's FULL name — 'Calculator#add' resolves to "
                + "nothing: " + address);

        // AND IT MUST WORK. Hand it to a symbol= consumer exactly as an agent would. This is
        // the assertion that matters: not that the address has a particular spelling, but
        // that the key we handed over opens the door.
        org.jawata.mcp.tools.FindReferencesTool references =
            new org.jawata.mcp.tools.FindReferencesTool(() -> service);
        ObjectNode byName = om.createObjectNode();
        byName.put("kind", "references");
        byName.put("symbol", address);

        ToolResponse resolved = references.execute(byName);
        assertTrue(resolved.isSuccess(),
            "the address the search TAUGHT must resolve through the tools it told the agent "
                + "to use — otherwise we handed out a key that opens nothing: " + resolved.getError());
    }

    // ------------------------------------------------------------------ D4

    @Test
    @DisplayName("D4: a landmark whose type was RENAMED is never served from cache — it would not resolve")
    void aRenamedTypeIsNotServedAsAStaleLandmark() throws Exception {
        // Sprint-24 audit: the ranking was cached in a static map keyed on the Eclipse project
        // NAME alone and never invalidated (invalidate() had no callers anywhere). On a
        // long-lived resident the first ranking became permanent — so after a rename, the
        // landmarks call kept handing out the OLD name as orientation. A landmark you cannot
        // address is worse than no landmark: it is a confident pointer at nothing, and it
        // breaks the one invariant this feature's own test asserts.
        InspectTool inspect = new InspectTool(() -> service);
        ObjectNode args = om.createObjectNode();
        args.put("kind", "landmarks");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> before =
            (List<Map<String, Object>>) data(inspect.execute(args)).get("landmarks");
        assertFalse(before.isEmpty(), "the fixture has landmarks to begin with");
        assertTrue(before.stream().anyMatch(l -> "com.example.Calculator".equals(l.get("qualifiedName"))),
            "Calculator is one of them: " + before);

        // Rename it through the compiler-accurate path an agent would actually use.
        org.jawata.mcp.tools.RenameSymbolTool rename = new org.jawata.mcp.tools.RenameSymbolTool(
            () -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        ObjectNode renameArgs = om.createObjectNode();
        renameArgs.put("symbol", "com.example.Calculator");
        renameArgs.put("newName", "Reckoner");
        renameArgs.put("auto_apply", true);
        ToolResponse renamed = rename.execute(renameArgs);
        assertTrue(renamed.isSuccess(), "got: " + renamed.getError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> after =
            (List<Map<String, Object>>) data(inspect.execute(args)).get("landmarks");

        assertTrue(after.stream().noneMatch(l -> "com.example.Calculator".equals(l.get("qualifiedName"))),
            "the old name must NOT still be offered as a landmark: " + after);
        for (Map<String, Object> landmark : after) {
            assertNotNull(service.findType(String.valueOf(landmark.get("qualifiedName"))),
                "and EVERY landmark served must still resolve by its name: " + landmark);
        }
    }

    @Test
    @DisplayName("D4: landmarks name the load-bearing types, most-referenced first")
    void landmarksAreRankedByDependence() {
        InspectTool inspect = new InspectTool(() -> service);
        ObjectNode args = om.createObjectNode();
        args.put("kind", "landmarks");
        args.put("limit", 10);

        ToolResponse r = inspect.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> landmarks =
            (List<Map<String, Object>>) data(r).get("landmarks");
        assertFalse(landmarks.isEmpty(), "a real workspace has landmarks: " + data(r));
        assertTrue(landmarks.size() <= 10, "the limit is honored: " + landmarks.size());

        // Every landmark carries what an agent needs to go straight there.
        for (Map<String, Object> landmark : landmarks) {
            assertNotNull(landmark.get("qualifiedName"), "addressable by name: " + landmark);
            assertNotNull(landmark.get("references"), "ranked by dependence: " + landmark);
        }

        // Most-referenced FIRST — that is the whole point of the ordering.
        for (int i = 1; i < landmarks.size(); i++) {
            int previous = (Integer) landmarks.get(i - 1).get("references");
            int current = (Integer) landmarks.get(i).get("references");
            assertTrue(previous >= current,
                "descending by references: " + previous + " then " + current);
        }
        assertEquals(landmarks.size(), data(r).get("count"));

        // Dogfood (v2.11.0): a count that hit the search bound is a FLOOR, not a
        // count — it must say so. On jawata's own workspace a 200-cap saturated the
        // top six types, making the ranking among the most load-bearing types
        // arbitrary and the number a quiet lie.
        for (Map<String, Object> landmark : landmarks) {
            int references = (Integer) landmark.get("references");
            if (references >= 2000) {
                assertEquals(Boolean.TRUE, landmark.get("atLeast"),
                    "a saturated count must be flagged as a floor: " + landmark);
            } else {
                assertNull(landmark.get("atLeast"),
                    "an exact count must NOT claim to be a floor: " + landmark);
            }
        }
    }

    @Test
    @DisplayName("D4: a landmark is addressable by name with no search in between")
    void landmarksFeedStraightIntoNameAddressing() {
        InspectTool inspect = new InspectTool(() -> service);
        ObjectNode args = om.createObjectNode();
        args.put("kind", "landmarks");
        ToolResponse r = inspect.execute(args);
        assertTrue(r.isSuccess());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> landmarks =
            (List<Map<String, Object>>) data(r).get("landmarks");

        // EVERY landmark must feed straight into name addressing — that is the loop
        // D1..D4 exists to close. A landmark you cannot address by name (a secondary
        // type, say) would be worse than useless: it would teach a name that fails.
        for (Map<String, Object> landmark : landmarks) {
            ObjectNode members = om.createObjectNode();
            members.put("kind", "type_members");
            members.put("typeName", String.valueOf(landmark.get("qualifiedName")));
            ToolResponse followUp = inspect.execute(members);
            assertTrue(followUp.isSuccess(),
                "landmark " + landmark.get("qualifiedName")
                    + " must resolve by name with no search: " + followUp.getError());
        }
    }
}
