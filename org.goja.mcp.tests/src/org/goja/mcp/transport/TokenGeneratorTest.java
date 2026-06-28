package org.goja.mcp.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 14a Stage 2: TokenGenerator sanity tests. 32 bytes of entropy → 64
 * hex chars, lowercase, no duplicates across a sample of N tokens.
 */
class TokenGeneratorTest {

    private static final Pattern HEX_64 = Pattern.compile("^[0-9a-f]{64}$");

    @Test
    @DisplayName("generate produces 64 lowercase hex chars (32 bytes)")
    void generateProduces64HexChars() {
        String token = TokenGenerator.generate();
        assertEquals(64, token.length(), "token length: " + token);
        assertTrue(HEX_64.matcher(token).matches(),
            "token must be 64 chars of lowercase hex, got: " + token);
    }

    @Test
    @DisplayName("generate produces distinct tokens across N calls (no obvious determinism)")
    void generateProducesDistinctTokens() {
        Set<String> tokens = new HashSet<>();
        int n = 1000;
        for (int i = 0; i < n; i++) {
            tokens.add(TokenGenerator.generate());
        }
        assertEquals(n, tokens.size(),
            "expected " + n + " distinct tokens, got " + tokens.size());
    }
}
