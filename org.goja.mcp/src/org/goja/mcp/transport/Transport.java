package org.goja.mcp.transport;

/**
 * Transport seam between the MCP server's main message loop and the
 * underlying I/O channel.
 *
 * <p>Sprint 14a Stage 1 introduced the seam with a read/write pair for
 * stdio; Stage 3 generalizes to a {@link #run} model so the HTTP transport
 * (which is per-request via servlet thread, not a continuous stream) fits
 * cleanly. Stdio implements {@code run} as a loop over its internal
 * read/write; HTTP runs the listener until {@link #close} is signalled.
 */
public interface Transport extends AutoCloseable {

    /**
     * Drive the transport's I/O until the channel is exhausted or
     * {@link #close} is called. For each inbound JSON-RPC message,
     * dispatch via {@code handler} and emit the returned response (if
     * non-{@code null}).
     */
    void run(MessageHandler handler) throws Exception;

    /**
     * Stop the transport. Idempotent. May be called from another thread to
     * unblock a currently-running {@link #run}.
     */
    @Override
    void close();

    /**
     * Handler interface bound by the application to its
     * {@code McpProtocolHandler::processMessage}. Returns {@code null} for
     * JSON-RPC notifications (no response expected).
     */
    @FunctionalInterface
    interface MessageHandler {
        String handle(String message);
    }
}
