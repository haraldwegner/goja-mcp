import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.knowledge.EmbeddingService;

import java.io.PrintWriter;
import java.sql.*;
import java.util.*;

/**
 * Sprint 27a Stage 0 — derive the analogy policy's abstention rule and margin
 * from measured score profiles. Runs against a COPY of the store.
 *
 * Phase 1: finish the embedding backfill on the copy (the live store is only
 *          ~50% embedded, which alone breaks 5 of the 12 frozen cues).
 * Phase 2: load every current-identity vector.
 * Phase 3: for each cue, the FULL score profile over the corpus.
 * Phase 4: evaluate candidate abstention rules against the frozen contract.
 */
public class DerivePolicy {

    static final String URL = "jdbc:h2:file:/tmp/jawata-corpus/experience";
    static final ObjectMapper M = new ObjectMapper();

    record Cue(String id, String text, String designated, List<String> acceptSet, boolean nonsense) {}

    public static void main(String[] args) throws Exception {
        EmbeddingService svc = EmbeddingService.shared();
        if (!svc.available()) {
            System.out.println("FATAL embedder unavailable: " + svc.unavailableReason());
            return;
        }
        System.out.println("embedder identity = " + svc.identityKey());

        try (Connection c = DriverManager.getConnection(URL, "", "")) {
            backfillAll(c, svc);
            Map<String, float[]> vectors = loadVectors(c, svc.identityKey());
            Map<String, String> summaries = loadSummaries(c);
            System.out.println("vectors loaded = " + vectors.size());

            List<Cue> cues = cues();
            PrintWriter out = new PrintWriter(
                "/tmp/claude-1000/-home-harald-CursorProjects-jawata-studio/"
                + "4526476c-40e7-4ce7-b547-322dc393449c/scratchpad/profiles.tsv");
            out.println("cue_id\tkind\ttop1_id\ttop1\ttop2\ttop3\tmedian\tp90\tp99\tmean\tsd"
                      + "\tdesignated_score\tdesignated_rank\ttop1_in_accept\tcue_text");

            for (Cue q : cues) {
                float[] v = svc.embed(q.text());
                double[] scores = new double[vectors.size()];
                String[] ids = new String[vectors.size()];
                int i = 0;
                for (Map.Entry<String, float[]> e : vectors.entrySet()) {
                    ids[i] = e.getKey();
                    scores[i] = EmbeddingService.cosine(v, e.getValue());
                    i++;
                }
                Integer[] order = new Integer[scores.length];
                for (int k = 0; k < order.length; k++) order[k] = k;
                Arrays.sort(order, (a, b) -> Double.compare(scores[b], scores[a]));

                double[] sorted = scores.clone();
                Arrays.sort(sorted);
                double median = pct(sorted, 0.50), p90 = pct(sorted, 0.90), p99 = pct(sorted, 0.99);
                double mean = Arrays.stream(scores).average().orElse(0);
                double sd = Math.sqrt(Arrays.stream(scores).map(s -> (s - mean) * (s - mean))
                        .average().orElse(0));

                double t1 = scores[order[0]], t2 = scores[order[1]], t3 = scores[order[2]];
                String t1id = ids[order[0]];

                double desScore = -1; int desRank = -1;
                if (q.designated() != null) {
                    for (int r = 0; r < order.length; r++) {
                        if (ids[order[r]].startsWith(q.designated())) {
                            desScore = scores[order[r]]; desRank = r + 1; break;
                        }
                    }
                }
                boolean inAccept = q.acceptSet() != null
                    && q.acceptSet().stream().anyMatch(t1id::startsWith);

                out.printf("%s\t%s\t%s\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%d\t%s\t%s%n",
                    q.id(), q.nonsense() ? "NONSENSE" : "real", t1id.substring(0, 8),
                    t1, t2, t3, median, p90, p99, mean, sd, desScore, desRank, inAccept,
                    q.text().replace('\t', ' '));

                System.out.printf("%-12s %-9s top1=%.4f t2=%.4f med=%.4f p99=%.4f sd=%.4f"
                        + "  z=%.2f  gap=%.4f  des#%d(%.4f) accept=%s  [%s]%n",
                    q.id(), q.nonsense() ? "NONSENSE" : "real", t1, t2, median, p99, sd,
                    (t1 - mean) / sd, t1 - t2, desRank, desScore, inAccept,
                    trunc(summaries.getOrDefault(t1id, "?"), 46));
            }
            out.close();
        }
    }

    /** Embed every row lacking a CURRENT-identity vector, in full — no batch cap. */
    static void backfillAll(Connection c, EmbeddingService svc) throws Exception {
        List<String[]> pending = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, summary, body_json FROM experience_entry"
              + " WHERE embedding IS NULL OR embedder_identity IS NULL OR embedder_identity <> ?")) {
            ps.setString(1, svc.identityKey());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    pending.add(new String[] {rs.getString(1), rs.getString(2), rs.getString(3)});
                }
            }
        }
        System.out.println("backfill pending = " + pending.size());
        int done = 0, blank = 0;
        long t0 = System.currentTimeMillis();
        try (PreparedStatement up = c.prepareStatement(
                "UPDATE experience_entry SET embedding = ?, embedder_identity = ? WHERE id = ?")) {
            for (String[] p : pending) {
                String text = EmbeddingService.textOf(p[1], detailsOf(p[2]));
                float[] v = svc.embed(text);
                if (v == null) { blank++; continue; }
                up.setBytes(1, EmbeddingService.toBytes(v));
                up.setString(2, svc.identityKey());
                up.setString(3, p[0]);
                up.executeUpdate();
                if (++done % 200 == 0) {
                    System.out.println("  embedded " + done + "/" + pending.size());
                }
            }
        }
        System.out.printf("backfill done=%d blank=%d in %.1fs%n",
            done, blank, (System.currentTimeMillis() - t0) / 1000.0);
    }

    static String detailsOf(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) return null;
        try {
            JsonNode d = M.readTree(bodyJson).get("details");
            return d == null || d.isNull() ? null : d.asText();
        } catch (Exception e) { return null; }
    }

    static Map<String, float[]> loadVectors(Connection c, String identity) throws Exception {
        Map<String, float[]> m = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, embedding FROM experience_entry WHERE embedding IS NOT NULL"
              + " AND embedder_identity = ? AND status NOT IN ('rejected','superseded')")) {
            ps.setString(1, identity);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    float[] v = EmbeddingService.fromBytes(rs.getBytes(2));
                    if (v != null) m.put(rs.getString(1), v);
                }
            }
        }
        return m;
    }

    static Map<String, String> loadSummaries(Connection c) throws Exception {
        Map<String, String> m = new HashMap<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, summary FROM experience_entry")) {
            while (rs.next()) m.put(rs.getString(1), rs.getString(2));
        }
        return m;
    }

    static double pct(double[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(p * (sorted.length - 1)))];
    }

    static String trunc(String s, int n) {
        if (s == null) return "?";
        s = s.replaceAll("\\s+", " ");
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    /** The 12 frozen calibration cues + the 5 committed controls + new nonsense controls. */
    static List<Cue> cues() {
        List<Cue> l = new ArrayList<>();
        l.add(new Cue("cue-01", "the position monitor alarms about a mismatch whenever we bet against a stock", "d20ce2c6", List.of("d20ce2c6"), false));
        l.add(new Cue("cue-02", "removing the record from the lookup table too early meant a late confirmation reached the wrong owner", "8932f5b6", List.of("8932f5b6","9b2dc078","573d4536"), false));
        l.add(new Cue("cue-03", "a lost websocket acknowledgement leaves an order that can never be called off", "5d48ce19", List.of("5d48ce19","5082dd19","d15903f1"), false));
        l.add(new Cue("cue-04", "never cancel before broker confirms", "5e07e60f", List.of("5e07e60f","b217d3c3","9fca1b32","d3fd7a9c","d0fe2a4c","79d59a3a","8932f5b6"), false));
        l.add(new Cue("cue-05", "browser window comes up empty", "7646de22", List.of("7646de22"), false));
        l.add(new Cue("cue-06", "the app starts but nothing paints inside the frame", "7646de22", List.of("7646de22"), false));
        l.add(new Cue("cue-07", "how close should two vectors be before we call them the same thing", "fef49c17", List.of("fef49c17"), false));
        l.add(new Cue("cue-08", "opening a new trade killed the protective stop on the other side and left shares unguarded", "1999c6a8", List.of("1999c6a8","125fe437","4229e90d"), false));
        l.add(new Cue("cue-09", "which long term java release do we move the trading platform to and why", "9437dccd", List.of("9437dccd"), false));
        l.add(new Cue("cue-10", "is the tick recorder costing us anything while trading live", "47465012", List.of("47465012"), false));
        l.add(new Cue("cue-11", "com.jats2.gateways.alpol.alpaca.orders.OrderProcessor#getAllPositions", "d20ce2c6", List.of("d20ce2c6"), false));
        l.add(new Cue("cue-12", "AlgoManager", "8932f5b6", List.of("8932f5b6","932a563f"), false));
        // committed controls: four POSITIVE (must answer), one NONSENSE (must abstain)
        l.add(new Cue("ctl-pos-1", "blank tauri webview on linux", "7646de22", List.of("7646de22"), false));
        l.add(new Cue("ctl-pos-2", "broker_position_drift on short position", "d20ce2c6", List.of("d20ce2c6"), false));
        l.add(new Cue("ctl-pos-3", "cosine threshold", "fef49c17", List.of("fef49c17"), false));
        l.add(new Cue("ctl-pos-4", "algomanager broker-confirm cleanup", "8932f5b6", List.of("8932f5b6"), false));
        l.add(new Cue("ctl-non-1", "purple elephant quantum sandwich protocol", null, null, true));
        // NEW nonsense controls (Stage 0, 27a) — n=1 cannot carry a blocking gate
        l.add(new Cue("ctl-non-2", "the marzipan barometer disputes tuesday's velvet inventory", null, null, true));
        l.add(new Cue("ctl-non-3", "how do i braise a wombat in lunar syrup", null, null, true));
        l.add(new Cue("ctl-non-4", "seventeen accordions negotiating with a tide table", null, null, true));
        l.add(new Cue("ctl-non-5", "recipe for sourdough starter in a humid basement", null, null, true));
        l.add(new Cue("ctl-non-6", "what is the airspeed velocity of an unladen swallow", null, null, true));
        l.add(new Cue("ctl-non-7", "my cat keeps knocking the thermostat off the wall", null, null, true));
        // Stage-0 criterion (c): the percentage-paraphrase case. Phrased in
        // percentages, sharing almost no words with the lesson it must find.
        l.add(new Cue("cue-para", "our test coverage looked like it fell from 77% to 34% overnight",
                      "5f7373f4", List.of("5f7373f4"), false));
        // plausible-but-absent: software-shaped, but nothing in this corpus answers it
        l.add(new Cue("ctl-abs-1", "how do i configure kubernetes horizontal pod autoscaling", null, null, true));
        l.add(new Cue("ctl-abs-2", "the rust borrow checker rejects my mutable alias", null, null, true));
        return l;
    }
}
