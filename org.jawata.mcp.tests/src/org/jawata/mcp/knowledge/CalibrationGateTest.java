package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 27 C4 — <b>the E2 gate</b>: does semantic recall actually find the
 * right knowledge, on the REAL corpus, through the REAL union?
 *
 * <p>Judged by the ACCEPT-SET rule (Harald's option A, frozen at C0 in
 * {@code accept-sets.json} before any retrieval code existed): a cue PASSES when
 * the winning entry is in that cue's accept set AND the designated entry is
 * inside the nomination window. Bar: <b>≥10 of 12</b>, against a keyword
 * baseline of 1 of 12.</p>
 *
 * <p>This test needs the corpus export it was calibrated against — a dump of a
 * real 2054-entry store, which is NOT committed (it is personal knowledge, and
 * pinned by sha256 rather than vendored). Point it at one with
 * {@code -Djawata.embed.corpus=/path/to/export.json}. Without it the test
 * reports LOUDLY that the gate did not run rather than passing quietly: a gate
 * that silently skips is indistinguishable from one that passed, and that is
 * exactly how a headline claim goes unverified.</p>
 */
class CalibrationGateTest {

    private static final String CORPUS_PROPERTY = "jawata.embed.corpus";
    private static final String ACCEPT_SETS = "/test-resources/embed-goldens/accept-sets.json";
    private static final int BAR = 10;
    /** The frozen contract's nomination window: the designated entry must be inside it. */
    private static final int FROZEN_K = 12;
    /** C2 clause (i): the meaning path alone, by the frozen contract. */
    private static final int EMBEDDINGS_ALONE_BAR = 9;
    /** Stage 2b (ii): the merged ranking, at the figure recorded when D9 landed. */
    private static final int FUSED_COMPONENT_BAR = 10;
    /** The word stream alone — a floor under the stream, not a target. */
    private static final int WORDS_ALONE_BAR = 4;
    /** D1 criterion (c): the cue from the first dogfood hour, and its answer. */
    private static final String PARAPHRASE_CUE =
        "our test coverage looked like it fell from 77% to 34% overnight";
    private static final String RATCHET_LESSON = "5f7373f4";

    @Test
    void the_calibration_cues_are_answered_from_the_real_corpus() throws Exception {
        // ABORT rather than return. This test's own javadoc says a gate that
        // silently skips is indistinguishable from one that passed — and then it
        // returned, which JUnit records as a PASS. Printing loudly was the
        // mitigation; aborting is the fix, and it costs one visible "aborted" in
        // a run without the corpus instead of one invisible green.
        String corpusPath = System.getProperty(CORPUS_PROPERTY);
        org.junit.jupiter.api.Assumptions.assumeTrue(
            corpusPath != null && Files.exists(Path.of(corpusPath)),
            "[E2 GATE] NOT RUN — no corpus at -D" + CORPUS_PROPERTY
            + ". This is the sprint's headline gate; it is unverified in this run.");
        EmbeddingService svc = EmbeddingService.shared();
        org.junit.jupiter.api.Assumptions.assumeTrue(svc.available(),
            "[E2 GATE] NOT RUN — embedder unavailable: " + svc.unavailableReason());

        JsonNode accept;
        try (InputStream in = CalibrationGateTest.class.getResourceAsStream(ACCEPT_SETS)) {
            accept = new ObjectMapper().readTree(in);
        }

        // The corpus is SAMPLED, not truncated, and every entry named in an
        // accept set is force-included so the gate is never made easier by
        // dropping the answer. It is embedded once for the class (see
        // embedTheCorpusOnce): fewer rivals is WEAKER evidence than the full
        // corpus, so the sample is printed and recorded rather than glossed.
        H2ExperienceStore store = sharedStore;
        EmbeddingIndex index = sharedIndex;
        {
            // A gate that cannot find its own answers is measuring its fixture,
            // not the product. Prove every accept-set entry actually landed.
            Set<String> presentIds = new LinkedHashSet<>();
            for (StoredEntry e : store.all()) {       // ONE pass, not one per id
                if (e.id() != null) {
                    presentIds.add(e.id());
                }
            }
            List<String> missing = new ArrayList<>();
            for (String want : acceptedIds(accept)) {
                if (presentIds.stream().noneMatch(id -> id.startsWith(want))) {
                    missing.add(want);
                }
            }
            System.out.printf("[E2 GATE] accept-set entries present: %d of %d%s%n",
                acceptedIds(accept).size() - missing.size(), acceptedIds(accept).size(),
                missing.isEmpty() ? "" : " — MISSING " + missing);

            ExperienceRetrieval union = new ExperienceRetrieval(store, () -> null, index);
            ExperienceRetrieval keyword = ExperienceRetrieval.keywordOnly(store, () -> null);

            int passed = 0;
            int keywordPassed = 0;
            int embeddingsPassed = 0;
            int lexicalPassed = 0;
            int fusedPassed = 0;
            List<String> embeddingsFailing = new ArrayList<>();
            List<String> fusedFailing = new ArrayList<>();
            List<String> rows = new ArrayList<>();
            // The corpus ONCE: BM25's idf is a property of the whole set, so
            // every cue must be scored against the same rows — and against the
            // same rows PRODUCTION would use. lexicalScores filters rejected and
            // superseded before scoring (dead rows would distort every surviving
            // row's weight), so measuring the word arms over the raw table would
            // score them on a population the product never sees.
            List<StoredEntry> all = new ArrayList<>();
            for (StoredEntry e : store.all()) {
                if (!ExperienceEntry.REJECTED.equals(e.status())
                        && !ExperienceEntry.SUPERSEDED.equals(e.status())) {
                    all.add(e);
                }
            }
            for (JsonNode cue : accept.get("cues")) {
                String text = cue.get("cue").asText();
                Set<String> ok = new LinkedHashSet<>();
                for (JsonNode a : cue.get("accept_set")) {
                    ok.add(a.asText());
                }
                Map<String, Object> unionAnswer = union.recall(query(cue, text));
                Map<String, Object> kwAnswer = keyword.recall(query(cue, text));
                // BOTH HALVES, for these arms as for the others. Until the C2b
                // audit these two were judged by the winner alone while the
                // three below required the designated entry inside K as well —
                // so the two arms carrying the comparison's argument were scored
                // by a weaker rule than the arms they were tabulated beside.
                String designatedId = cue.get("designated").asText();
                boolean semantic = contractHolds(rankedAnswer(unionAnswer), ok, designatedId);
                boolean kw = contractHolds(rankedAnswer(kwAnswer), ok, designatedId);

                // THE EMBEDDINGS-ONLY ARM, and the frozen contract's second
                // half. The C2 gate is written as "embeddings alone >=9/12 AND
                // union >=10/12" precisely so the symbol path cannot mask a
                // meaning regression — cue-11 is already union-only. Until the
                // C2 audit, only the union was ever measured, so the clause was
                // satisfied by a number that could not see what it guards
                // against. This scores the meaning ranking directly, and checks
                // BOTH halves the contract names: winner acceptable, AND the
                // designated entry inside K.
                if (embeddingsAlonePasses(index, text, ok, cue.get("designated").asText())) {
                    embeddingsPassed++;
                } else {
                    embeddingsFailing.add(cue.path("id").asText());
                }

                // Sprint 27a D9 — the two NEW arms, measured before anything is
                // wired: words alone, and the two streams merged by rank. Both
                // are scored by the SAME frozen contract as the arms above, so
                // the four numbers are comparable.
                String designated = cue.get("designated").asText();
                List<String> byWords = rankedIds(LexicalIndex.score(text, all));
                if (contractHolds(byWords, ok, designated)) {
                    lexicalPassed++;
                }
                // The UNCAPPED merged ranking: the contract asks whether the
                // designated entry is inside the top FROZEN_K, and the nominee
                // list is capped below that for context economy. Measuring the
                // capped list against this contract reports regressions that
                // are artefacts of the cap.
                List<String> merged = AnalogyPolicy.fuse(
                    profileOf(index, text), LexicalIndex.score(text, all));
                if (contractHolds(merged, ok, designated)) {
                    fusedPassed++;
                } else {
                    fusedFailing.add(cue.path("id").asText());
                    // Where each stream put the designated entry, and who each
                    // stream's winner was — the discriminating observation for
                    // "why did merging lose a cue one stream had right".
                    List<String> sem = rankedIds(profileOf(index, text));
                    List<String> lex = rankedIds(LexicalIndex.score(text, all));
                    System.out.printf("  [fused MISS] %s designated=%s%n"
                        + "      meaning : winner=%s designatedRank=%s%n"
                        + "      words   : winner=%s designatedRank=%s%n"
                        + "      merged  : winner=%s designatedRank=%s%n",
                        cue.path("id").asText(), designated,
                        head(sem), rankOf(sem, designated),
                        head(lex), rankOf(lex, designated),
                        head(merged), rankOf(merged, designated));
                }
                if (!semantic || !kw) {
                    // DIAGNOSIS (C4 investigation): show what each arm actually
                    // returned, so a fixture defect cannot masquerade as a
                    // retrieval verdict.
                    System.out.printf("  ? %-12s union=%s %s | kw=%s %s%n",
                        cue.path("id").asText(),
                        unionAnswer.get("result"), top3(unionAnswer),
                        kwAnswer.get("result"), top3(kwAnswer));
                }
                if (semantic) {
                    passed++;
                }
                if (kw) {
                    keywordPassed++;
                }
                rows.add(String.format("  [%s] kw=%-5s %-14s %s",
                    semantic ? "PASS" : "MISS", kw ? "hit" : "miss",
                    cue.get("class").asText(), text.length() > 56
                        ? text.substring(0, 56) + "…" : text));
            }
            rows.forEach(System.out::println);
            int n = accept.get("cues").size();
            // "no-embedder", NOT "keyword": since D9 this arm runs word matching
            // too, so calling it the old keyword path would credit the retired
            // conjunctive rule with a score it never had. That rule measured
            // 2/12 and is recorded in the dossier as a historical baseline, not
            // printed here as though it were still running.
            System.out.printf("[E2 GATE] semantic %d/%d · embeddings-alone %d/%d · "
                + "no-embedder %d/%d · bar %d%n",
                passed, n, embeddingsPassed, n, keywordPassed, n, BAR);
            System.out.printf("[E2 GATE] D9 arms — words-alone %d/%d · FUSED %d/%d%n",
                lexicalPassed, n, fusedPassed, n);
            if (!embeddingsFailing.isEmpty()) {
                System.out.println("[E2 GATE] embeddings-alone failing: " + embeddingsFailing);
            }
            if (!fusedFailing.isEmpty()) {
                System.out.println("[E2 GATE] fused failing: " + fusedFailing);
            }

            assertTrue(passed >= BAR,
                "E2: semantic recall answered " + passed + " of "
                + accept.get("cues").size() + " calibration cues; the bar is " + BAR
                + " (C0 baseline). This is the sprint's central claim.");
            assertTrue(passed > keywordPassed,
                "E2: the full union (" + passed + ") must strictly beat the "
                + "no-embedder path (" + keywordPassed + ") — otherwise the "
                + "meaning half of the sprint bought nothing.");
            // Stage 2b clause (ii), ASSERTED rather than printed. The C2b audit
            // found both D9 arms computed, reported and gated by nothing at all
            // — a number that cannot fail is not a gate. Two bars, because the
            // clause's own wording is about the merge and the sprint's claim is
            // about the shipped path:
            //   * the shipped union must not regress (BAR, asserted above);
            //   * the merged component must not fall below what it measured
            //     when D9 was accepted.
            // The component sits BELOW the union by construction — it has no
            // symbol path — so pinning it at its measured value is the honest
            // form. Raising it is a deviation only Harald can grant.
            assertTrue(fusedPassed >= FUSED_COMPONENT_BAR,
                "Stage 2b (ii): the merged ranking answered " + fusedPassed + " of "
                + accept.get("cues").size() + "; the recorded figure is "
                + FUSED_COMPONENT_BAR + ". A drop means rank fusion cost a cue and "
                + "must be reverted, not shipped. Failing: " + fusedFailing);
            assertTrue(lexicalPassed >= WORDS_ALONE_BAR,
                "the word stream answered " + lexicalPassed + " of "
                + accept.get("cues").size() + "; the recorded figure is "
                + WORDS_ALONE_BAR + " — below it the merge is carrying a stream "
                + "that has stopped working");
            assertTrue(embeddingsPassed >= EMBEDDINGS_ALONE_BAR,
                "E2 clause (i): the MEANING path alone answered " + embeddingsPassed
                + " of " + accept.get("cues").size() + " by the frozen contract "
                + "(winner in accept_set AND designated within K=" + FROZEN_K + "); "
                + "the bar is " + EMBEDDINGS_ALONE_BAR + ". Gating only the union "
                + "would let the symbol path mask exactly this. Failing: "
                + embeddingsFailing);
        }                                 // the corpus is closed in @AfterAll
    }

    /**
     * D1's criterion (c), and the reason Stage 2b exists: the
     * percentage-paraphrase case from the first dogfood hour returns the
     * ratchet lesson.
     *
     * <p>It is a THIRTEENTH cue, not one of the frozen twelve. It failed for the
     * whole of Sprint 27 and through C2 here: the question is phrased in
     * percentages and the lesson in words, so meaning ranks it 28th, and the
     * conjunctive word rule returned nothing at all because the token
     * {@code looked} is absent from every row. Rarity-weighted word matching
     * ranks it 1st of 2,080 — this asserts it through the WIRED front door,
     * not against a ranking computed inside the test.</p>
     */
    @Test
    void criterion_c_the_paraphrase_case_returns_the_ratchet_lesson() throws Exception {
        String corpusPath = System.getProperty(CORPUS_PROPERTY);
        // ABORT, never a bare return: JUnit records a return as a PASS, and this
        // is the sprint's uniquely unreachable measure — the one place a
        // silently-skipped gate would be most costly to mistake for a green one.
        org.junit.jupiter.api.Assumptions.assumeTrue(
            corpusPath != null && Files.exists(Path.of(corpusPath)),
            "[CRITERION C] NOT RUN — no corpus at -D" + CORPUS_PROPERTY
            + "; D1's third measure is unverified in this run.");
        EmbeddingService svc = EmbeddingService.shared();
        org.junit.jupiter.api.Assumptions.assumeTrue(svc.available(),
            "[CRITERION C] NOT RUN — embedder unavailable: " + svc.unavailableReason());
        {
            // The SAME corpus the calibration arms are measured against, embedded
            // once for the class — same rivals, same rarity statistics, so this
            // probe and the table beside it describe one fixture and not two.
            ExperienceRetrieval recall =
                new ExperienceRetrieval(sharedStore, () -> null, sharedIndex);
            Map<String, Object> answer = recall.recall(
                new RecallQuery(null, null, null, PARAPHRASE_CUE, null));

            List<String> ranked = new ArrayList<>();
            for (String key : new String[] {"entries", "analogies"}) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> part =
                    (List<Map<String, Object>>) answer.getOrDefault(key, List.of());
                part.forEach(m -> ranked.add(String.valueOf(m.get("id"))));
            }
            System.out.printf("[CRITERION C] result=%s returned=%s%n",
                answer.get("result"),
                ranked.stream().map(s -> s.substring(0, Math.min(8, s.length()))).toList());
            assertTrue(ranked.stream().anyMatch(id -> id.startsWith(RATCHET_LESSON)),
                "D1 criterion (c): the coverage-collapse question must return the "
                + "ratchet lesson. Returned: " + ranked);
            // POSITION, not merely presence. The dossier publishes "returns it
            // FIRST"; presence alone passed when the answer was 4th, so with
            // only that assertion the whole ordering repair could be reverted
            // and no test in the suite would fail. The re-audit found exactly
            // that, on the sprint's headline. This line is the end-to-end
            // evidence that the merged ranking reaches the agent.
            assertTrue(ranked.get(0).startsWith(RATCHET_LESSON),
                "D1 criterion (c): the ratchet lesson must lead the answer, not "
                + "merely appear in it — leading id was " + ranked.get(0));
        }                                 // the corpus is closed in @AfterAll
    }

    /** The cue as the agent would pose it: a symbol cue by symbol, prose by symptom. */
    private static RecallQuery query(JsonNode cue, String text) {
        return "symbol".equals(cue.path("keyword_cue_field").asText("symptom"))
            ? new RecallQuery(text, null, null, null, null)
            : new RecallQuery(null, null, null, text, null);
    }

    // The winner-only rule that used to judge the union and keyword arms lived
    // here. It is gone: every arm is now judged by contractHolds, which checks
    // BOTH halves of the frozen contract. Keeping a weaker rule available is how
    // two of five arms came to be scored by it while the table above them
    // claimed one contract for all.

    /** The full unfloored meaning profile for one cue — what the policy judges on. */
    private static Map<String, Double> profileOf(EmbeddingIndex index, String cue) {
        Map<String, Double> profile = new java.util.LinkedHashMap<>();
        for (EmbeddingIndex.Hit h : index.nearestEntries(cue, Integer.MAX_VALUE, 0.0)) {
            profile.put(h.id(), h.score());
        }
        return profile;
    }

    /**
     * The loaded, embedded corpus — built ONCE for the whole class.
     *
     * <p>Both gates here need the same corpus embedded, and each building its
     * own cost minutes of silence. The runner halts a test that emits no event
     * for five minutes (it cannot tell a hang from work, and JUnit events are
     * what reset that clock — printing progress does not), so the second gate
     * tripped the watchdog and the run died at exit 124 with the measurement
     * never taken. Embedding once halves the work and puts it inside class
     * setup rather than inside a single test's silent stretch.</p>
     *
     * <p>The sample stays what it was: shortening it would make the gate easier,
     * which is not a fix for a timing problem.</p>
     */
    private static H2ExperienceStore sharedStore;
    private static EmbeddingIndex sharedIndex;

    @org.junit.jupiter.api.BeforeAll
    static void embedTheCorpusOnce() throws Exception {
        String corpusPath = System.getProperty(CORPUS_PROPERTY);
        if (corpusPath == null || !Files.exists(Path.of(corpusPath))
                || !EmbeddingService.shared().available()) {
            return;                       // the tests abort with the reason
        }
        JsonNode accept;
        try (InputStream in = CalibrationGateTest.class.getResourceAsStream(ACCEPT_SETS)) {
            accept = new ObjectMapper().readTree(in);
        }
        Set<String> mustInclude = new LinkedHashSet<>(acceptedIds(accept));
        mustInclude.add(RATCHET_LESSON);  // criterion (c)'s answer, or it cannot be found
        sharedStore = H2ExperienceStore.openMemory();
        int rivals = Integer.getInteger("jawata.embed.corpus.sample", 700);
        int loaded = loadCorpus(sharedStore, Path.of(corpusPath), rivals, mustInclude);
        sharedIndex = new EmbeddingIndex(sharedStore, EmbeddingService.shared());
        int embedded = embedAll(sharedIndex, "CORPUS");
        System.out.printf("[CORPUS] %d loaded, %d embedded, identity=%s%n",
            loaded, embedded, EmbeddingService.shared().identityKey());
    }

    @org.junit.jupiter.api.AfterAll
    static void closeTheCorpus() {
        if (sharedStore != null) {
            sharedStore.close();
            sharedStore = null;
            sharedIndex = null;
        }
    }

    /** Embed the whole loaded corpus, reporting each slice for diagnosis. */
    private static int embedAll(EmbeddingIndex index, String label) {
        int embedded = 0;
        long started = System.currentTimeMillis();
        for (int pass = 0; pass < 200; pass++) {
            int n = index.backfill(200);
            embedded += n;
            if (n == 0) {
                break;
            }
            System.out.printf("[%s] embedded %d rows (%.0fs)%n",
                label, embedded, (System.currentTimeMillis() - started) / 1000.0);
        }
        return embedded;
    }

    /** A recall answer as one ranking: gated entries first, then analogies. */
    @SuppressWarnings("unchecked")
    private static List<String> rankedAnswer(Map<String, Object> answer) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> m
                : (List<Map<String, Object>>) answer.getOrDefault("entries", List.of())) {
            ids.add(String.valueOf(m.get("id")));
        }
        for (Map<String, Object> m
                : (List<Map<String, Object>>) answer.getOrDefault("analogies", List.of())) {
            ids.add(String.valueOf(m.get("id")));
        }
        return ids;
    }

    /** The first id of a ranking, abbreviated; "-" when the ranking is empty. */
    private static String head(List<String> ranked) {
        return ranked.isEmpty() ? "-" : ranked.get(0).substring(0, 8);
    }

    /** 1-based position of the first id with this prefix, or "absent". */
    private static String rankOf(List<String> ranked, String prefix) {
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).startsWith(prefix)) {
                return String.valueOf(i + 1);
            }
        }
        return "absent";
    }

    /** Ids of a score map, best first. */
    private static List<String> rankedIds(Map<String, Double> scores) {
        List<Map.Entry<String, Double>> es = new ArrayList<>(scores.entrySet());
        es.sort(java.util.Comparator.comparingDouble(
                (Map.Entry<String, Double> e) -> e.getValue()).reversed()
            .thenComparing(Map.Entry::getKey));
        return es.stream().map(Map.Entry::getKey).toList();
    }

    /**
     * The SAME frozen contract the other arms are judged by, applied to a bare
     * ranking: the winner is acceptable AND the designated entry is inside K.
     * Judging a new arm by a friendlier rule would make its number
     * incomparable with the ones it is printed beside.
     */
    private static boolean contractHolds(List<String> ranked, Set<String> acceptable,
                                         String designated) {
        if (ranked.isEmpty()) {
            return false;
        }
        String winner = ranked.get(0);
        boolean winnerOk = acceptable.stream().anyMatch(winner::startsWith);
        boolean designatedInWindow = ranked.stream().limit(FROZEN_K)
            .anyMatch(id -> id.startsWith(designated));
        return winnerOk && designatedInWindow;
    }

    /**
     * The frozen contract measured on the MEANING RANKING ALONE — no keyword,
     * no symbol path: the winner must be in the accept set AND the designated
     * entry must sit inside the top {@link #FROZEN_K}.
     *
     * <p>This reads the ranking straight off the index rather than through
     * {@code recall()}, because every {@code recall()} answer is a union by
     * construction. A union-only number cannot fail when the symbol path
     * carries a cue the embeddings lost, which is the masking the C2 clause
     * was written to prevent.</p>
     */
    private static boolean embeddingsAlonePasses(EmbeddingIndex index, String cueText,
                                                 Set<String> acceptable, String designated) {
        List<EmbeddingIndex.Hit> ranked = index.nearestEntries(cueText, FROZEN_K, 0.0);
        if (ranked.isEmpty()) {
            return false;
        }
        String winner = ranked.get(0).id();
        boolean winnerOk = acceptable.stream().anyMatch(winner::startsWith);
        boolean designatedInWindow = ranked.stream()
            .anyMatch(h -> h.id().startsWith(designated));
        return winnerOk && designatedInWindow;
    }

    /** The first three ranked ids of an answer (entries then analogies), for diagnosis. */
    @SuppressWarnings("unchecked")
    private static String top3(Map<String, Object> answer) {
        List<Map<String, Object>> ranked = new ArrayList<>(
            (List<Map<String, Object>>) answer.getOrDefault("entries", List.of()));
        ranked.addAll((List<Map<String, Object>>) answer.getOrDefault("analogies", List.of()));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ranked.size() && i < 3; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.valueOf(ranked.get(i).get("id")), 0, 8);
        }
        return sb.append(']').toString();
    }

    /** Every id any accept set names — these must always be in the corpus. */
    private static Set<String> acceptedIds(JsonNode accept) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode cue : accept.get("cues")) {
            for (JsonNode a : cue.get("accept_set")) {
                ids.add(a.asText());
            }
        }
        return ids;
    }

    /** Load a deterministic SAMPLE of an {@code experience export} into the store. */
    private static int loadCorpus(H2ExperienceStore store, Path export,
                                  int rivals, Set<String> mustInclude) throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(export));
        JsonNode all = root.path("data").path("entries");
        List<JsonNode> chosen = new ArrayList<>();
        List<JsonNode> others = new ArrayList<>();
        for (JsonNode e : all) {
            String id = e.path("id").asText("");
            boolean required = mustInclude.stream().anyMatch(id::startsWith);
            (required ? chosen : others).add(e);
        }
        // A deterministic spread rather than the first N, so the rivals are not
        // all drawn from one era of the store.
        int want = Math.max(1, rivals - chosen.size());
        int stride = Math.max(1, others.size() / want);
        for (int i = 0; i < others.size() && chosen.size() < rivals; i += stride) {
            chosen.add(others.get(i));
        }
        // IMPORT rather than put(): put() mints a NEW id, which would make every
        // accept-set comparison fail no matter how good retrieval was. The
        // export/import pair round-trips the original ids, and those ids are
        // what the frozen accept sets are written against.
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode e : chosen) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = mapper.convertValue(e, Map.class);
            // The export shape carries the anchor inside `body`, while the
            // import writes the `symbol_fqn` COLUMN — and that column is what
            // the symbol cue matches on. Without this lift every anchor imports
            // as null, the symbol path finds nothing, and the gate would blame
            // retrieval for a defect in its own fixture.
            @SuppressWarnings("unchecked")
            Map<String, Object> body = row.get("body") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
            if (row.get("symbol_fqn") == null && body.get("symbol") != null) {
                row.put("symbol_fqn", String.valueOf(body.get("symbol")));
            }
            if (row.get("package_name") == null && body.get("package") != null) {
                row.put("package_name", String.valueOf(body.get("package")));
            }
            // Sprint 27a D3: symptoms live under `body` in the export, but
            // importEntries writes them to experience_symptom from the TOP-LEVEL
            // `symptoms` key — and it is that table the embed text's LISTAGG
            // reads. Without this lift the imported corpus carries no symptoms
            // and the D3 measurement is of a symptom-less store, invisibly.
            if (row.get("symptoms") == null && body.get("symptoms") instanceof List<?>) {
                row.put("symptoms", body.get("symptoms"));
            }
            rows.add(row);
        }
        Map<String, Object> report = store.importEntries(rows);
        // The REPORT is the truth, not rows.size(): an insert that failed is a
        // smaller corpus, and a gate over a silently smaller corpus lies.
        System.out.println("[E2 GATE] import report: " + report);
        return (int) (Integer) report.getOrDefault("imported", 0);
    }
}
