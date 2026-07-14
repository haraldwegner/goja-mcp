package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.FindRefsTool;
import org.jawata.mcp.tools.MoveTool;
import org.jawata.mcp.tools.RenameSymbolTool;
import org.jawata.mcp.tools.shared.ResolveOrRelocate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D2) — stale memory repairs itself. The human loop is: go where you
 * think it is; on a miss, search; when you find it elsewhere, REPLACE the stale
 * memory. An agent gets that whole loop in ONE call: a name that no longer
 * resolves is answered with the correction, explicitly flagged.
 *
 * <p>The correction is never ACTED on — silently retargeting a refactoring at a
 * symbol the caller did not name would be worse than failing. The agent
 * re-issues once, and its memory is now right.</p>
 */
class ResolveOrRelocateTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ObjectMapper om;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        om = new ObjectMapper();
    }

    private ObjectNode refsBySymbol(String fqn) {
        ObjectNode args = om.createObjectNode();
        args.put("kind", "references");
        args.put("symbol", fqn);
        return args;
    }

    /**
     * Retry until the Java model has caught up with a mutation we just made, or give up.
     *
     * <p>JDT rebuilds its index asynchronously, so a query fired the instant a rename lands
     * can miss the new name — not because the name is absent, but because the lookup could
     * not yet succeed. Those are different facts, and a test that conflates them is flaky by
     * construction. This waits for the settled state it means to assert.</p>
     */
    private boolean settles(java.util.function.Supplier<ToolResponse> query) {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (query.get().isSuccess()) {
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a MOVED class: the old name answers with its new location, flagged as a correction")
    void movedClass_correctsInOneCall() {
        // The agent's memory says com.example.Calculator. Reality moves on.
        MoveTool move = new MoveTool(() -> service, cache);
        ObjectNode moveArgs = om.createObjectNode();
        moveArgs.put("kind", "class");
        moveArgs.put("typeName", "com.example.Calculator");
        moveArgs.put("targetPackage", "com.example.math");
        ToolResponse moved = move.execute(moveArgs);
        assertTrue(moved.isSuccess(), "the move must land: " + moved.getError());

        // Now the stale memory is used. ONE call: not-there + found-here.
        FindRefsTool refs = new FindRefsTool(() -> service);
        ToolResponse r = refs.execute(refsBySymbol("com.example.Calculator"));

        assertFalse(r.isSuccess(), "a stale name must NOT silently resolve to something else");
        assertEquals(ResolveOrRelocate.RELOCATED, r.getError().getCode(),
            "flagged as a relocation, not a plain not-found: " + r.getError());
        assertTrue(r.getError().getMessage().contains("com.example.math.Calculator"),
            "the correction names the NEW location: " + r.getError().getMessage());
        assertTrue(String.valueOf(r.getError().getHint()).contains("com.example.math.Calculator"),
            "the hint tells the agent what to remember: " + r.getError().getHint());

        // The corrected name works immediately — one hop, memory repaired.
        ToolResponse retry = refs.execute(refsBySymbol("com.example.math.Calculator"));
        assertTrue(retry.isSuccess(), "the corrected name resolves: " + retry.getError());
    }

    @Test
    @DisplayName("a member renamed to an UNRELATED word: name the real members, do not guess")
    void renamedMember_namesTheRealMembersWithoutGuessing() {
        RenameSymbolTool rename = new RenameSymbolTool(() -> service, cache);
        ObjectNode renameArgs = om.createObjectNode();
        renameArgs.put("symbol", "com.example.Calculator#multiply");
        renameArgs.put("newName", "times");
        assertTrue(rename.execute(renameArgs).isSuccess(), "the rename must land");

        FindRefsTool refs = new FindRefsTool(() -> service);
        // WAIT for the Java model to settle after the mutation — the way a real agent's
        // next dispatch does. This used to assert immediately and merely SAY it had let
        // the model settle, which raced JDT's index rebuild and failed roughly one run in
        // six. Querying into a mid-rebuild window is a different test (and the product
        // already refuses to claim "gone" from a lookup it could not complete) — so the
        // wait belongs here, bounded and explicit.
        assertTrue(settles(() -> refs.execute(refsBySymbol("com.example.Calculator#times"))),
            "the renamed member resolves once the model has settled");

        ToolResponse r = refs.execute(refsBySymbol("com.example.Calculator#multiply"));

        assertFalse(r.isSuccess());
        // `multiply` -> `times` shares not one letter. Nothing in the index says the
        // one BECAME the other, so claiming "Found: times" would be a guess wearing a
        // fact's clothes. The honest answer: the member is not there, and these are
        // the ones that are — the agent picks.
        String message = r.getError().getMessage();
        assertFalse(message.contains("gone, not moved"),
            "the TYPE is right there — saying otherwise is false: " + message);
        assertTrue(message.contains("no member 'multiply'"), "got: " + message);
        assertTrue(message.contains("times"),
            "the real members are named, so the agent can pick: " + message);
    }

    @Test
    @DisplayName("a name that is GONE, not moved: an honest not-found, no invented correction")
    void genuinelyAbsent_saysSo() {
        FindRefsTool refs = new FindRefsTool(() -> service);
        ToolResponse r = refs.execute(refsBySymbol("com.example.NeverExistedAnywhere"));

        assertFalse(r.isSuccess());
        assertEquals("SYMBOL_NOT_FOUND", r.getError().getCode(),
            "nothing similar exists — do NOT dress this up as a relocation: " + r.getError());
        assertTrue(r.getError().getMessage().contains("gone, not moved"),
            "says so plainly: " + r.getError().getMessage());
    }

    @Test
    @DisplayName("dogfood v2.11.0: a near-miss member offers ONLY plausible corrections")
    void nearMissOffersNoNoise() {
        // A typo'd member: the real one must lead, and unrelated members sharing
        // nothing but a "get" prefix must NOT ride along. A wrong suggestion is
        // worse than one fewer suggestion.
        // A tool call first: relocate() reads the JDT index, which a dispatch refreshes.
        FindRefsTool warm = new FindRefsTool(() -> service);
        warm.execute(refsBySymbol("com.example.Calculator"));

        List<String> candidates =
            ResolveOrRelocate.relocate(service, "com.example.Calculator#getLastResultt");
        assertFalse(candidates.isEmpty(), "the typo has an obvious intended target");
        assertEquals("com.example.Calculator#getLastResult", candidates.get(0),
            "the real member leads: " + candidates);
        for (String candidate : candidates) {
            assertTrue(candidate.toLowerCase().contains("lastresult"),
                "no noise: every offer must be plausibly what was meant (getPathUtils and "
                    + "friends share only a 'get' prefix), got: " + candidates);
        }
    }

    @Test
    @DisplayName("dogfood v2.11.0: the type exists but the member does not — say THAT, not 'gone'")
    void typeExistsButMemberDoesNot() {
        FindRefsTool refs = new FindRefsTool(() -> service);
        ToolResponse r = refs.execute(refsBySymbol("com.example.Calculator#totallyUnrelatedXyz"));

        assertFalse(r.isSuccess());
        String message = r.getError().getMessage();
        assertFalse(message.contains("gone, not moved"),
            "the TYPE did not move — saying so would be false: " + message);
        assertTrue(message.contains("com.example.Calculator") && message.contains("no member"),
            "says what is actually true: " + message);
        assertTrue(message.contains("It has:") && message.contains("add"),
            "and names the members that DO exist: " + message);

        // Second dogfood round (v2.11.1, live on ToolResponse): the list carried the
        // constructor, every overload twice, and then the method names AGAIN as
        // fields — 25 entries, half of them noise, in the one message whose job is a
        // clean correction. A name is addressable once (Type#name covers every
        // overload), so it is said once.
        String listed = message.substring(message.indexOf("It has:"));
        assertFalse(listed.contains("Calculator"),
            "the constructor is not addressable as Type#member: " + listed);
        List<String> names = List.of(listed
            .replace("It has:", "").replace(".", "").trim().split(",\\s*"));
        assertEquals(names.size(), Set.copyOf(names).size(),
            "every name is said exactly once — no duplicated overloads: " + names);
    }

    @Test
    @DisplayName("the relocation search itself ranks the moved type first")
    void relocateRanksTheMovedType() {
        MoveTool move = new MoveTool(() -> service, cache);
        ObjectNode moveArgs = om.createObjectNode();
        moveArgs.put("kind", "class");
        moveArgs.put("typeName", "com.example.Calculator");
        moveArgs.put("targetPackage", "com.example.math");
        assertTrue(move.execute(moveArgs).isSuccess());

        // The relocation search reads the JDT INDEX, which a tool call refreshes on
        // dispatch. In production relocate() only ever runs inside such a call; here
        // we make one first, exactly as the caller would.
        FindRefsTool refs = new FindRefsTool(() -> service);
        assertTrue(refs.execute(refsBySymbol("com.example.math.Calculator")).isSuccess(),
            "the moved class resolves under its new name");

        List<String> candidates = ResolveOrRelocate.relocate(service, "com.example.Calculator");
        assertFalse(candidates.isEmpty(), "the index knows where it went");
        assertEquals("com.example.math.Calculator", candidates.get(0), "got: " + candidates);

        // A member suffix survives the move when the member is really there.
        List<String> withMember =
            ResolveOrRelocate.relocate(service, "com.example.Calculator#add");
        assertEquals("com.example.math.Calculator#add", withMember.get(0), "got: " + withMember);
    }

    @Test
    @DisplayName("D2: inspect(kind=type_members) on a MOVED type relocates too — the plan-listed miss-site")
    void inspectTypeMembersRelocatesAStaleTypeName() {
        // Sprint-24 audit T2.4: the plan's Stage-2 wiring list named "inspect type kinds"
        // among the miss-sites that must answer a moved name with the correction in the same
        // response. They were left unwired in v2.13.0 — inspect(kind=type_members) on a stale
        // name returned a bare "Type not found", the second-search dead-end D2 exists to
        // remove, from exactly the tools D4's landmarks steer an agent toward.
        MoveTool move = new MoveTool(() -> service, cache);
        ObjectNode moveArgs = om.createObjectNode();
        moveArgs.put("kind", "class");
        moveArgs.put("typeName", "com.example.Calculator");
        moveArgs.put("targetPackage", "com.example.math");
        assertTrue(move.execute(moveArgs).isSuccess(), "the move must land");

        org.jawata.mcp.tools.InspectTool inspect = new org.jawata.mcp.tools.InspectTool(() -> service);

        // The corrected name works (proves the move + settle), so the stale one must now RELOCATE.
        ObjectNode fresh = om.createObjectNode();
        fresh.put("kind", "type_members");
        fresh.put("typeName", "com.example.math.Calculator");
        assertTrue(settles(() -> inspect.execute(fresh)),
            "the moved type resolves under its new name once the model settles");

        ObjectNode stale = om.createObjectNode();
        stale.put("kind", "type_members");
        stale.put("typeName", "com.example.Calculator");
        ToolResponse r = inspect.execute(stale);

        assertFalse(r.isSuccess(), "a stale type name must not silently resolve to something else");
        assertEquals(ResolveOrRelocate.RELOCATED, r.getError().getCode(),
            "inspect must relocate, not dead-end on 'Type not found': " + r.getError());
        assertTrue(r.getError().getMessage().contains("com.example.math.Calculator"),
            "and name the new location: " + r.getError().getMessage());

        // A genuinely absent type still gets an honest not-found from inspect, not a fake relocation.
        ObjectNode gone = om.createObjectNode();
        gone.put("kind", "type_hierarchy");
        gone.put("typeName", "com.example.NeverExistedAnywhere");
        ToolResponse goneR = inspect.execute(gone);
        assertFalse(goneR.isSuccess());
        assertEquals("SYMBOL_NOT_FOUND", goneR.getError().getCode(),
            "nothing to relocate to — the miss passes through honestly: " + goneR.getError());
    }
}
