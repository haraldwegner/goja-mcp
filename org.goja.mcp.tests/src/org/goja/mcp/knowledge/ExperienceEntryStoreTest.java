package org.goja.mcp.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21 Stage 1 — {@link ExperienceEntry} persistence: the retrieval facets
 * (status / scope_kind / operation / symptoms / links / fault ownership) survive a
 * put→get round-trip in the {@code body_json} document, and status transitions apply.
 */
class ExperienceEntryStoreTest {

    private static ExperienceEntry sampleEntry() {
        SymbolFact fact = SymbolFact.of("failure_mode",
                "OSGi resolve NPE running Maven tests through run_tests", Confidence.HIGH)
            .scope(List.of("com.example.alpol"), List.of())
            .details("run_tests hits an OSGi NPE on plain-Maven projects; use filtered mvn instead")
            .build();
        return ExperienceEntry.of(fact)
            .scopeKind("package")
            .operation("run_tests")
            .faultOwner("external")
            .externalSystem("OSGi/Tycho test runner")
            .addSymptom("  OSGi   NPE ")            // alias-normalized on the child table
            .addSymptom("NullPointerException resolving bundle")
            .addLink("handled_by", "filtered mvn -Dtest=Class#method")
            .addLink("detected_by", "run_tests")
            .build();
    }

    @Test
    void entry_with_symptoms_and_links_roundtrips() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            String id = store.put(sampleEntry());
            assertNotNull(id);
            assertEquals(1L, store.count());

            Optional<Map<String, Object>> got = store.get(id);
            assertTrue(got.isPresent());
            Map<String, Object> doc = got.get();

            assertEquals("failure_mode", doc.get("type"));
            assertEquals(ExperienceEntry.CANDIDATE, doc.get("status"));
            assertEquals("package", doc.get("scope_kind"));
            assertEquals("run_tests", doc.get("operation"));
            assertEquals("external", doc.get("fault_owner"));
            assertEquals("OSGi/Tycho test runner", doc.get("external_system"));

            assertTrue(doc.get("symptoms") instanceof List<?>, "symptoms present in document");
            assertEquals(2, ((List<?>) doc.get("symptoms")).size());
            assertTrue(doc.get("links") instanceof List<?>, "links present in document");
            assertEquals(2, ((List<?>) doc.get("links")).size());
        }
    }

    @Test
    void setStatus_transitions_and_reports_row_change() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            String id = store.put(sampleEntry());

            assertTrue(store.setStatus(id, ExperienceEntry.ACCEPTED), "existing row changes");
            assertFalse(store.setStatus("no-such-id", ExperienceEntry.ACCEPTED),
                    "missing id → no row changed");
        }
    }

    @Test
    void symptom_normalization_collapses_whitespace_and_case() {
        // The child table stores alias-normalized symptoms so paraphrases index together.
        assertEquals("osgi npe", H2ExperienceStore.normalize("  OSGi   NPE "));
        assertEquals("", H2ExperienceStore.normalize(null));
    }
}
