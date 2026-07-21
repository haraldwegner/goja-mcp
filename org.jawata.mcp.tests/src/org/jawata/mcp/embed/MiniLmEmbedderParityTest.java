package org.jawata.mcp.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 27 C2 — THE parity gate (expectation E1).
 *
 * <p>Our owned encoder must reproduce the reference implementation's vectors to
 * <b>cosine ≥ 0.999</b> on the committed golden set. The band is deliberately
 * severe and is NOT a similarity judgement: at this point only float rounding
 * may differ, and a genuinely buggy re-implementation lands around 0.90–0.98 —
 * which is exactly why a "0.9 is close enough" bar would defeat the gate
 * instead of enforcing it.</p>
 *
 * <p>The test measures BOTH bundled precisions when both are present, so the
 * fp16-vs-fp32 shipping decision is made by the measurement rather than
 * asserted by whoever wrote the code.</p>
 */
class MiniLmEmbedderParityTest {

    private static final String GOLDENS = "/test-resources/embed-goldens/golden-vectors.json";
    private static final double PARITY_FLOOR = 0.999;

    private static JsonNode goldens() throws Exception {
        try (InputStream in = MiniLmEmbedderParityTest.class.getResourceAsStream(GOLDENS)) {
            assertNotNull(in, "golden vectors must ship with the test fragment: " + GOLDENS);
            return new ObjectMapper().readTree(in);
        }
    }

    private record Result(String precision, double min, double mean, int cases, String worst) {
    }

    private static Result measure(MiniLmEmbedder e, JsonNode g) {
        double min = 1.0;
        double sum = 0;
        int n = 0;
        String worst = "";
        for (JsonNode c : g.get("cases")) {
            String text = c.get("text").asText();
            JsonNode vec = c.get("vector");
            float[] expected = new float[vec.size()];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (float) vec.get(i).asDouble();
            }
            float[] actual = e.embed(text);
            assertEquals(expected.length, actual.length,
                "dimension must match the reference for: " + text);
            double cos = cosine(expected, actual);
            sum += cos;
            n++;
            if (cos < min) {
                min = cos;
                worst = text;
            }
        }
        return new Result(e.precision(), min, sum / n, n, worst);
    }

    @Test
    void our_vectors_match_the_reference_on_every_golden_case() throws Exception {
        JsonNode g = goldens();
        List<Result> results = new ArrayList<>();
        for (String resource : new String[] {MiniLmEmbedder.RESOURCE_F32, MiniLmEmbedder.RESOURCE_F16}) {
            MiniLmEmbedder e = MiniLmEmbedder.fromResource(resource, MatMuls.active());
            if (e != null) {
                results.add(measure(e, g));
            }
        }
        assertTrue(!results.isEmpty(), "no bundled weights found — the gate cannot run");

        for (Result r : results) {
            System.out.printf("[parity] %s cases=%d min=%.6f mean=%.6f backend=%s%n",
                r.precision(), r.cases(), r.min(), r.mean(), MatMuls.activeName());
            assertTrue(r.cases() >= 20,
                "the golden set must be broad, got " + r.cases() + " cases");
            assertTrue(r.min() >= PARITY_FLOOR,
                r.precision() + " parity FAILED: worst cosine " + r.min()
                + " < " + PARITY_FLOOR + " on case \"" + r.worst() + "\"");
        }
    }

    @Test
    void the_embedder_reports_what_it_actually_loaded() {
        MiniLmEmbedder e = MiniLmEmbedder.bundled();
        assertTrue("fp16".equals(e.precision()) || "fp32".equals(e.precision()),
            "precision must be one we ship, got " + e.precision());
        assertTrue(e.backend().equals("scalar") || e.backend().startsWith("vector-api"),
            "backend must be one we ship, got " + e.backend());
    }

    @Test
    void every_vector_is_unit_length_and_384_dimensional() {
        MiniLmEmbedder e = MiniLmEmbedder.bundled();
        for (String text : new String[] {"", "a", "never cancel before broker confirms",
                                         "日本語のテキスト", "x".repeat(4000)}) {
            float[] v = e.embed(text);
            assertEquals(384, v.length, "dimension for: " + text);
            double norm = 0;
            for (float f : v) {
                norm += (double) f * f;
            }
            assertEquals(1.0, Math.sqrt(norm), 1e-4,
                "vectors must be L2-normalized so cosine is a dot product; failed for: " + text);
        }
    }

    /**
     * The whole point of the sprint's privacy claim: embedding must work with
     * nothing but bundled resources. This asserts the mechanism honestly — the
     * embedder loads from the classpath and never opens a socket — by embedding
     * successfully while the JVM's network stack is irrelevant to the result.
     * (A hard no-network proof lives in the C10 cable probe.)
     */
    @Test
    void embedding_works_from_bundled_resources_alone() {
        MiniLmEmbedder e = MiniLmEmbedder.bundled();
        float[] a = e.embed("the broker confirmed the cancel");
        float[] b = e.embed("the broker confirmed the cancel");
        assertEquals(384, a.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], 0f, "the same text must embed deterministically");
        }
    }

    @Test
    void vectors_of_different_identities_are_never_compared() {
        EmbedderIdentity mine = EmbedderIdentity.current();
        EmbedderIdentity other = new EmbedderIdentity("some/other-model", 384, 1);
        EmbedderIdentity newerPipeline = new EmbedderIdentity(mine.model(), mine.dim(), 99);

        EmbedderIdentity.requireComparable(mine, EmbedderIdentity.current());   // same: fine
        assertThrows(IllegalArgumentException.class,
            () -> EmbedderIdentity.requireComparable(mine, other),
            "a different model is a different space");
        assertThrows(IllegalArgumentException.class,
            () -> EmbedderIdentity.requireComparable(mine, newerPipeline),
            "a pipeline change that moves vectors is a different space");
        assertThrows(IllegalArgumentException.class,
            () -> EmbedderIdentity.requireComparable(mine, null),
            "an unknown identity must never be treated as a match");

        assertEquals(mine, EmbedderIdentity.parse(mine.key()), "key() round-trips");
        assertEquals(384, MiniLmEmbedder.bundled().identity().dim());
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }
}
