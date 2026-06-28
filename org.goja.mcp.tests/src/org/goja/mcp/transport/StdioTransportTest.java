package org.goja.mcp.transport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 14a Stage 1: verify the stdio transport extract preserves the
 * original inline loop's semantics — UTF-8, blank-line skipping, flush on
 * write, EOF → null.
 */
class StdioTransportTest {

    @Test
    @DisplayName("readMessage returns the next non-blank line")
    void readMessage_returnsNextNonBlankLine() throws IOException {
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}\n";
        try (StdioTransport transport = new StdioTransport(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream())) {
            String message = transport.readMessage();
            assertNotNull(message);
            assertTrue(message.contains("\"method\":\"initialize\""));
        }
    }

    @Test
    @DisplayName("readMessage skips blank lines until next non-blank")
    void readMessage_skipsBlankLines() throws IOException {
        String input = "\n\n   \n{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}\n";
        try (StdioTransport transport = new StdioTransport(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new ByteArrayOutputStream())) {
            String message = transport.readMessage();
            assertNotNull(message);
            assertTrue(message.contains("\"method\":\"x\""));
        }
    }

    @Test
    @DisplayName("readMessage returns null at end of stream")
    void readMessage_returnsNullAtEof() throws IOException {
        try (StdioTransport transport = new StdioTransport(
                new ByteArrayInputStream(new byte[0]),
                new ByteArrayOutputStream())) {
            assertNull(transport.readMessage());
        }
    }

    @Test
    @DisplayName("writeMessage emits the line and flushes immediately")
    void writeMessage_emitsLineAndFlushes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (StdioTransport transport = new StdioTransport(
                new ByteArrayInputStream(new byte[0]), out)) {
            transport.writeMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}");
            String written = out.toString(StandardCharsets.UTF_8);
            assertTrue(written.startsWith("{\"jsonrpc\":\"2.0\""),
                "expected response line at start: " + written);
            assertTrue(written.endsWith("\n") || written.endsWith("\r\n"),
                "expected newline-terminated line: " + written);
        }
    }

    @Test
    @DisplayName("roundTripsJsonRpcMessages — feed request, write response, EOF")
    void roundTripsJsonRpcMessages() throws IOException {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"ping\"}\n";
        String responseLine = "{\"jsonrpc\":\"2.0\",\"id\":42,\"result\":\"pong\"}";
        ByteArrayInputStream in = new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (StdioTransport transport = new StdioTransport(in, out)) {
            String received = transport.readMessage();
            assertNotNull(received);
            assertTrue(received.contains("\"method\":\"ping\""));

            transport.writeMessage(responseLine);
            String written = out.toString(StandardCharsets.UTF_8);
            assertTrue(written.contains("\"result\":\"pong\""));

            // Stream exhausted after the one request.
            assertNull(transport.readMessage());
        }
    }

    @Test
    @DisplayName("UTF-8 round-trip preserves multi-byte content")
    void utf8RoundTrip() throws IOException {
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":{\"text\":\"naïve café — 中文\"}}\n";
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (StdioTransport transport = new StdioTransport(in, out)) {
            String received = transport.readMessage();
            assertNotNull(received);
            assertTrue(received.contains("naïve café — 中文"),
                "expected UTF-8 content preserved: " + received);
        }
    }

    // ===== Stage 3: run() drives the loop internally =====

    @Test
    @DisplayName("run() dispatches each non-blank input line via the handler")
    void run_dispatchesEachInputLine() throws Exception {
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"a\"}\n"
                     + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"b\"}\n"
                     + "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"c\"}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (StdioTransport transport = new StdioTransport(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out)) {
            transport.run(msg -> "{\"jsonrpc\":\"2.0\",\"result\":\"ok\"}");
        }

        String written = out.toString(StandardCharsets.UTF_8);
        long lines = written.lines().filter(s -> !s.isBlank()).count();
        assertEquals(3, lines,
            "expected 3 response lines for 3 inbound messages, got: " + written);
    }

    @Test
    @DisplayName("run() skips writing when handler returns null (notification semantics)")
    void run_skipsNullHandlerResults() throws Exception {
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"x\"}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (StdioTransport transport = new StdioTransport(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out)) {
            transport.run(msg -> null);
        }

        assertTrue(out.toString(StandardCharsets.UTF_8).isEmpty(),
            "no response should be written for null handler result");
    }

    @Test
    @DisplayName("run() survives handler exceptions and continues to next message")
    void run_survivesHandlerException() throws Exception {
        // First message throws, second succeeds. Both lines should be read;
        // only the second produces output.
        String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"bad\"}\n"
                     + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"good\"}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        try (StdioTransport transport = new StdioTransport(
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), out)) {
            transport.run(msg -> {
                if (msg.contains("\"bad\"")) {
                    throw new RuntimeException("simulated dispatch failure");
                }
                return "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":\"ok\"}";
            });
        }

        String written = out.toString(StandardCharsets.UTF_8);
        assertTrue(written.contains("\"id\":2"),
            "second message should still get a response: " + written);
        long lines = written.lines().filter(s -> !s.isBlank()).count();
        assertEquals(1, lines, "only the surviving handler should produce output: " + written);
    }
}
