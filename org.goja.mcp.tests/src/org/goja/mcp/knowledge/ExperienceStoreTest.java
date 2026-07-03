package org.goja.mcp.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 21 Stage 0 — H2 experience store: schema + open/close + entry round-trip. */
class ExperienceStoreTest {

    @Test
    void inMemory_put_get_count_roundtrip() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            assertEquals(0L, store.count(), "empty on open");

            SymbolFact fact = SymbolFact.of("domain_fact",
                    "Billing DTOs keep no-arg constructors", Confidence.MEDIUM)
                .symbol("com.example.billing.InvoiceDto")
                .details("legacy XML/Jackson tests depend on them")
                .build();

            String id = store.put(fact);
            assertNotNull(id);
            assertEquals(1L, store.count());

            Optional<Map<String, Object>> got = store.get(id);
            assertTrue(got.isPresent(), "round-trips the stored entry");
            assertEquals("domain_fact", got.get().get("type"));
            assertEquals("com.example.billing.InvoiceDto", got.get().get("symbol"));
            assertEquals("Billing DTOs keep no-arg constructors", got.get().get("summary"));
            assertEquals(Confidence.MEDIUM.wire(), got.get().get("confidence"));

            assertTrue(store.get("no-such-id").isEmpty(), "absent id → empty");
        }
    }

    @Test
    void fileStore_persists_across_reopen(@TempDir Path dir) {
        String id;
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            id = store.put(SymbolFact.of("lesson", "guard the workbench lifecycle", Confidence.HIGH)
                    .symbol("com.example.WorkflowCoordinator").build());
            assertEquals(1L, store.count());
        }
        // Reopen the same file DB — the entry must survive.
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            assertEquals(1L, store.count(), "entry persisted across reopen");
            assertTrue(store.get(id).isPresent());
        }
    }
}
