package org.goja.mcp.transport;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates per-server-startup Bearer tokens via {@link SecureRandom}.
 * 32 bytes of entropy encoded as 64-char lowercase hex (256 bits, no
 * padding ambiguity, URL/header-safe).
 *
 * <p>Sprint 14a Stage 2 — sits behind the {@code -token} CLI flag's absence
 * (when present, the provided token is used verbatim). Stage 3 wires the
 * generated token into the HTTP transport's auth filter + the READY line
 * emitted on stdout.
 *
 * <p>Tokens are sensitive: callers must NOT log them at INFO and MUST emit
 * them on stderr (not stdout) outside of the single READY line — see the
 * {@code tokenNotInStdoutBeyondReadyLine} test in Stage 3.
 */
public final class TokenGenerator {

    private static final SecureRandom RNG = new SecureRandom();
    private static final int BYTES = 32;

    private TokenGenerator() {
        // utility
    }

    /**
     * Returns a fresh 64-character lowercase hex token (32 bytes of entropy).
     */
    public static String generate() {
        byte[] buf = new byte[BYTES];
        RNG.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
