package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;

/**
 * Sprint 27 D2 — experience returned as ANALOGY: ranked, capped, and labeled in
 * words.
 *
 * <p>Three rules from the signed ontology are enforced here, and each exists
 * because breaking it produces a specific, known harm:</p>
 *
 * <ol>
 *   <li><b>Equality boosts rank; it never gates.</b> Requiring the cue's symbol
 *       or operation to match would remove every lesson learned somewhere else
 *       — which is most of them, and exactly the transfer the store is for.</li>
 *   <li><b>The basis is stated in words, never as a number.</b> "same
 *       operation" or "meaning-near" tells the agent what kind of resemblance
 *       this is, so it can judge transfer. A bare 0.42 invites treating a score
 *       as authority, and no rendering may contain one.</li>
 *   <li><b>The anchor is provenance, not a criterion.</b> An entry whose code is
 *       gone still applies — it is rendered as "learned on X, since removed",
 *       which is context, not a staleness warning. Marking it stale would tell
 *       the agent to discount knowledge that is still true.</li>
 * </ol>
 */
public final class ExperienceAnalogies {

    // Sprint 27a D1: the old DEFAULT_CAP of two lived here and is gone. How many
    // entries reach the agent is AnalogyPolicy.MAX_NOMINEES now — one authority,
    // derived rather than assumed. The fixed two was the flaw: it hid a correct
    // third answer and padded a thin cue to two.

    private ExperienceAnalogies() {
    }

    /** Ranked experience with the reasons it surfaced, in words. */
    public record Analogy(StoredEntry entry, List<String> basis, String provenance) {
    }

    /**
     * Rank experience candidates and cap them.
     *
     * @param candidates   experience-kind entries from the nominator union
     * @param cue          what the agent asked
     * @param semantic     meaning scores by entry id (may be empty — keyword-only
     *                     nomination is the degrade path, not an error)
     * @param cap          maximum returned
     */
    public static List<Analogy> rank(List<StoredEntry> candidates, RecallQuery cue,
                                     Map<String, Double> semantic, int cap,
                                     Supplier<IJdtService> jdt) {
        return rank(candidates, cue, semantic, Map.of(), List.of(), cap, jdt);
    }

    /**
     * Rank experience candidates, ordering by the MERGED ranking the nominator
     * produced rather than re-deriving an order from meaning alone.
     *
     * <p><b>Why the extra parameters exist.</b> Until the C2b audit, this method
     * scored every candidate as its raw cosine plus three fixed boosts — so a
     * row nominated only by WORDS scored 0.0 and sorted below everything, and
     * with the embedder off every row scored 0.0 and the order collapsed to
     * newest-first. That is the very defect Sprint 27a D9 set out to end, and it
     * survived inside the renderer while the merge above it was correct.</p>
     *
     * @param nominated the merged order from {@code AnalogyPolicy}, best first;
     *                  empty falls back to meaning-then-recency as before
     * @param lexical   word-overlap scores, used only to state a basis in words
     */
    public static List<Analogy> rank(List<StoredEntry> candidates, RecallQuery cue,
                                     Map<String, Double> semantic,
                                     Map<String, Double> lexical,
                                     List<String> nominated, int cap,
                                     Supplier<IJdtService> jdt) {
        record Scored(StoredEntry entry, double score, List<String> basis) {
        }
        List<Scored> scored = new ArrayList<>();
        for (StoredEntry e : candidates) {
            Set<String> basis = new LinkedHashSet<>();
            double meaning = semantic.getOrDefault(e.id(), 0.0);
            double score = meaning;
            // "meaning-near" is a CLAIM, and it must not be made for a row the
            // meaning path did not actually place: a row admitted on words can
            // carry a cosine below the junk floor, and labelling that
            // meaning-near states a basis the evidence does not support.
            if (meaning >= AnalogyPolicy.JUNK_FLOOR) {
                basis.add("meaning-near");
            }
            if (lexical.getOrDefault(e.id(), 0.0) > 0) {
                basis.add("shares distinctive wording");
            }

            // BOOSTS. Deliberately additive and modest: they reorder, they never
            // admit or exclude. The largest boost is still smaller than the gap
            // between a strong and a weak meaning match, so equality sharpens
            // the ranking without overturning it.
            if (cue != null && cue.hasOperation()
                    && eq(e.operation(), cue.operation())) {
                score += 0.15;
                basis.add("same operation");
            }
            if (cue != null && cue.hasSymptom() && symptomOverlap(e, cue.symptom())) {
                score += 0.10;
                basis.add("similar symptom");
            }
            if (cue != null && cue.hasSymbol() && sameArea(e, cue.symbol())) {
                score += 0.05;
                basis.add("same area of the code");
            }
            if (basis.isEmpty()) {
                basis.add("related");        // surfaced by keyword, no stronger claim
            }
            scored.add(new Scored(e, score, List.copyOf(basis)));
        }

        // THE MERGED ORDER LEADS, and equality still MOVES rows within it.
        //
        // A first version of this used the merged position as the sole primary
        // key. Because every nominated id gets a distinct position, nothing was
        // ever tied — so the boosts below could not reorder anything the merge
        // had ranked, and rule 1 of this class ("equality boosts rank; it never
        // gates") became true only of rows the merge did not nominate. The C2b
        // re-audit caught it: a same-operation entry that used to lead an
        // operation cue could lose first place to a merely meaning-near row.
        //
        // So a boost now promotes a row by a few PLACES rather than by a score:
        // local, bounded, and unable to jump a row to the front from far down —
        // the "reorders but never overturns" property the additive form had on
        // the cosine scale, carried into rank space where the merge now lives.
        // Which signals promote, and by how much, is settled by measurement in
        // promotion() rather than by reading the additive weights across.
        Map<String, Integer> mergedRank = new java.util.HashMap<>();
        for (int i = 0; i < nominated.size(); i++) {
            mergedRank.putIfAbsent(nominated.get(i), i);
        }
        scored.sort(Comparator
            .comparingInt((Scored s) -> {
                int at = mergedRank.getOrDefault(s.entry().id(), Integer.MAX_VALUE);
                return at == Integer.MAX_VALUE ? at : at - promotion(s.basis());
            })
            .thenComparing(Comparator.comparingDouble(Scored::score).reversed())
            .thenComparing(s -> s.entry().createdAt() == null
                ? 0L : -s.entry().createdAt().toEpochMilli()));

        List<Analogy> out = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < cap; i++) {
            Scored s = scored.get(i);
            out.add(new Analogy(s.entry(), s.basis(), provenanceOf(s.entry(), jdt)));
        }
        return out;
    }

    /**
     * How many places an equality signal moves a row up the merged ranking.
     *
     * <p>Ordinal images of the additive boosts (0.15 / 0.10 / 0.05), so a row
     * that matches the cue's operation, symptom and area together moves at most
     * six places. Bounded on purpose: equality sharpens the order the evidence
     * produced, it does not replace it.</p>
     */
    private static int promotion(List<String> basis) {
        int places = 0;
        if (basis.contains("same operation")) {
            places += 2;
        }
        if (basis.contains("same area of the code")) {
            places += 1;
        }
        // SYMPTOM OVERLAP IS DELIBERATELY NOT PROMOTED. Measured: promoting it
        // cost a calibration cue (the union fell 11/12 -> 10/12, A/B'd against
        // the identical corpus). The reason is double counting — word overlap
        // between a symptom cue and a row's symptoms is precisely what the two
        // ranking streams already weigh, so promoting it again adds the same
        // evidence twice and drowns the streams' own ordering. Operation and
        // area are different: they are exact, categorical facts neither a
        // cosine nor a BM25 weight can see, so they are what equality has to
        // contribute. The basis line still SAYS "similar symptom" where it
        // applies — it is true, it is simply not counted twice.
        return places;
    }

    /**
     * Where this was learned — context, never a warning.
     *
     * <p>"since removed" is a statement about the CODE, not about the lesson's
     * validity. An experience entry is never stale.</p>
     */
    static String provenanceOf(StoredEntry e, Supplier<IJdtService> jdt) {
        String anchor = e.symbolFqn();
        if (anchor == null || anchor.isBlank()) {
            return null;
        }
        if (!e.isJavaResolvable()) {
            return "learned on " + anchor;
        }
        IJdtService service = jdt == null ? null : jdt.get();
        if (service == null) {
            return "learned on " + anchor;
        }
        try {
            String typeName = anchor.contains("#")
                ? anchor.substring(0, anchor.indexOf('#')) : anchor;
            var type = service.findType(typeName);
            boolean gone = type == null || !type.exists();
            return "learned on " + anchor + (gone ? ", since removed" : "");
        } catch (Exception ex) {
            return "learned on " + anchor;     // a failed lookup is not an absence
        }
    }

    /**
     * Structured form for the MCP answer. Text rendering lives in
     * {@link ExperienceRetrieval#renderAnalogyLine} and reads THIS map — one
     * rendering path, so the ontology's rules (basis in words, provenance, no
     * similarity number) cannot hold on one path and quietly lapse on another.
     */
    public static List<Map<String, Object>> toMaps(List<Analogy> analogies) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Analogy a : analogies) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.entry().id());
            m.put("type", a.entry().type());
            m.put("summary", a.entry().summary());
            m.put("basis", a.basis());                 // words, never a score
            if (a.provenance() != null) {
                m.put("provenance", a.provenance());
            }
            // Sprint 27 D4 — dispatch recall rides the analogy carrier: seat,
            // human verdict and outcome, still framed as an analogy because a
            // past run is evidence, not the rule.
            Map<String, Object> dispatch = DispatchRecall.toMap(DispatchRecall.of(a.entry()));
            if (dispatch != null) {
                m.put("dispatch", dispatch);
            }
            m.put("framing", "analogy — judge whether it transfers");
            out.add(m);
        }
        return out;
    }

    private static boolean eq(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static boolean symptomOverlap(StoredEntry e, String symptom) {
        String norm = H2ExperienceStore.normalize(symptom);
        if (norm.isEmpty()) {
            return false;
        }
        String hay = String.join(" ", e.symptoms()) + " "
            + H2ExperienceStore.normalize(e.summary() == null ? "" : e.summary());
        for (String token : norm.split("\\s+")) {
            if (token.length() > 3 && hay.contains(token)) {
                return true;                  // ANY shared salient token, not all
            }
        }
        return false;
    }

    private static boolean sameArea(StoredEntry e, String symbol) {
        String s = e.symbolFqn();
        if (s != null && !s.isBlank()
                && (symbol.startsWith(s) || s.startsWith(symbol))) {
            return true;
        }
        String pkg = e.packageName();
        return pkg != null && !pkg.isBlank() && symbol.startsWith(pkg + ".");
    }

}
