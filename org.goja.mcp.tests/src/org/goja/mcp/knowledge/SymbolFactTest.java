package org.goja.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 15a — the shared symbol_fact evidence schema. Pure unit (no project
 * load): asserts deterministic key order, NON_NULL omission, the symbol-vs-scope
 * variants, and lowercase confidence serialization.
 */
class SymbolFactTest {

    @Test
    @DisplayName("symbol-anchored fact: ordered keys, no scope, lowercase confidence")
    void symbolAnchored() {
        Map<String, Object> m = SymbolFact
            .of("api_contract", "Totals preserve line-item rounding", Confidence.HIGH)
            .symbol("com.example.InvoiceService#calculateTotals")
            .source("javadoc", "src/main/java/com/example/InvoiceService.java")
            .build()
            .toMap();

        assertEquals(List.of("type", "symbol", "summary", "source", "confidence"),
            List.copyOf(m.keySet()), "deterministic key order, omitting empties");
        assertEquals("api_contract", m.get("type"));
        assertEquals("com.example.InvoiceService#calculateTotals", m.get("symbol"));
        assertEquals("high", m.get("confidence"));
        assertFalse(m.containsKey("scope"));
        assertFalse(m.containsKey("details"));
        assertFalse(m.containsKey("evidence"));
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) m.get("source");
        assertEquals("javadoc", source.get("kind"));
        assertEquals("src/main/java/com/example/InvoiceService.java", source.get("file"));
    }

    @Test
    @DisplayName("scope-anchored fact: scope present, symbol absent, evidence/exceptions kept")
    void scopeAnchored() {
        Map<String, Object> m = SymbolFact
            .of("naming_convention", "Outbound adapters use the *Client suffix", Confidence.MEDIUM)
            .scope(List.of("com.example.billing"), List.of())
            .details("Classes calling external HTTP APIs are named *Client.")
            .addEvidence("InvoiceClient")
            .addEvidence("PaymentClient")
            .exceptions(List.of("LegacyPaymentService"))
            .build()
            .toMap();

        assertFalse(m.containsKey("symbol"));
        assertEquals("medium", m.get("confidence"));
        assertEquals("Classes calling external HTTP APIs are named *Client.", m.get("details"));
        @SuppressWarnings("unchecked")
        Map<String, Object> scope = (Map<String, Object>) m.get("scope");
        assertEquals(List.of("com.example.billing"), scope.get("packages"));
        assertEquals(List.of(), scope.get("symbols"));
        assertEquals(List.of("InvoiceClient", "PaymentClient"), m.get("evidence"));
        assertEquals(List.of("LegacyPaymentService"), m.get("exceptions"));
    }

    @Test
    @DisplayName("required fields are enforced")
    void requiredFields() {
        assertThrows(IllegalArgumentException.class,
            () -> SymbolFact.of("", "s", Confidence.LOW));
        assertThrows(IllegalArgumentException.class,
            () -> SymbolFact.of("t", " ", Confidence.LOW));
        assertThrows(IllegalArgumentException.class,
            () -> SymbolFact.of("t", "s", null));
    }

    @Test
    @DisplayName("confidence wire form is lowercase")
    void confidenceWire() {
        assertEquals("low", Confidence.LOW.wire());
        assertEquals("medium", Confidence.MEDIUM.wire());
        assertEquals("high", Confidence.HIGH.wire());
    }
}
