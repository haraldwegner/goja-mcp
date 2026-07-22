package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sprint 27 C4 — the retrieval ontology, enforced.
 *
 * <p>What is under test is not "semantic recall returns things" but the two
 * rules that make it safe to put in an agent's context: <b>facts stay hard-gated
 * and terminal</b>, and <b>experience comes back as capped, labeled analogy that
 * never reads as fact</b>.</p>
 */
class SemanticRecallTest {

    private H2ExperienceStore store;
    private EmbeddingService svc;
    private ExperienceRetrieval semantic;
    private ExperienceRetrieval keywordOnly;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        svc = EmbeddingService.shared();
        semantic = new ExperienceRetrieval(store, () -> null, new EmbeddingIndex(store, svc));
        keywordOnly = ExperienceRetrieval.keywordOnly(store, () -> null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private String putExperience(String summary, String details, String anchor) {
        SymbolFact.Builder f = SymbolFact.of("lesson", summary, Confidence.MEDIUM).details(details);
        if (anchor != null) {
            f.symbol(anchor);
        }
        return store.put(ExperienceEntry.of(f.build()).build());
    }

    private String putFact(String summary, String anchor) {
        return store.put(ExperienceEntry.of(
            SymbolFact.of("api_contract", summary, Confidence.HIGH).symbol(anchor).build()).build());
    }

    private static RecallQuery symptom(String s) {
        return new RecallQuery(null, null, null, s, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> analogies(Map<String, Object> r) {
        return (List<Map<String, Object>>) r.getOrDefault("analogies", List.of());
    }

    /**
     * ABORT, not return, when the embedder is unavailable.
     *
     * <p>A bare {@code return} is recorded by JUnit as a PASS, which makes a
     * gate that never ran indistinguishable from one that held —
     * {@link CalibrationGateTest} prints "NOT RUN" for exactly this reason. The
     * older tests in this file still use the bare return; the C2 gates must
     * not, because they are the ones being called blocking.</p>
     */
    private void requireEmbedder() {
        org.junit.jupiter.api.Assumptions.assumeTrue(svc.available(),
            "embedder unavailable — this C2 gate did NOT run: " + svc.unavailableReason());
    }

    @Test
    void a_paraphrase_reaches_experience_that_keyword_recall_cannot_find() {
        if (!svc.available()) {
            return;
        }
        String target = putExperience(
            "never remove the algo from the map until the broker confirms",
            "premature removal routes a late cancel callback to the wrong algo on a reused slot",
            null);
        putExperience("the opening range is the first fifteen minutes", "session structure", null);

        RecallQuery cue = symptom(
            "we deleted the order record too early and a late confirmation went to the wrong place");

        // PREMISE CORRECTED, Sprint 27a D9. This assertion used to read
        // "keyword recall genuinely cannot reach this - that is the Recall
        // Gap", and it held only because the older word rule required EVERY cue
        // token to appear. Once word matching is scored rather than
        // conjunctive, it reaches this cue legitimately: the question says "a
        // late confirmation went to the wrong place" and the entry says "a late
        // cancel callback to the wrong algo" — the shared content words are
        // "late" and "wrong", and matching on them is correct, not invented.
        //
        // So the gap was never "keyword cannot reach a paraphrase". It was
        // "keyword could not reach anything a natural sentence asked for",
        // which is a defect and is what D9 repaired. What remains true, and is
        // what this test now asserts, is the SHARPER claim: meaning ranks the
        // right entry FIRST.
        Map<String, Object> byWords = keywordOnly.recall(cue);
        assertEquals(ExperienceRetrieval.RESULT_ANALOGY, byWords.get("result"),
            "word matching now reaches this cue through the words it shares — "
            + "with no embedder at all, which is the degrade path D9 restores");

        Map<String, Object> after = semantic.recall(cue);
        assertEquals(ExperienceRetrieval.RESULT_ANALOGY, after.get("result"),
            "and semantic recall answers it - as analogy, not as fact");
        List<Map<String, Object>> got = analogies(after);
        assertFalse(got.isEmpty());
        assertEquals(target, got.get(0).get("id"), "the right entry, ranked first");
    }

    @Test
    void an_analogy_is_never_dressed_as_a_fact() {
        if (!svc.available()) {
            return;
        }
        putExperience("never free the slot before the broker confirms the cancel",
            "a late confirmation can reach a reused slot", null);
        Map<String, Object> r = semantic.recall(symptom(
            "we released the resource before the exchange acknowledged it"));
        List<Map<String, Object>> got = analogies(r);
        assertFalse(got.isEmpty(), "precondition: an analogy surfaced");

        Map<String, Object> a = got.get(0);
        assertEquals("analogy — judge whether it transfers", a.get("framing"),
            "the framing must say what this is");
        assertNotNull(a.get("basis"), "and WHY it surfaced, in words");
        assertTrue(a.get("basis") instanceof List<?> l && !l.isEmpty(),
            "the basis must be a non-empty list of reasons, not a bare flag");
        assertFalse(a.containsKey("score"),
            "the structured form must not carry a similarity number either");

        // The result must never be presented as a gated match.
        assertEquals(ExperienceRetrieval.RESULT_ANALOGY, r.get("result"));
        assertTrue(((List<?>) r.get("entries")).isEmpty(),
            "an analogy never lands in the gated-fact list");
    }

    @Test
    void no_rendering_anywhere_contains_a_similarity_number() {
        if (!svc.available()) {
            return;
        }
        putExperience("the webview renders blank on linux",
            "the DMABUF compositor path fails silently on some drivers", null);
        // Cue chosen from a MEASURED score, not from intuition: this cue scores
        // 0.2653 against the entry above, so an analogy genuinely surfaces and
        // the assertions below have something to inspect.
        //
        // This comment used to add that a weaker phrasing (0.1177) fell below
        // "the derived 0.15 floor" and so demonstrated the floor was not
        // vacuous. Both halves are now wrong: the analogy path's floor is
        // AnalogyPolicy.JUNK_FLOOR = 0.10, so 0.1177 would be nominated — and
        // the C2 measurement records that the floor fires on nothing in the
        // corpus at all. The floor is a guard, not a filter; see AnalogyPolicy.
        Map<String, Object> r = semantic.recall(
            symptom("the app starts but nothing paints inside the frame"));
        // The precondition asserts the RESULT, not merely that some text came
        // back — an absence message is also non-empty text, which would let
        // this test pass while proving nothing.
        assertEquals(ExperienceRetrieval.RESULT_ANALOGY, r.get("result"),
            "precondition: an analogy must actually have surfaced");
        String text = ExperienceRetrieval.renderText(r);
        assertFalse(text.isEmpty());

        // A score in the text invites treating similarity as authority. The
        // basis is words; the ranking stays inside the machine.
        assertFalse(text.matches("(?s).*0\\.\\d{2,}.*"),
            "no cosine-looking number may appear in a rendering: " + text);
        assertFalse(text.toLowerCase().contains("score"),
            "nor the word 'score': " + text);
        assertTrue(text.contains("In a similar situation:"),
            "and the advisory framing must survive rendering: " + text);
    }

    @Test
    void experience_whose_code_is_gone_still_returns_with_its_provenance() {
        if (!svc.available()) {
            return;
        }
        // The ruling that forced the ontology: an old refactoring lesson is
        // still good experience even when the class it was learned on is gone.
        putExperience("extract was reverted twice on a long method",
            "the extraction changed behaviour because a field was captured",
            "com.gone.DeletedClass#oldMethod");

        Map<String, Object> r = semantic.recall(symptom(
            "pulling a block out of a big routine broke it"));
        List<Map<String, Object>> got = analogies(r);
        assertFalse(got.isEmpty(),
            "a dead anchor must NOT remove experience - that would delete every"
            + " lesson learned on code that has since changed");
        assertNotNull(got.get(0).get("provenance"), "and its origin is rendered as context");
        assertTrue(String.valueOf(got.get(0).get("provenance")).startsWith("learned on"));
    }

    @Test
    void a_fact_that_fails_its_address_gate_is_not_smuggled_back_as_an_analogy() {
        if (!svc.available()) {
            return;
        }
        // A FACT about code at an address. The cue does not contain that
        // address, so the gate must not admit it - and the meaning nominator
        // must not offer it either, or the hard gate becomes decorative.
        putFact("callers must never pass null to this API",
            "com.example.SomeService#doWork");

        Map<String, Object> r = semantic.recall(symptom(
            "callers must never pass null to this API"));
        for (Map<String, Object> a : analogies(r)) {
            assertFalse("api_contract".equals(a.get("type")),
                "an address-bound fact must never appear as an analogy: " + a);
        }
    }

    @Test
    void analogies_are_capped_so_the_context_cannot_be_flooded() {
        if (!svc.available()) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            putExperience("broker confirmation lesson number " + i,
                "never act before the broker confirms the cancel, case " + i, null);
        }
        Map<String, Object> r = semantic.recall(symptom(
            "we acted before the exchange acknowledged the cancellation"));
        // The cap is AnalogyPolicy's. It used to be a fixed two, which is the
        // flaw D1 removed — a correct third answer was being hidden by it.
        assertTrue(analogies(r).size() <= AnalogyPolicy.MAX_NOMINEES,
            "at most " + AnalogyPolicy.MAX_NOMINEES + " analogies may reach the agent");
    }

    @Test
    void equality_boosts_the_ranking_but_never_decides_admission() {
        if (!svc.available()) {
            return;
        }
        String sameOp = store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "renaming across modules needs a full rebuild",
                Confidence.MEDIUM).details("stale binaries hid the breakage").build())
            .operation("rename_symbol").build());
        putExperience("renaming across modules needs a full rebuild too",
            "stale binaries hid the breakage as well", null);

        List<StoredEntry> both = store.all();
        List<ExperienceAnalogies.Analogy> ranked = ExperienceAnalogies.rank(
            both, new RecallQuery(null, null, "rename_symbol", null, null),
            Map.of(), 2, () -> null);

        assertEquals(sameOp, ranked.get(0).entry().id(),
            "the same-operation entry ranks first...");
        assertEquals(2, ranked.size(),
            "...but the other is still ADMITTED - equality boosts, it never gates");
        assertTrue(ranked.get(0).basis().contains("same operation"),
            "and the boost is named in words");
    }

    @Test
    void with_no_index_recall_behaves_exactly_as_it_did_before_this_sprint() {
        putExperience("database file already locked at startup",
            "the lock survived a crash", null);

        Map<String, Object> r = keywordOnly.recall(symptom("database file already locked"));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, r.get("result"));
        assertFalse(r.containsKey("analogies"),
            "no index means no analogies key at all - the pre-27 answer shape, unchanged");

        Map<String, Object> miss = keywordOnly.recall(symptom("something entirely unrelated"));
        assertEquals(ExperienceRetrieval.RESULT_ABSENCE, miss.get("result"),
            "and an honest absence stays an absence");
    }

    // --- Sprint 27a C2: what the store vouches for, and what it merely offers ---------

    /**
     * The blocking honesty check, in the form the 2026-07-22 design ruling
     * leaves possible.
     *
     * <p>The signed spec asked for "nonsense returns nothing". Measurement
     * refuted that as buildable: nonsense scores 0.18–0.41 against a real
     * corpus because it is made of real words, and no rule over the score
     * profile separates it from a genuine question (see
     * {@link AnalogyPolicyDerivationTest}). What survives, and is asserted
     * here: <b>the store never VOUCHES for an answer to a question it cannot
     * answer.</b> Nominees may come back — they are the nearest entries and the
     * agent judges them — but they arrive labelled as analogies, never as
     * matched facts.</p>
     */
    @Test
    void a_question_the_store_cannot_answer_gets_no_vouched_answer() {
        requireEmbedder();
        putExperience("never act before the broker confirms the cancel",
            "the confirmation is the only safe trigger", null);
        putExperience("the webview paints blank under the DMABUF compositor",
            "disabling the compositor path restores painting", null);

        Map<String, Object> r = semantic.recall(symptom(
            "the marzipan barometer disputes tuesday's velvet inventory"));

        // RESULT_ANALOGY is the machine-readable form of exactly this: nothing
        // passed the gate, comparable entries are offered. It is the honest
        // label here — MATCH would be a claim the store cannot support, and
        // ABSENCE would hide that nominees came back.
        assertEquals(ExperienceRetrieval.RESULT_ANALOGY, r.get("result"),
            "the store must not claim a MATCH for a question nothing answers");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vouched =
            (List<Map<String, Object>>) r.getOrDefault("entries", List.of());
        assertTrue(vouched.isEmpty(),
            "nothing may be vouched for here — the exact path found nothing, and "
            + "the meaning path is not entitled to promote its nominees: " + vouched);

        // Whatever the meaning path DOES offer must read as an offer.
        for (Map<String, Object> a : analogies(r)) {
            assertEquals("analogy — judge whether it transfers", a.get("framing"),
                "a nominee must be labelled as one — with silence no longer "
                + "available, this label is what carries the honesty: " + a);
            assertNotNull(a.get("basis"), "and it must say WHY it was nominated");
        }
    }

    /**
     * The spec's D2 measure gets its own probe rather than being inferred from
     * the front-door check: a novel question reaching recall through the
     * DISPATCH path must likewise produce nothing vouched.
     */
    @Test
    void a_novel_question_through_dispatch_gets_no_vouched_answer() {
        requireEmbedder();
        // A seeded PAST RUN in the runner's own shape, so the dispatch
        // decorator has something to attach and the probe cannot pass by
        // finding nothing at all.
        store.put(ExperienceEntry.of(
            SymbolFact.of(DispatchRecall.SEAT_RUN_TYPE,
                "seat javadocs on PurityCheck: accepted (javadocs-1)", Confidence.MEDIUM)
            .details("{\"schema\":1,\"ts\":1750000000,\"runId\":\"javadocs-1\","
                + "\"seat\":\"javadocs\",\"target\":\"PurityCheck\","
                + "\"work\":\"documented five undocumented members\","
                + "\"humanVerdict\":\"accepted\",\"outcome\":\"applied unchanged\"}")
            .build())
            .operation("seat:javadocs").build());

        Map<String, Object> r = semantic.recall(
            new RecallQuery(null, null, "seat:javadoc-like-work-never-run-before", null, null));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> vouched =
            (List<Map<String, Object>>) r.getOrDefault("entries", List.of());
        assertTrue(vouched.isEmpty(),
            "dispatch recall must not vouch for a past run that does not exist: " + vouched);

        // The labelling half must not hold vacuously: something has to have come
        // back for "it came back labelled" to mean anything. The C2 audit found
        // the first version of this probe iterating a list it never required to
        // be non-empty.
        assertFalse(analogies(r).isEmpty(),
            "precondition: the seeded past run must surface as a nominee, or this "
            + "probe proves nothing about how nominees are labelled");
        boolean sawDispatch = false;
        for (Map<String, Object> a : analogies(r)) {
            assertEquals("analogy — judge whether it transfers", a.get("framing"),
                "a dispatch-decorated nominee is still a nominee: " + a);
            sawDispatch |= a.containsKey("dispatch");
        }
        assertTrue(sawDispatch,
            "and the dispatch decoration must actually be on it — otherwise this "
            + "is the front-door check under another name");
    }

    /**
     * The recall half of the ruling: a weak but correct match must be
     * DELIVERED. Under the retired margin rule this cue was silenced, which is
     * the failure Harald's asymmetry ruling names — missing an experience we
     * already hold is the expensive error, discarding a nominee costs a glance.
     */
    @Test
    void a_weak_but_correct_match_is_delivered_rather_than_withheld() {
        requireEmbedder();
        String target = putExperience(
            "cosine bands must never swap jobs: parity is not dedup is not retrieval",
            "parity sits at 0.999, dedup near 0.9, retrieval is the broad band", null);
        for (int i = 0; i < 12; i++) {
            putExperience("unrelated background lesson number " + i,
                "about build ordering and nothing else, case " + i, null);
        }

        Map<String, Object> r = semantic.recall(symptom(
            "how close should two vectors be before we call them the same thing"));

        assertTrue(analogies(r).stream().anyMatch(a -> target.equals(a.get("id"))),
            "the correct entry must reach the agent even when its score is low — "
            + "silence here is the expensive error, not a safe default");
    }

    /**
     * Stage 2b clause (iv), proven where the clause actually points: with NO
     * embedder at all, a prose question still reaches the right entry by words.
     *
     * <p>This runs unconditionally — {@code keywordOnly} has no index, so there
     * is nothing to be unavailable, and the C2b audit was right that proving a
     * degrade path only when the embedder is present proves nothing about the
     * degrade path. It also pins WHICH entry and on WHAT basis: a fixture large
     * enough that stop-words are carried by most rows and therefore cannot
     * match, so the hit rests on distinctive words alone.</p>
     */
    @Test
    void with_no_embedder_a_prose_question_is_still_answered_by_words() {
        String target = putExperience(
            "never remove the algo from the map until the broker confirms",
            "premature removal routes a late cancel callback to the wrong algo", null);
        // Background carrying the same stop-words, so "the"/"a"/"to" are in a
        // majority of rows and contribute nothing — the match must be earned by
        // "broker" and "confirms".
        for (int i = 0; i < 8; i++) {
            putExperience("a note about the opening range and the session, number " + i,
                "the structure of the session, to be used with the range", null);
        }
        // A RIVAL that also matches lexically but is the wrong answer, recorded
        // LAST so recency would put it first. Without it the pool held exactly
        // one candidate and "the right entry, FIRST" could not fail — the C2b
        // re-audit's finding, and the reason the claim now has something to beat.
        //
        // It shares ONE distinctive word with the cue ("cancel"); the target
        // shares three ("broker", "confirms", "cancel"). A first version of this
        // rival repeated "broker" and "cancel" twice each and duly won on term
        // frequency — which proved the scorer works and the fixture did not.
        putExperience("a queued order was rejected by the exchange yesterday",
            "unrelated, though it does mention a cancel", null);

        Map<String, Object> answer = keywordOnly.recall(
            symptom("we removed it before the broker confirms the cancel"));

        assertEquals(ExperienceRetrieval.RESULT_ANALOGY, answer.get("result"),
            "with no embedder the store must still answer a prose question by words");
        List<Map<String, Object>> got = analogies(answer);
        assertFalse(got.isEmpty(), "and it must return something");
        assertEquals(target, got.get(0).get("id"),
            "the RIGHT entry, first — not merely some entry, and not the newest");
        assertTrue(String.valueOf(got.get(0).get("basis")).contains("shares distinctive wording"),
            "and it must say the basis is words, since no meaning score exists: "
            + got.get(0).get("basis"));
    }

    /**
     * Equality still ranks — asserted THROUGH THE PRODUCTION PATH.
     *
     * <p>{@code ExperienceAnalogies}' first rule is "equality boosts rank; it
     * never gates". The C2b re-audit found that rule had quietly become
     * unreachable in the product: once the merged ranking became the sole
     * primary sort key, every nominated row held a distinct position, so no two
     * rows were ever tied and the boosts could not move anything. The only test
     * of boost ordering called the overload the product had stopped using — the
     * guarantee was asserted on a path nothing took.</p>
     */
    @Test
    void a_same_operation_entry_still_leads_through_the_front_door() {
        requireEmbedder();
        // The cue carries a SUBJECT (a symptom) as well as an operation. That
        // shape matters and the first version of this test got it wrong: on an
        // operation-ONLY cue the fit gate treats a matching operation as a FIT,
        // so the entry is returned as a vouched entry and never reaches the
        // analogy path where the boosts live. With a subject present, operation
        // is a refinement — which is exactly the case the boost exists for.
        String sameOperation = store.put(ExperienceEntry.of(
            SymbolFact.of("lesson", "renaming across modules needs a full rebuild",
                Confidence.MEDIUM).details("stale binaries hid the breakage").build())
            .operation("rename_symbol").build());
        // Recorded LATER and worded to score at least as well by meaning, so
        // leading cannot be an artefact of recency or of the meaning score.
        putExperience("renaming across modules needs a full rebuild as well",
            "stale binaries hid the breakage here too", null);
        for (int i = 0; i < 6; i++) {
            putExperience("an unrelated note number " + i, "about something else", null);
        }

        RecallQuery cue = new RecallQuery(null, null, "rename_symbol",
            "a rebuild was needed after moving code between modules", null);

        // PRECONDITION, or this test cannot tell a working boost from a boost
        // that does nothing: the target must NOT already lead on the evidence.
        // Ask the same query with the operation removed — then no promotion
        // applies — and require the rival to lead there. Only then does the
        // assertion below measure what it claims to. The C2b sign-off named
        // this: without it, disabling promotion() entirely would leave this
        // test, and every calibration arm, green.
        List<Map<String, Object>> withoutOperation = analogies(semantic.recall(
            new RecallQuery(null, null, null, cue.symptom(), null)));
        assertFalse(withoutOperation.isEmpty(), "precondition: analogies expected");
        assertFalse(sameOperation.equals(withoutOperation.get(0).get("id")),
            "precondition: with the operation removed the OTHER entry must lead, "
            + "otherwise the operation boost is not what puts it first below");

        Map<String, Object> answer = semantic.recall(cue);
        List<Map<String, Object>> got = analogies(answer);
        assertFalse(got.isEmpty(), "precondition: the cue must return analogies");
        assertEquals(sameOperation, got.get(0).get("id"),
            "the same-operation entry must lead — equality ranks, and it must do "
            + "so on the path the product actually takes");
        assertTrue(String.valueOf(got.get(0).get("basis")).contains("same operation"),
            "and it must say so: " + got.get(0).get("basis"));
    }

    /**
     * A rejected note stays gone THROUGH THE WORD PATH.
     *
     * <p>The C2b audit found the word stream had shipped with no status filter
     * at all — the keyword path filters in SQL and the meaning path in its
     * query, and the third one filtered nothing, so a note the user had thrown
     * away could return by a new door. The existing guard did not notice,
     * because its fixture holds one row whose words the cue does not contain.
     * This fixture is built the other way round: the rejected row shares the
     * cue's most distinctive words, and there are enough rows that the
     * discrimination cut does not silence the word stream.</p>
     */
    @Test
    void a_rejected_note_stays_gone_by_the_word_path_too() {
        String doomed = putExperience(
            "the tick recorder starves the quote thread under load",
            "the recorder holds the lock while flushing, and quotes queue behind it", null);
        store.setStatus(doomed, ExperienceEntry.REJECTED);
        for (int i = 0; i < 5; i++) {
            putExperience("an unrelated note number " + i, "about something else", null);
        }

        Map<String, Object> answer = semantic.recall(
            symptom("the tick recorder starves the quote thread under load"));

        List<String> returned = new java.util.ArrayList<>();
        for (Map<String, Object> a : analogies(answer)) {
            returned.add(String.valueOf(a.get("id")));
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> gated =
            (List<Map<String, Object>>) answer.getOrDefault("entries", List.of());
        for (Map<String, Object> e : gated) {
            returned.add(String.valueOf(e.get("id")));
        }
        assertFalse(returned.contains(doomed),
            "a rejected note must not return by ANY path — it shares every "
            + "distinctive word of this cue, so only the status filter can "
            + "keep it out. Returned: " + returned);
    }

    @Test
    void the_cue_text_is_the_words_of_the_cue() {
        assertEquals("blank window rename_symbol com.foo.Bar",
            ExperienceRetrieval.cueText(
                new RecallQuery("com.foo.Bar", null, "rename_symbol", "blank window", null)));
        assertEquals("", ExperienceRetrieval.cueText(null));
        assertEquals("", ExperienceRetrieval.cueText(
            new RecallQuery(null, null, null, null, null)));
    }
}
