package org.goja.mcp.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * Stdio transport: line-delimited JSON-RPC over an arbitrary
 * {@link InputStream} / {@link OutputStream} pair (default real-world
 * wiring is {@code (System.in, System.out)}; tests inject byte arrays).
 *
 * <p>Sprint 14a Stage 3: {@link #run} replaces the inline loop pattern.
 * {@link #readMessage} and {@link #writeMessage} remain {@code public} for
 * direct unit testing of the I/O semantics (UTF-8, blank-line skip, flush
 * on write).
 */
public class StdioTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(StdioTransport.class);

    private final BufferedReader reader;
    private final PrintWriter writer;
    private volatile boolean closed = false;

    public StdioTransport(InputStream in, OutputStream out) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new PrintWriter(out, true, StandardCharsets.UTF_8);
    }

    /**
     * Drive the stdio loop: read each non-blank line, dispatch via
     * {@code handler}, write the response if non-null. Exits when input
     * EOF is hit, {@link #close} is invoked between messages, or an
     * uncaught {@link IOException} bubbles out of read/write.
     *
     * <p>Per-message dispatch errors are logged and swallowed — the loop
     * survives a single bad message (preserves pre-Stage-3 behaviour).
     */
    @Override
    public void run(MessageHandler handler) throws IOException {
        log.debug("StdioTransport: entering message loop");
        String line;
        while (!closed && (line = readMessage()) != null) {
            log.debug("Received: {}", line);
            try {
                String response = handler.handle(line);
                if (response != null) {
                    writeMessage(response);
                    log.debug("Sent: {}", response);
                }
            } catch (Exception e) {
                log.error("Error processing message", e);
            }
        }
        log.debug("StdioTransport: message loop exited (closed={})", closed);
    }

    /**
     * Block until the next non-blank line is available; return it.
     * Returns {@code null} on EOF. Public for direct unit testing of the
     * read semantics.
     */
    public String readMessage() throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.isBlank()) {
                return line;
            }
        }
        return null;
    }

    /**
     * Write one message as a single newline-terminated line and flush.
     * Public for direct unit testing of the write semantics.
     */
    public void writeMessage(String message) {
        writer.println(message);
        writer.flush();
    }

    @Override
    public void close() {
        closed = true;
        try {
            reader.close();
        } catch (IOException ignored) {
            // Best-effort close; writer still flushes below.
        }
        writer.close();
    }
}
