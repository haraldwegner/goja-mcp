package org.goja.mcp.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 14a Stage 2: TransportConfig parser tests. Verifies HTTP-is-default,
 * stdio opt-in, port / bind / token / token-file parsing, and graceful
 * pass-through of unknown flags (Eclipse framework args).
 */
class TransportConfigTest {

    @Test
    @DisplayName("httpIsDefault — no flag selects HTTP")
    void httpIsDefault() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[0]);
        assertEquals(TransportConfig.Kind.HTTP, cfg.getKind());
        // Defaults for HTTP-mode parameters:
        assertEquals(0, cfg.getPort(), "default port = 0 (auto-allocate)");
        assertEquals("127.0.0.1", cfg.getBindAddress(), "default bind = 127.0.0.1");
        assertNull(cfg.getToken(), "default token = null (generated at startup)");
        assertNull(cfg.getTokenFile(), "default tokenFile = null (don't write)");
    }

    @Test
    @DisplayName("nullArgs treated as empty array (HTTP default)")
    void nullArgsTreatedAsEmpty() {
        TransportConfig cfg = TransportConfig.fromArgs(null);
        assertEquals(TransportConfig.Kind.HTTP, cfg.getKind());
    }

    @Test
    @DisplayName("stdioOptIn — -transport stdio selects STDIO")
    void stdioOptIn() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[] { "-transport", "stdio" });
        assertEquals(TransportConfig.Kind.STDIO, cfg.getKind());
    }

    @Test
    @DisplayName("-transport http explicit selects HTTP")
    void httpExplicit() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[] { "-transport", "http" });
        assertEquals(TransportConfig.Kind.HTTP, cfg.getKind());
    }

    @Test
    @DisplayName("-transport with unknown value throws IllegalArgumentException")
    void unknownTransportValueRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> TransportConfig.fromArgs(new String[] { "-transport", "websocket" }));
        assertTrue(ex.getMessage().contains("websocket"), "exception should name the bad value");
    }

    @Test
    @DisplayName("-transport missing value throws")
    void transportFlagMissingValue() {
        assertThrows(IllegalArgumentException.class,
            () -> TransportConfig.fromArgs(new String[] { "-transport" }));
    }

    @Test
    @DisplayName("-port parses numeric value")
    void portParsesNumeric() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[] { "-port", "8765" });
        assertEquals(8765, cfg.getPort());
    }

    @Test
    @DisplayName("autoPortAllocatedWhenMissing — absent -port leaves port=0 (auto)")
    void autoPortAllocatedWhenMissing() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[] { "-transport", "http" });
        assertEquals(0, cfg.getPort(), "port=0 signals OS-assigned ephemeral");
    }

    @Test
    @DisplayName("-port with non-numeric value throws")
    void portNonNumericRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> TransportConfig.fromArgs(new String[] { "-port", "abc" }));
    }

    @Test
    @DisplayName("-bind overrides default 127.0.0.1")
    void bindOverridesDefault() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[] { "-bind", "0.0.0.0" });
        assertEquals("0.0.0.0", cfg.getBindAddress());
    }

    @Test
    @DisplayName("-token supplies a static token (no generation)")
    void tokenSuppliedVerbatim() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[] { "-token", "secret-token-xyz" });
        assertEquals("secret-token-xyz", cfg.getToken());
    }

    @Test
    @DisplayName("tokenGeneratedWhenMissing — absent -token leaves token=null (generated at runtime)")
    void tokenGeneratedWhenMissing() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[] { "-transport", "http" });
        assertNull(cfg.getToken(),
            "config-level token absent → application generates at startup");
    }

    @Test
    @DisplayName("-token-file PATH parses as java.nio.file.Path")
    void tokenFileParsesPath() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[] { "-token-file", "/tmp/goja-token" });
        assertEquals(Path.of("/tmp/goja-token"), cfg.getTokenFile());
    }

    @Test
    @DisplayName("Unknown flags pass through silently (Eclipse -data / -clean / etc.)")
    void unknownFlagsIgnored() {
        // Eclipse mixes its own args with ours in the application argv.
        TransportConfig cfg = TransportConfig.fromArgs(new String[] {
            "-data", "/tmp/ws",
            "-clean",
            "-consoleLog",
            "-transport", "stdio",
            "-os", "linux"
        });
        assertEquals(TransportConfig.Kind.STDIO, cfg.getKind());
    }

    @Test
    @DisplayName("All flags combined parse correctly (composition)")
    void allFlagsCombined() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[] {
            "-transport", "http",
            "-port", "9090",
            "-bind", "127.0.0.1",
            "-token", "deadbeef",
            "-token-file", "/tmp/tok"
        });
        assertEquals(TransportConfig.Kind.HTTP, cfg.getKind());
        assertEquals(9090, cfg.getPort());
        assertEquals("127.0.0.1", cfg.getBindAddress());
        assertEquals("deadbeef", cfg.getToken());
        assertEquals(Path.of("/tmp/tok"), cfg.getTokenFile());
    }

    @Test
    @DisplayName("-transport stdio with other HTTP-only flags still selects STDIO (mixed is OK)")
    void stdioWithHttpFlagsStillStdio() {
        // The parser doesn't enforce "HTTP flags only on HTTP kind" — the
        // application owns that semantics; parser is purely syntactic.
        TransportConfig cfg = TransportConfig.fromArgs(new String[] {
            "-transport", "stdio",
            "-port", "9090"
        });
        assertEquals(TransportConfig.Kind.STDIO, cfg.getKind());
        assertEquals(9090, cfg.getPort());
    }

    @Test
    @DisplayName("toString redacts the token value (security)")
    void toStringRedactsToken() {
        TransportConfig cfg = TransportConfig.fromArgs(new String[] { "-token", "super-secret-token" });
        String dump = cfg.toString();
        assertFalse(dump.contains("super-secret-token"),
            "toString must not leak token bytes: " + dump);
        assertTrue(dump.contains("<provided>"), "should show <provided> placeholder: " + dump);
    }
}
