package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jawata.mcp.embed.MatMul;
import org.jawata.mcp.embed.MatMuls;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 27 D1 (C8) — the backend is REPORTED, not inferred.
 *
 * <p>The launch flag only makes the Vector API backend possible; whether it
 * loaded is a fact of the running JVM. The two backends are indistinguishable
 * in their results and roughly 3× apart in speed, so "which one is running"
 * is invisible exactly where it matters — which is why {@code health_check}
 * states it, and states the measured one rather than the configured one.</p>
 */
class EmbedderBackendHonestyTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @SuppressWarnings("unchecked")
    private Map<String, Object> health() {
        HealthCheckTool tool = new HealthCheckTool(() -> false, () -> 45,
            () -> org.jawata.mcp.ProjectLoadingState.NOT_LOADED, () -> null, () -> null);
        var r = tool.execute(OM.createObjectNode());
        assertTrue(r.isSuccess());
        return (Map<String, Object>) r.getData();
    }

    @Test
    void health_check_names_the_backend_that_actually_answered() {
        @SuppressWarnings("unchecked")
        Map<String, Object> embedder = (Map<String, Object>) health().get("embedder");
        assertNotNull(embedder, "health_check must say which backend is running — "
            + "the difference is a silent 3x and invisible in every result");

        Object backend = embedder.get("backend");
        assertNotNull(backend);
        assertEquals(MatMuls.activeName(), backend,
            "the reported name is the implementation that answered a probe "
            + "multiplication at startup, not a reading of the command line");
        assertEquals(!"scalar".equalsIgnoreCase(MatMuls.activeName()),
            embedder.get("vectorApi"),
            "and the boolean agrees with the name — two fields that can disagree "
            + "are worse than one");
    }

    @Test
    void an_unavailable_embedder_reports_a_reason_not_a_bare_false() {
        @SuppressWarnings("unchecked")
        Map<String, Object> embedder = (Map<String, Object>) health().get("embedder");
        assertNotNull(embedder.get("available"));
        if (Boolean.FALSE.equals(embedder.get("available"))) {
            assertNotNull(embedder.get("note"),
                "semantic recall being OFF is something somebody has to be told, "
                + "with the reason — a bare false is a shrug");
        } else {
            assertNotNull(embedder.get("identity"),
                "an available embedder names WHICH model answered, because vectors "
                + "from two identities are not comparable");
        }
    }

    /**
     * The flagless path, asserted rather than assumed: the scalar backend is
     * always constructible and always right. A run without
     * {@code --add-modules} takes exactly this path, so a suite that only ever
     * ran with the flag would never have executed the shipped fallback.
     */
    @Test
    void the_scalar_backend_is_always_available_and_agrees_with_the_active_one() {
        MatMul scalar = MatMuls.scalar();
        assertNotNull(scalar, "the fallback is not optional — it is the floor");
        assertEquals("scalar", scalar.name());

        // A small multiply both must agree on, to the bit patterns float allows.
        float[] a = {1f, 2f, 3f, 4f, 5f, 6f};          // 2x3
        float[] b = {7f, 8f, 9f, 10f, 11f, 12f};        // 3x2
        float[] viaScalar = new float[4];
        float[] viaActive = new float[4];
        scalar.multiply(a, b, viaScalar, 2, 3, 2);
        MatMuls.active().multiply(a, b, viaActive, 2, 3, 2);
        for (int i = 0; i < viaScalar.length; i++) {
            assertEquals(viaScalar[i], viaActive[i], 1e-5f,
                "the backends must be indistinguishable in RESULT — that is what "
                + "makes the flag a speed decision instead of a correctness one");
        }
    }
}
