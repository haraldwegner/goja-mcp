package org.goja.mcp.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/**
 * HTTP transport using the JDK's built-in
 * {@link com.sun.net.httpserver.HttpServer}.
 *
 * <p>Sprint 14a Stage 3 chose the JDK server over an embedded Jetty bundle
 * because the Eclipse target platform did not already include Jetty
 * (pre-flight grep returned empty). Adding Jetty would require a target
 * platform bump plus the Plan-agent-flagged risk of OSGi class-loader vs
 * servlet-thread friction with JDT APIs. The JDK server avoids all of
 * that: no target changes, no new bundle dependencies, no OSGi
 * class-loader concerns, works guaranteed with Java 21.
 *
 * <p><b>Endpoints:</b>
 * <ul>
 *   <li>{@code POST /mcp} — request-response. Body is a JSON-RPC message;
 *       returns the JSON-RPC response (200 + application/json) or 204
 *       (notification). 400 on malformed JSON, 401 on missing/wrong
 *       Bearer token, 405 on non-POST.</li>
 *   <li>{@code GET /mcp/events} — Server-Sent Events (SSE) channel.
 *       Long-lived response of {@code event: <type>\ndata: <json>\n\n}
 *       frames. Sends an initial {@code ready} event on subscription,
 *       then {@code heartbeat} every {@code heartbeatInterval} (default
 *       30 s) until the client disconnects or the transport is closed.
 *       401 on missing/wrong token, 405 on non-GET.</li>
 * </ul>
 *
 * <p><b>READY contract:</b> after a successful bind, exactly one line is
 * written to stdout: {@code READY url=http://<bind>:<port> token=<token>}.
 * The token MUST NOT appear elsewhere on stdout — the manager-side
 * launcher captures stdout into a log file, and a leaked token in logs is
 * a security regression. See {@code tokenNotInStdoutBeyondReadyLine} test.
 *
 * <p><b>Bearer auth:</b> every request must carry
 * {@code Authorization: Bearer <token>}; missing or wrong → 401 with
 * empty body. Localhost binding is the secondary gate.
 *
 * <p><b>Tool-output event integration (Sprint 14a Stage 4 caveat):</b>
 * the SSE channel infrastructure ships in v1.8.5 (heartbeats + the
 * {@link #sendEvent} public API), but the dispatching tools do not yet
 * emit progress events. Wiring up the tool layer to call
 * {@link #sendEvent} for progress / partial-results is a v1.8.6 follow-up
 * (separate scope from the bug #9 leak fix).
 */
public class HttpTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(HttpTransport.class);

    private static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final int requestedPort;
    private final String bindAddress;
    private final String token;
    private final Duration heartbeatInterval;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<SseSubscriber> subscribers = new CopyOnWriteArrayList<>();

    private HttpServer server;
    private final CountDownLatch readyLatch = new CountDownLatch(1);
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private volatile int actualPort = -1;

    public HttpTransport(int port, String bindAddress, String token) {
        this(port, bindAddress, token, DEFAULT_HEARTBEAT_INTERVAL);
    }

    /**
     * Package-private constructor for tests that need a short heartbeat
     * interval to exercise the keep-alive path quickly. Production callers
     * use the three-arg constructor with the 30s default.
     */
    HttpTransport(int port, String bindAddress, String token, Duration heartbeatInterval) {
        if (bindAddress == null || bindAddress.isBlank()) {
            throw new IllegalArgumentException("bindAddress required");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("token required (use TokenGenerator.generate() if not provided)");
        }
        if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("heartbeatInterval must be positive");
        }
        this.requestedPort = port;
        this.bindAddress = bindAddress;
        this.token = token;
        this.heartbeatInterval = heartbeatInterval;
    }

    /**
     * Start the HTTP listener, emit READY on stdout, then block until
     * {@link #close} is invoked from another thread.
     *
     * <p>If {@code requestedPort} was 0 (auto-allocate), the OS-assigned
     * port is captured and exposed via the READY line + {@link #getActualPort}.
     */
    @Override
    public void run(MessageHandler handler) throws IOException, InterruptedException {
        if (handler == null) {
            throw new IllegalArgumentException("handler required");
        }
        server = HttpServer.create(new InetSocketAddress(bindAddress, requestedPort), 0);
        server.createContext("/mcp", new McpHandler(handler, token, objectMapper));
        server.createContext("/mcp/events", new EventsHandler(token, heartbeatInterval, subscribers));
        // Cached pool — POST handlers return quickly; SSE handlers hold a
        // thread for the connection lifetime. Cached scales as needed
        // without starving POSTs (an issue with fixed pools when several
        // SSE clients are connected).
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "HttpTransport-worker");
            t.setDaemon(true);
            return t;
        }));
        server.start();

        actualPort = server.getAddress().getPort();
        // READY contract: single line on stdout, token appears here ONLY.
        System.out.println("READY url=http://" + bindAddress + ":" + actualPort + " token=" + token);
        readyLatch.countDown();

        log.info("HTTP transport listening on {}:{}", bindAddress, actualPort);

        shutdownLatch.await();
        log.info("HTTP transport shutdown signal received");
    }

    /**
     * Returns the OS-assigned port once {@link #run} has bound the
     * listener. Used by tests and by the manager's READY-line capture.
     * Blocks until the server is ready (throws if interrupted).
     */
    public int getActualPort() throws InterruptedException {
        readyLatch.await();
        return actualPort;
    }

    /**
     * Broadcast an SSE event to every currently-subscribed
     * {@code /mcp/events} client. Public API ready for tool-layer
     * progress integration (v1.8.6 wiring; see class-level Javadoc).
     *
     * @param type event name (becomes {@code event: <type>} in the frame)
     * @param dataJson event body as already-serialized JSON
     *                 (becomes {@code data: <dataJson>})
     */
    public void sendEvent(String type, String dataJson) {
        for (SseSubscriber sub : subscribers) {
            sub.send(type, dataJson);
        }
    }

    @Override
    public void close() {
        // Close subscribers first so SSE threads exit their wait loops
        // before server.stop() pulls their socket out from under them.
        for (SseSubscriber sub : subscribers) {
            sub.close();
        }
        subscribers.clear();

        if (server != null) {
            try {
                server.stop(0);
            } catch (Exception e) {
                log.warn("Error stopping HTTP server: {}", e.getMessage());
            }
            server = null;
        }
        shutdownLatch.countDown();
    }

    /**
     * Translates HTTP requests on {@code /mcp} into JSON-RPC dispatch via
     * the application-supplied {@link MessageHandler}. Enforces Bearer
     * auth + basic JSON syntactic validation up-front.
     */
    private static final class McpHandler implements HttpHandler {

        private final MessageHandler handler;
        private final String expectedAuthHeader;
        private final ObjectMapper objectMapper;

        McpHandler(MessageHandler handler, String token, ObjectMapper objectMapper) {
            this.handler = handler;
            this.expectedAuthHeader = "Bearer " + token;
            this.objectMapper = objectMapper;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                if (!"POST".equals(exchange.getRequestMethod())) {
                    sendEmpty(exchange, 405);
                    return;
                }

                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth == null || !auth.equals(expectedAuthHeader)) {
                    sendEmpty(exchange, 401);
                    return;
                }

                byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
                if (bodyBytes.length == 0) {
                    sendEmpty(exchange, 400);
                    return;
                }
                String body = new String(bodyBytes, StandardCharsets.UTF_8);
                if (body.isBlank()) {
                    sendEmpty(exchange, 400);
                    return;
                }

                // Syntactic JSON check up-front so the dispatcher only sees
                // well-formed messages. McpProtocolHandler does its own
                // semantic JSON-RPC validation downstream.
                try {
                    objectMapper.readTree(body);
                } catch (JsonProcessingException e) {
                    sendEmpty(exchange, 400);
                    return;
                }

                String response = handler.handle(body);
                if (response != null) {
                    byte[] respBytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, respBytes.length);
                    exchange.getResponseBody().write(respBytes);
                } else {
                    // Notification — no response body per JSON-RPC.
                    exchange.sendResponseHeaders(204, -1);
                }
            } catch (Exception e) {
                log.error("HTTP dispatch error", e);
                try {
                    sendEmpty(exchange, 500);
                } catch (IOException ignored) {
                    // already failed; drop
                }
            } finally {
                exchange.close();
            }
        }
    }

    /**
     * GET {@code /mcp/events}: opens a long-lived SSE stream, sends an
     * initial {@code ready} event, then {@code heartbeat} every
     * {@code heartbeatInterval} until the client disconnects or the
     * transport is {@link #close closed}. Registered subscribers also
     * receive any event broadcast via {@link #sendEvent}.
     */
    private static final class EventsHandler implements HttpHandler {

        private final String expectedAuthHeader;
        private final Duration heartbeatInterval;
        private final List<SseSubscriber> subscribers;

        EventsHandler(String token, Duration heartbeatInterval, List<SseSubscriber> subscribers) {
            this.expectedAuthHeader = "Bearer " + token;
            this.heartbeatInterval = heartbeatInterval;
            this.subscribers = subscribers;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equals(exchange.getRequestMethod())) {
                sendEmpty(exchange, 405);
                exchange.close();
                return;
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals(expectedAuthHeader)) {
                sendEmpty(exchange, 401);
                exchange.close();
                return;
            }

            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.getResponseHeaders().add("Cache-Control", "no-cache");
            // 0 = chunked transfer encoding (response length not known in advance).
            exchange.sendResponseHeaders(200, 0);

            SseSubscriber sub = new SseSubscriber(exchange.getResponseBody());
            subscribers.add(sub);

            try {
                sub.send("ready", "{}");
                while (!sub.isClosed()) {
                    sub.waitForCloseOrTimeout(heartbeatInterval.toMillis());
                    if (sub.isClosed()) break;
                    sub.send("heartbeat", "{}");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                subscribers.remove(sub);
                sub.close();
                try {
                    exchange.close();
                } catch (Exception ignored) {
                    // already closing
                }
            }
        }
    }

    /**
     * Per-connection SSE writer. Frame format is
     * {@code event: <type>\ndata: <data>\n\n}. Write failures (client
     * disconnect) mark the subscriber as closed so the heartbeat loop
     * exits cleanly. {@link #close} can be invoked from another thread
     * (by {@link HttpTransport#close} on shutdown) to interrupt the
     * heartbeat wait.
     */
    static final class SseSubscriber {

        private final OutputStream out;
        private volatile boolean closed = false;
        private final Object writeLock = new Object();
        private final Object sleepLock = new Object();

        SseSubscriber(OutputStream out) {
            this.out = out;
        }

        /**
         * Write one SSE frame. Silently drops if the subscriber is closed
         * or the wire-write throws (treats as client disconnect).
         */
        void send(String type, String dataJson) {
            if (closed) return;
            byte[] frame = ("event: " + type + "\ndata: " + dataJson + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
            synchronized (writeLock) {
                if (closed) return;
                try {
                    out.write(frame);
                    out.flush();
                } catch (IOException e) {
                    closed = true;
                    // Wake the heartbeat loop so it can clean up.
                    synchronized (sleepLock) {
                        sleepLock.notifyAll();
                    }
                }
            }
        }

        /** Wait up to {@code millis} ms, returning early on {@link #close}. */
        void waitForCloseOrTimeout(long millis) throws InterruptedException {
            synchronized (sleepLock) {
                if (closed) return;
                sleepLock.wait(millis);
            }
        }

        boolean isClosed() {
            return closed;
        }

        void close() {
            closed = true;
            synchronized (sleepLock) {
                sleepLock.notifyAll();
            }
        }
    }

    private static void sendEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
    }
}
