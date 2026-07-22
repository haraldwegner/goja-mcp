package org.jawata.mcp.knowledge;

import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 21 Stage 2 — the two-phase, fit-gated, <em>terminal</em> retrieval contract
 * (recall-gap design notes §7). A cue resolves to a closed, scope-filtered set of fitting
 * nodes (pointers resolved to current code through JDT) OR an authoritative absence —
 * never a similarity-ranked pile the agent must sift.
 *
 * <ul>
 *   <li><b>Phase 1 (fuzzy gather):</b> {@link ExperienceStore#query} keyword/alias-matches
 *       any present cue over the indexed columns + the symptom child table. Deterministic
 *       (LIKE/normalize) — embeddings are Sprint 27, behind this same gate.</li>
 *   <li><b>Phase 2 (fit gate):</b> keep only candidates whose scope <em>contains</em> the
 *       cue (symbol equal/enclosing, package equal/enclosing, operation equal, symptom
 *       alias-match). A candidate that merely surfaced in Phase 1 but does not contain the
 *       cue is dropped — that is the denoising step.</li>
 *   <li><b>Terminal:</b> 0 fitting → {@code absence}; ≥1 → the fit set ordered by
 *       scope-specificity › confidence › recency, each pointer JDT-resolved (or flagged
 *       stale). The single most-specific node is the head; callers wanting ≤1 (the advisor)
 *       take the head.</li>
 * </ul>
 */
public final class ExperienceRetrieval {

    private static final Logger log = LoggerFactory.getLogger(ExperienceRetrieval.class);

    /** Cap the closed fit set so a pathological cue can't return a pile; report if capped. */
    private static final int MAX_TERMINAL = 5;

    public static final String RESULT_MATCH = "match";
    public static final String RESULT_ABSENCE = "absence";
    public static final String RESULT_PRIMER = "primer";
    /** Sprint 27: nothing passed the gate, but comparable experience exists. */
    public static final String RESULT_ANALOGY = "analogy";

    /** Domain-layer entry types + scope kinds — the always-relevant knowledge the primer pushes.
     *  v2.2.3: widened with the standing how-to-work types a real memory corpus maps to
     *  (dogfood find: 97 md-loaded entries, zero {@code domain_fact} — the primer injected
     *  nothing). References/projects/notes stay cue-gated. */
    private static final java.util.Set<String> DOMAIN_TYPES = java.util.Set.of(
        "domain_fact", "domain_concept", "bounded_context", "invariant", "ubiquitous_language",
        "user", "feedback", "naming_convention", "api_contract", "convention");
    private static final java.util.Set<String> DOMAIN_SCOPES = java.util.Set.of(
        "bounded_context", "domain_concept");

    private final ExperienceStore store;
    private final Supplier<IJdtService> jdt;
    /** Sprint 27: the meaning nominator. NULL is the supported degrade state —
     *  with no index this class behaves exactly as it did in v3.3.1. */
    private final EmbeddingIndex index;
    /** Sprint 27 D6: measurement. NULL = not installed; retrieval is identical
     *  either way, because a counter must never change what it counts. */
    private QualityLedger quality;

    /**
     * Retrieval over {@code store}, WITH meaning-based nomination whenever the
     * store can carry it.
     *
     * <p>v3.4.1: this constructor used to pass {@code null} for the index, which
     * made every one of the three production call sites keyword-only while every
     * test — each wiring the index by hand — passed. The index is now derived
     * from the store ({@link EmbeddingIndex#forStore}), so recall is semantic by
     * DEFAULT and a caller cannot forget it. A store that cannot carry an index
     * still degrades to exactly the keyword behaviour.</p>
     */
    public ExperienceRetrieval(ExperienceStore store, Supplier<IJdtService> jdt) {
        this(store, jdt, EmbeddingIndex.forStore(store));
    }

    /**
     * Retrieval with meaning-based nomination DELIBERATELY OFF — the pre-Sprint-27
     * behaviour, for the degrade tests and for the calibration gate's keyword arm.
     *
     * <p>It exists so that "no semantic nomination" is something a caller STATES.
     * Before v3.4.1 it was merely implied by picking the shorter constructor, which
     * is how three production sites turned the feature off without anyone deciding
     * to — and how a test could measure keyword-vs-semantic while both arms were
     * secretly the same.</p>
     */
    public static ExperienceRetrieval keywordOnly(ExperienceStore store,
            Supplier<IJdtService> jdt) {
        return new ExperienceRetrieval(store, jdt, null);
    }

    public ExperienceRetrieval(ExperienceStore store, Supplier<IJdtService> jdt,
                               EmbeddingIndex index) {
        this.store = store;
        this.jdt = jdt;
        this.index = index;
    }

    /** Sprint 27 D6: install the quality ledger (application wiring / tests). */
    public void setQualityLedger(QualityLedger ledger) {
        this.quality = ledger;
    }

    private void count(Runnable measurement) {
        if (quality != null) {
            measurement.run();
        }
    }

    /** Terminal recall — a {@code match} document with the fit set, or an {@code absence}. */
    public Map<String, Object> recall(RecallQuery q) {
        return recall(q, QualityLedger.SURFACE_QUESTION_HOOK);
    }

    /**
     * Recall, naming the SURFACE it is being asked from (Sprint 27 D6).
     *
     * <p>Retrieval is identical whatever the surface is — the name only decides
     * which counter advances. It is a parameter because the mcp cannot infer it:
     * a seat's recall and a prompt hook's recall arrive as the same call, and
     * guessing would make one of the two counters permanently wrong.</p>
     */
    public Map<String, Object> recall(RecallQuery q, String surface) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cue", cueMap(q));
        if (q == null || q.isEmpty()) {
            out.put("result", RESULT_ABSENCE);
            out.put("reason", "no cue provided");
            out.put("message", "No cue — provide symbol / package / operation / symptom.");
            out.put("entries", List.of());
            return out;
        }

        List<StoredEntry> candidates = store.query(q);

        // Sprint 27 D2 — THE KIND SPLIT, as ROUTING rather than as a second
        // pipeline (Harald's ruling, 2026-07-21).
        //
        // The signed ontology is about ADMISSION: scope containment must never
        // DELETE experience learned elsewhere. It is not a licence to bypass
        // the retrieval pipeline itself — Sprints 21c/21e earned nine
        // behaviours inside it (return the answering SECTION rather than its
        // whole file, prefer the more specific scope, never drop recorded
        // entries as family duplicates, match symptom words non-adjacently, …)
        // and every one applies to experience. Routing experience around them
        // deletes all nine, and caps at 2 what today returns up to 5 — a cue
        // would come back with LESS than before. So:
        //
        //   • the gate still decides what counts as a confirmed FIT, unchanged;
        //   • it no longer EXCLUDES anything — experience it turns away is
        //     offered below as capped, labeled analogy, together with whatever
        //     meaning nomination finds.
        //
        // The gate stops excluding; it only routes.
        List<StoredEntry> fitting = new ArrayList<>();
        List<StoredEntry> turnedAwayExperience = new ArrayList<>();
        for (StoredEntry e : candidates) {
            if (fits(e, q)) {
                fitting.add(e);
            } else if (KnowledgeKind.of(e).isExperience()) {
                turnedAwayExperience.add(e);
            }
        }
        // Sprint 27 D2 — the SECOND nominator. Keyword gathered `candidates`
        // above; meaning gathers its own, and the two are a UNION, never a
        // replacement: an FQN cue is answered deterministically by the keyword
        // side and badly by meaning, while a paraphrase is the reverse (C0
        // measured both). Experience that the fit gate did not admit is offered
        // as capped, labeled ANALOGY — never as fact.
        // One semantic scan serves BOTH uses below: tie-breaking the fit set's
        // ordering, and nominating analogies. (Brute-force cosine scans the
        // store either way; scanning once is just not paying twice.)
        Map<String, Double> meaning = meaningScores(q);

        List<ExperienceAnalogies.Analogy> analogies =
            analogies(q, fitting, turnedAwayExperience, meaning);

        if (fitting.isEmpty()) {
            if (analogies.isEmpty()) {
                // Sprint 27a D2: the surface was consulted and STAYED SILENT.
                // Counted as the abstain counterpart of a speak, so a silent
                // surface is distinguishable from one never consulted.
                count(() -> quality.silent(surface));
                out.put("result", RESULT_ABSENCE);
                out.put("reason", candidates.isEmpty() ? "no candidates" : "no candidate fit the cue's scope");
                out.put("message", "No known knowledge for this cue.");
                out.put("entries", List.of());
                return out;
            }
            // Not an absence: nothing is asserted about this cue, but comparable
            // experience exists. Said in its own words so a caller can never
            // read an analogy as a gated fact.
            count(() -> quality.fired(surface));
            out.put("result", RESULT_ANALOGY);
            out.put("count", 0);
            out.put("entries", List.of());
            out.put("analogies", ExperienceAnalogies.toMaps(analogies));
            out.put("message", "No established fact for this cue — "
                + analogies.size() + " comparable experience(s) below; judge whether they transfer.");
            return out;
        }

        // Sprint 21c (item B): the section IS the fact — when a section and its
        // file-parent both fit (same source_ref), drop the PARENT bundle; recall
        // answers with the fact and injection pays only the fact's tokens. Sibling
        // sections that both fit REMAIN (the hook's ambiguity signal, item D).
        if (fitting.size() > 1) {
            java.util.Set<String> sectionSources = new java.util.HashSet<>();
            for (StoredEntry e : fitting) {
                if (e.isSection() && e.sourceRef() != null) {
                    sectionSources.add(e.sourceRef());
                }
            }
            if (!sectionSources.isEmpty()) {
                fitting.removeIf(e -> !e.isSection() && e.sourceRef() != null
                    && sectionSources.contains(e.sourceRef()));
            }
        }

        // Disambiguate: scope-specificity › member affinity › confidence › recency.
        // Sprint 21e: a #member cue ranks entries that KNOW the member (anchor or
        // symptom/summary mention) above the type's other lessons — live finding: a
        // real corpus holds MANY facts anchored to one busy type (17 for the ORB
        // SlotManager), and without affinity the top-N tie broke on insertion order.
        String memberToken = q.hasSymbol() && q.symbol().indexOf('#') >= 0
            ? q.symbol().substring(q.symbol().indexOf('#') + 1)
            : null;
        // Sprint 27 (R1 iteration 1): meaning breaks TIES — and only ties. The
        // 21c ordering contract (specificity › member affinity › confidence)
        // ranks first, untouched. But a bare type-name cue can leave the whole
        // fit set TIED on all three (same specificity, no member token, same
        // confidence, loaded the same day), and then the old final key —
        // insertion timestamp — decided the WINNER by file-load order. An
        // arbitrary tail loses to an informed one: meaning ranks within the
        // tie, recency only after that. Nothing is added or removed by this;
        // it is purely which of the same entries comes first.
        fitting.sort(Comparator
            .comparingInt(StoredEntry::specificity).reversed()
            .thenComparing(Comparator.comparingInt(
                (StoredEntry e) -> memberAffinity(e, memberToken)).reversed())
            .thenComparing(Comparator.comparingInt(StoredEntry::confidenceRank).reversed())
            .thenComparing(Comparator.comparingDouble(
                (StoredEntry e) -> meaning.getOrDefault(e.id(), 0.0)).reversed())
            .thenComparing(e -> e.createdAt() == null ? 0L : -e.createdAt().toEpochMilli()));

        boolean capped = fitting.size() > MAX_TERMINAL;
        List<StoredEntry> top = capped ? fitting.subList(0, MAX_TERMINAL) : fitting;

        List<Map<String, Object>> entries = new ArrayList<>();
        for (StoredEntry e : top) {
            entries.add(present(e));
        }
        count(() -> quality.fired(surface));
        out.put("result", RESULT_MATCH);
        out.put("count", entries.size());
        if (capped) {
            out.put("capped_from", fitting.size());
            log.info("recall fit set capped {} -> {} for cue {}", fitting.size(), MAX_TERMINAL, cueMap(q));
        }
        out.put("entries", entries);
        if (!analogies.isEmpty()) {
            out.put("analogies", ExperienceAnalogies.toMaps(analogies));
        }
        return out;
    }

    /**
     * Sprint 27a D9 — the WORD scan for this cue: rarity-weighted overlap over
     * every live row.
     *
     * <p>It runs beside {@link #meaningScores} rather than instead of it,
     * because the two miss different cues: measured on the frozen set, meaning
     * alone answers 9 of 12 and words alone 4 of 12, and the cue words rescue
     * is one meaning ranks 28th.</p>
     *
     * <p>Needs no model, so this is also what the degrade path gains: with the
     * embedder off the store still matches on words, which the older
     * conjunctive substring rule effectively could not.</p>
     *
     * <p><b>COST, disclosed rather than discovered later.</b> This reads every
     * live row and tokenises all of it on EVERY recall, on every surface, with
     * no cache: rarity is a property of the whole corpus, so the statistics are
     * recomputed per cue. On the ~2,080-row live store that is a full table read
     * plus a term-frequency map per row — materially more than the meaning scan
     * beside it, which reads only {@code id, embedding} and parses nothing.
     * Nothing here is cached and nothing claims to be. If a profiler run shows
     * it dominating recall latency, the fix is a cached inverted index
     * invalidated on write; that is deliberately NOT built yet, because an
     * unmeasured optimisation is how a cache-invalidation bug enters a store
     * whose whole job is telling the truth.</p>
     */
    private Map<String, Double> lexicalScores(RecallQuery q) {
        String cue = cueText(q);
        if (cue.isBlank()) {
            return Map.of();
        }
        // REJECTED AND SUPERSEDED ROWS ARE NOT CANDIDATES. Both sibling paths
        // enforce this — the keyword query in its SQL, EmbeddingIndex in its —
        // and a third path that did not would let a note the user threw away
        // come back by another door. The C2b audit found exactly that here, and
        // the suite missed it because the guarding test's fixture holds one row
        // whose words the cue does not contain.
        List<StoredEntry> live = new ArrayList<>();
        for (StoredEntry e : store.all()) {
            if (isLive(e)) {
                live.add(e);
            }
        }
        return LexicalIndex.score(cue, live);
    }

    /**
     * A row the user has not thrown away.
     *
     * <p>Filtered here as well as at the pool, on purpose: rarity is computed
     * over whatever corpus is handed in, so leaving dead rows in the scan would
     * distort every surviving row's weight even though the dead ones could not
     * be returned.</p>
     */
    private static boolean isLive(StoredEntry e) {
        return !ExperienceEntry.REJECTED.equals(e.status())
            && !ExperienceEntry.SUPERSEDED.equals(e.status());
    }

    /**
     * One brute-force semantic scan for this cue: every scored id, unfloored.
     * Empty when there is no index, it is unavailable, or the cue is blank —
     * the degrade path, in which the tie-break simply contributes 0s and the
     * ordering falls back to what the word stream and recency give.
     */
    private Map<String, Double> meaningScores(RecallQuery q) {
        if (index == null || !index.available()) {
            return Map.of();
        }
        String cue = cueText(q);
        if (cue.isBlank()) {
            return Map.of();
        }
        Map<String, Double> scores = new LinkedHashMap<>();
        for (EmbeddingIndex.Hit h : index.nearestEntries(cue, Integer.MAX_VALUE, 0.0)) {
            scores.put(h.id(), h.score());
        }
        return scores;
    }

    /**
     * Meaning-nominated EXPERIENCE that the fit gate did not already admit.
     *
     * <p>Two exclusions carry the ontology, and both matter:</p>
     * <ul>
     *   <li><b>Facts are never offered as analogies.</b> A fact that failed the
     *       address gate has failed, and must stay failed — routing it here
     *       would smuggle an unverified statement about code past the very gate
     *       that exists to stop it.</li>
     *   <li><b>Nothing already returned is repeated.</b> The same entry in both
     *       lists would spend the agent's context twice to say one thing.</li>
     * </ul>
     *
     * <p>With no index, or an unavailable one, this returns empty — which is the
     * degrade path, and leaves recall behaving exactly as it did before
     * semantic retrieval existed.</p>
     */
    private List<ExperienceAnalogies.Analogy> analogies(RecallQuery q,
                                                       List<StoredEntry> alreadyReturned,
                                                       List<StoredEntry> turnedAway,
                                                       Map<String, Double> meaning) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (StoredEntry e : alreadyReturned) {
            seen.add(e.id());                 // never say the same thing twice
        }
        List<StoredEntry> pool = new ArrayList<>();
        for (StoredEntry e : turnedAway) {
            if (seen.add(e.id())) {
                pool.add(e);
            }
        }

        // The UNION: meaning nominates alongside keyword, never instead of it.
        //
        // Sprint 27a D1 - the meaning half NOMINATES and does not judge. The
        // policy returns the nearest few above a junk floor; whether any of
        // them actually answers this cue is the reading agent's call, which is
        // why they must be rendered as nominees and never as vouched answers.
        // Measurement killed the alternative: no statistic over the score
        // profile separates a real cue from a nonsense one (a nonsense control
        // outscores a correct answer by 2x), and the relative margin tried
        // first did not survive a change of corpus size. See AnalogyPolicy.
        //
        // EmbeddingIndex.NOMINATION_FLOOR keeps its original and different job
        // (a volume cap for fact nomination) and does not decide this.
        //
        // The keyword half is deliberately NOT subject to the policy: with no
        // embedder the profile is empty and nothing would be nominated at all,
        // which would turn the degrade path into silence.
        //
        // What is NO LONGER true, corrected at the C2b re-audit: this once read
        // "keyword analogies must survive exactly as they did in v3.3.1". Since
        // the merged ranking became the primary order, a keyword-turned-away row
        // sorts BEHIND everything the merge nominated, and at the cap can be
        // pushed out of the answer entirely. It still ADMITS exactly what it
        // always did; what changed is where it lands. Saying otherwise would
        // assert an invariant three lines above the call site that breaks it.
        Map<String, Double> lexical = lexicalScores(q);
        List<String> nominated = AnalogyPolicy.nominate(meaning, lexical);
        List<String> ids = new ArrayList<>();
        for (String id : nominated) {
            if (seen.add(id)) {
                ids.add(id);
            }
        }
        for (StoredEntry e : store.byIds(ids)) {
            // A FACT that failed its address gate must NOT reappear here -
            // that would smuggle an unverified statement about code past the
            // gate that exists to stop it.
            //
            // And a REJECTED or SUPERSEDED row must not reappear here either.
            // Each nominating path filters status for itself - the keyword query
            // in SQL, EmbeddingIndex in SQL, lexicalScores in Java - and the
            // C2b audit found the third of those had been added without it. This
            // is the choke point every nomination converges on, so enforcing it
            // HERE makes the invariant stream-independent: a fourth nominator
            // cannot reintroduce the bug a fourth time.
            if (KnowledgeKind.of(e).isExperience() && isLive(e)) {
                pool.add(e);
            }
        }
        if (pool.isEmpty()) {
            return List.of();
        }
        // The ceiling is the policy's, not a fixed two - the cap of two is what
        // hid a correct third answer.
        return ExperienceAnalogies.rank(pool, q, meaning, lexical, nominated,
            AnalogyPolicy.MAX_NOMINEES, jdt);
    }

    /** The text a cue is embedded as — its words, in the order a human would say them. */
    static String cueText(RecallQuery q) {
        if (q == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {q.symptom(), q.operation(), q.symbol(),
                                         q.packageName(), q.externalSystem()}) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part.trim());
            }
        }
        return sb.toString();
    }

    /**
     * Sprint 21 Stage 5 — the domain-layer primer: the accepted DOMAIN nodes in the store,
     * for the always-on SessionStart injection (vs cue-gated recall). Domain is the layer
     * that is always relevant (bounded contexts, concepts, invariants, ubiquitous language),
     * so it is pushed up front. Ordered confidence › recency, capped at {@code limit}.
     */
    public Map<String, Object> primer(int limit) {
        List<StoredEntry> domain = new ArrayList<>();
        for (StoredEntry e : store.all()) {
            if (!ExperienceEntry.ACCEPTED.equals(e.status())) {
                continue;
            }
            String type = e.type() == null ? "" : e.type().toLowerCase(Locale.ROOT);
            Object sk = e.body().get("scope_kind");
            String scopeKind = sk == null ? "" : sk.toString().toLowerCase(Locale.ROOT);
            if (DOMAIN_TYPES.contains(type) || DOMAIN_SCOPES.contains(scopeKind)) {
                domain.add(e);
            }
        }
        domain.sort(Comparator
            .comparingInt(StoredEntry::confidenceRank).reversed()
            .thenComparing(e -> e.createdAt() == null ? 0L : -e.createdAt().toEpochMilli()));

        Map<String, Object> out = new LinkedHashMap<>();
        if (domain.isEmpty()) {
            out.put("result", RESULT_ABSENCE);
            out.put("message", "No domain knowledge loaded.");
            out.put("entries", List.of());
            return out;
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (int i = 0; i < domain.size() && i < limit; i++) {
            entries.add(present(domain.get(i)));
        }
        count(() -> quality.fired(QualityLedger.SURFACE_PRIMER));
        out.put("result", RESULT_PRIMER);
        out.put("count", entries.size());
        out.put("entries", entries);
        return out;
    }

    /**
     * Render a recall/primer result as flat, injection-ready lines (Stage 5 {@code
     * format=text}). Rendering lives here (reactor-tested), so the push hooks stay dumb —
     * they peel the MCP envelope and emit these lines verbatim.
     */
    public static String renderText(Map<String, Object> result) {
        Object res = result.get("result");
        if (RESULT_ABSENCE.equals(res)) {
            Object msg = result.get("message");
            return msg == null ? "No known knowledge for this cue." : msg.toString();
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
            (List<Map<String, Object>>) result.getOrDefault("entries", List.of());
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> e : entries) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(renderEntryLine(e));
        }
        // Sprint 27: analogies render BELOW the gated facts and are visibly
        // different in kind — advisory framing, basis and provenance in words,
        // and never a similarity number (a score in the text invites treating
        // it as authority, which is precisely what an analogy is not).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> analogies =
            (List<Map<String, Object>>) result.getOrDefault("analogies", List.of());
        for (Map<String, Object> a : analogies) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(renderAnalogyLine(a));
        }
        return sb.toString();
    }

    /** One analogy line: what it was, why it surfaced, where it was learned. */
    static String renderAnalogyLine(Map<String, Object> a) {
        StringBuilder sb = new StringBuilder("In a similar situation: ");
        sb.append(san(a.get("summary")));
        Object basis = a.get("basis");
        sb.append("  [");
        sb.append(basis instanceof List<?> l ? String.join("; ",
            l.stream().map(String::valueOf).toList()) : san(basis));
        Object prov = a.get("provenance");
        if (prov != null) {
            sb.append("; ").append(san(prov));
        }
        sb.append(']');
        // Sprint 27 D4: the dispatch facts of a past seat run, on the line the
        // analogy already occupies — no second surface, no new command.
        Object dispatch = a.get("dispatch");
        if (dispatch instanceof Map<?, ?> d) {
            sb.append("  (").append(renderDispatch(d)).append(')');
        }
        return sb.toString();
    }

    /** Flat form of the {@code dispatch} block — seat, verdict, outcome, in that order. */
    static String renderDispatch(Map<?, ?> d) {
        StringBuilder sb = new StringBuilder("past run: seat ");
        Object seat = d.get("seat");
        sb.append(seat == null ? "unnamed" : san(seat));
        Object target = d.get("target");
        if (target != null) {
            sb.append(" on ").append(san(target));
        }
        sb.append(" — human verdict: ").append(san(d.get("human_verdict")));
        Object outcome = d.get("outcome");
        if (outcome != null) {
            sb.append(" — outcome: ").append(san(outcome));
        }
        return sb.toString();
    }

    /** Sprint 21a (item G): render a curation list as flat lines ({@code list} format=text). */
    public static String renderList(List<StoredEntry> rows) {
        if (rows == null || rows.isEmpty()) {
            return "No entries match.";
        }
        StringBuilder sb = new StringBuilder();
        for (StoredEntry e : rows) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(san(e.id())).append(" [").append(san(e.type())).append('/')
                .append(san(e.status())).append("] ").append(san(e.summary()));
            if (e.symbolFqn() != null && !e.symbolFqn().isBlank()) {
                sb.append(" @ ").append(san(e.symbolFqn()));
            }
        }
        return sb.toString();
    }

    static String renderEntryLine(Map<String, Object> e) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(san(e.get("type"))).append("] ").append(san(e.get("summary")));
        Object status = e.get("status");
        if (status != null) {
            sb.append(" (").append(san(status)).append(')');
        }
        // Sprint 27 D4: for a seat run the dispatch facts REPLACE the raw
        // journal blob — a truncated JSON string is where the seat and the
        // verdict used to go to die.
        Object dispatch = e.get("dispatch");
        if (dispatch instanceof Map<?, ?> d) {
            return sb.append(" — ").append(renderDispatch(d)).toString();
        }
        Object details = e.get("details");
        if (details != null) {
            String d = san(details);
            if (d.length() > 160) {
                d = d.substring(0, 157) + "...";
            }
            sb.append(" — ").append(d);
        }
        return sb.toString();
    }

    /**
     * Keep a rendered line safe for the push hook's double-encode round-trip + JSON re-emit:
     * strip quotes / backslashes / control chars (line breaks collapse to spaces), so the
     * bash envelope-peel and the {@code additionalContext} JSON never break on a stray char.
     */
    private static String san(Object o) {
        if (o == null) {
            return "";
        }
        return o.toString()
            .replace('\\', '/')
            .replace('"', '\'')
            .replaceAll("[\\r\\n\\t]+", " ")
            .trim();
    }

    // --- Phase 2: fit gate (scope-containment) -----------------------------------------

    /**
     * True iff the entry's scope contains the cue. <b>Subject</b> cues (symbol / package /
     * symptom / external_system) locate the knowledge — when any is present the entry must
     * fit on a subject (OR over subjects). <b>operation</b> is a <b>refinement</b>, not an
     * independent matcher: it narrows a subject fit (an entry declaring a <em>different</em>
     * operation is dropped) and only stands alone in an operation-only query. This stops a
     * same-kind failure on an unrelated symbol from leaking through on the operation alone.
     */
    boolean fits(StoredEntry e, RecallQuery q) {
        boolean subjectPresent =
            q.hasSymbol() || q.hasPackage() || q.hasSymptom() || q.hasExternalSystem();
        if (!subjectPresent) {
            // Operation-only (refinement) query: fit iff the entry is that operation.
            boolean only = q.hasOperation() && eqIgnoreCase(e.operation(), q.operation());
            count(() -> quality.gate("operation", only));
            return only;
        }
        // Sprint 27 D6: each criterion is counted for what IT said, not for what
        // the disjunction concluded — a criterion that never admits anything is
        // only visible when it is measured separately. Evaluated eagerly here
        // (no short-circuit) so a later criterion is not silently uncounted;
        // all four are cheap in-memory string checks.
        boolean symbolOk = q.hasSymbol() && symbolFits(e, q.symbol());
        boolean packageOk = q.hasPackage() && packageFits(e, q.packageName());
        boolean symptomOk = q.hasSymptom() && symptomFits(e, q.symptom());
        boolean externalOk = q.hasExternalSystem()
            && eqIgnoreCase(e.externalSystem(), q.externalSystem());
        count(() -> {
            if (q.hasSymbol()) {
                quality.gate("symbol", symbolOk);
            }
            if (q.hasPackage()) {
                quality.gate("package", packageOk);
            }
            if (q.hasSymptom()) {
                quality.gate("symptom", symptomOk);
            }
            if (q.hasExternalSystem()) {
                quality.gate("external_system", externalOk);
            }
        });
        if (!(symbolOk || packageOk || symptomOk || externalOk)) {
            return false;
        }
        // Refinement: an entry that declares a DIFFERENT operation is not about this one.
        boolean refined = !(q.hasOperation() && e.operation() != null && !e.operation().isBlank()
            && !eqIgnoreCase(e.operation(), q.operation()));
        if (q.hasOperation()) {
            count(() -> quality.gate("operation", refined));
        }
        return refined;
    }

    /** Symbol cue fits when the entry is scoped to that symbol (equal/enclosing), is a
     *  member OF the cue type, or is scoped to a package that contains it. Sprint 21e:
     *  cue and anchor match at TYPE level regardless of member notation — a type-level
     *  anchor answers a member cue AND a member anchor answers its type's cue. */
    private boolean symbolFits(StoredEntry e, String symbol) {
        String s = e.symbolFqn();
        if (s != null && !s.isBlank()) {
            if (s.equals(symbol) || symbol.startsWith(s + ".") || symbol.startsWith(s + "#")
                    || s.startsWith(symbol + "#")) {
                return true;
            }
        }
        String pkg = e.packageName();
        return pkg != null && !pkg.isBlank() && symbol.startsWith(pkg + ".");
    }

    /** Package cue fits when the entry governs that package (equal/enclosing) or holds a
     *  symbol inside it. */
    private boolean packageFits(StoredEntry e, String pkg) {
        String p = e.packageName();
        if (p != null && !p.isBlank()) {
            if (p.equals(pkg) || pkg.startsWith(p + ".")) {
                return true;               // entry package equals or encloses the cue package
            }
        }
        String s = e.symbolFqn();
        return s != null && !s.isBlank() && s.startsWith(pkg + ".");
    }

    /** Symptom cue fits when it alias-matches a stored symptom or the summary. */
    /** v2.2.3: TOKENIZED — every cue token must appear somewhere in the entry's symptoms
     *  or summary. The old contiguous-substring match missed summaries where the cue words
     *  are non-adjacent ("blank webview" vs "…webview content area stays blank…"). */
    private boolean symptomFits(StoredEntry e, String symptom) {
        String norm = H2ExperienceStore.normalize(symptom);
        if (norm.isEmpty()) {
            return false;
        }
        String haystack = String.join(" ", e.symptoms()) + " "
            + H2ExperienceStore.normalize(e.summary() == null ? "" : e.summary());
        for (String token : norm.split("\\s+")) {
            if (!haystack.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /** Sprint 21e: 1 when the entry knows the cue's member — anchored to it, or its
     *  (alias-normalized) symptoms/summary mention it; 0 otherwise. */
    private static int memberAffinity(StoredEntry e, String memberToken) {
        if (memberToken == null || memberToken.isBlank()) {
            return 0;
        }
        if (e.symbolFqn() != null && e.symbolFqn().endsWith("#" + memberToken)) {
            return 1;
        }
        String norm = H2ExperienceStore.normalize(memberToken);
        String haystack = String.join(" ", e.symptoms()) + " "
            + H2ExperienceStore.normalize(e.summary() == null ? "" : e.summary());
        return haystack.contains(norm) ? 1 : 0;
    }

    private static boolean eqIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    // --- Presentation: body + current status + JDT-resolved pointer ---------------------

    private Map<String, Object> present(StoredEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.id());
        m.put("status", e.status());       // current column status (body_json is frozen at insert)
        m.putAll(e.body());
        m.put("status", e.status());       // ...and win over the frozen body value
        // Sprint 27 D2: say WHICH kind this is, so nothing reads as a verified
        // statement about code unless it actually is one.
        KnowledgeKind kind = KnowledgeKind.of(e);
        m.put("kind", kind.isFact() ? "fact" : "experience");
        // Sprint 27 D4: a seat run reached by a scoped cue (operation="seat:x")
        // states the same dispatch facts the analogy path states — the facts
        // must not depend on WHICH nominator found the run.
        Map<String, Object> dispatch = DispatchRecall.toMap(DispatchRecall.of(e));
        if (dispatch != null) {
            m.put("dispatch", dispatch);
        }
        // Item I: only Java anchors are JDT-resolvable; a non-Java pointer stays a plain
        // FQN in the body rather than being presented with a misleading "stale" flag.
        if (e.isJavaResolvable()) {
            Map<String, Object> pointer = resolvePointer(e.symbolFqn());
            if (pointer != null) {
                if (kind.isExperience() && Boolean.TRUE.equals(pointer.get("stale"))) {
                    // For EXPERIENCE the anchor is provenance, not a criterion:
                    // the code being gone says nothing about whether the lesson
                    // still holds. Flagging it "stale" would tell the agent to
                    // discount knowledge that is still true — the exact error
                    // the ontology exists to prevent. A FACT keeps its stale
                    // flag, because there the address IS the claim.
                    pointer.remove("stale");
                    pointer.put("note", "learned here; the symbol no longer exists");
                }
                m.put("resolved_pointer", pointer);
            }
        }
        return m;
    }

    /**
     * Resolve a symbol pointer to current code through JDT (design notes §4.4). The entry
     * is the coarse index; JDT gives the exact current location, or flags it stale when the
     * symbol no longer exists. Type-level resolution (strip any {@code #member}); no project
     * loaded → no resolution (the pointer stays a plain FQN).
     */
    Map<String, Object> resolvePointer(String symbolFqn) {
        if (symbolFqn == null || symbolFqn.isBlank()) {
            return null;
        }
        String typeName = symbolFqn.contains("#") ? symbolFqn.substring(0, symbolFqn.indexOf('#')) : symbolFqn;
        IJdtService service = jdt == null ? null : jdt.get();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("symbol", symbolFqn);
        if (service == null) {
            p.put("resolved", false);
            p.put("note", "no project loaded");
            return p;
        }
        try {
            IType type = service.findType(typeName);
            if (type == null || !type.exists()) {
                p.put("resolved", false);
                p.put("stale", true);
                p.put("note", "symbol not found in current workspace");
                return p;
            }
            p.put("resolved", true);
            if (type.getResource() != null && type.getResource().getLocation() != null) {
                p.put("file", type.getResource().getLocation().toOSString());
            }
        } catch (Exception ex) {
            p.put("resolved", false);
            p.put("note", "resolution error: " + ex.getMessage());
        }
        return p;
    }

    private static Map<String, Object> cueMap(RecallQuery q) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (q == null) {
            return m;
        }
        if (q.hasSymbol()) {
            m.put("symbol", q.symbol());
        }
        if (q.hasPackage()) {
            m.put("package", q.packageName());
        }
        if (q.hasOperation()) {
            m.put("operation", q.operation());
        }
        if (q.hasSymptom()) {
            m.put("symptom", q.symptom());
        }
        if (q.hasExternalSystem()) {
            m.put("external_system", q.externalSystem());
        }
        return m;
    }
}
