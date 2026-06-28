package org.goja.mcp.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.goja.mcp.tools.ToolRegistry;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles MCP protocol messages over JSON-RPC 2.0.
 * Routes requests to appropriate handlers and formats responses.
 */
public class McpProtocolHandler {

    private static final Logger log = LoggerFactory.getLogger(McpProtocolHandler.class);

    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private boolean initialized = false;
    private String clientName;
    private String clientVersion;

    public McpProtocolHandler(ToolRegistry toolRegistry) {
        this.objectMapper = new ObjectMapper();
        this.toolRegistry = toolRegistry;
    }

    /**
     * Process an incoming JSON-RPC message and return a response.
     * Returns null for notifications (no response required).
     */
    public String processMessage(String jsonMessage) {
        try {
            JsonRpcMessage request = objectMapper.readValue(jsonMessage, JsonRpcMessage.class);

            // Validate JSON-RPC version
            if (!"2.0".equals(request.getJsonrpc())) {
                return formatError(request.getId(),
                    JsonRpcMessage.JsonRpcError.INVALID_REQUEST,
                    "Invalid JSON-RPC version. Expected 2.0",
                    null);
            }

            // Handle request
            if (request.isRequest()) {
                return handleRequest(request);
            }

            // Handle notification (no response needed)
            if (request.isNotification()) {
                handleNotification(request);
                return null;
            }

            // Unknown message type
            return formatError(request.getId(),
                JsonRpcMessage.JsonRpcError.INVALID_REQUEST,
                "Invalid message type",
                null);

        } catch (JsonProcessingException e) {
            log.error("Failed to parse JSON-RPC message", e);
            return formatError(null,
                JsonRpcMessage.JsonRpcError.PARSE_ERROR,
                "Parse error: " + e.getMessage(),
                null);
        } catch (Exception e) {
            log.error("Error processing message", e);
            return formatError(null,
                JsonRpcMessage.JsonRpcError.INTERNAL_ERROR,
                "Internal error: " + e.getMessage(),
                null);
        }
    }

    private String handleRequest(JsonRpcMessage request) {
        String method = request.getMethod();
        Object id = request.getId();
        JsonNode params = request.getParams();

        log.debug("Handling request: method={}, id={}", method, id);

        try {
            Object result = switch (method) {
                case "initialize" -> handleInitialize(params);
                case "initialized" -> handleInitialized();
                case "tools/list" -> handleToolsList();
                case "tools/call" -> handleToolsCall(params);
                case "shutdown" -> handleShutdown();
                default -> throw new MethodNotFoundException("Method not found: " + method);
            };

            return formatSuccess(id, result);

        } catch (MethodNotFoundException e) {
            return formatError(id,
                JsonRpcMessage.JsonRpcError.METHOD_NOT_FOUND,
                e.getMessage(),
                null);
        } catch (InvalidParamsException e) {
            return formatError(id,
                JsonRpcMessage.JsonRpcError.INVALID_PARAMS,
                e.getMessage(),
                null);
        } catch (Exception e) {
            log.error("Error handling request: " + method, e);
            return formatError(id,
                JsonRpcMessage.JsonRpcError.INTERNAL_ERROR,
                e.getMessage(),
                null);
        }
    }

    private void handleNotification(JsonRpcMessage notification) {
        String method = notification.getMethod();
        log.debug("Handling notification: method={}", method);

        switch (method) {
            case "notifications/cancelled" -> {
                // Client cancelled a request - log and ignore
                log.debug("Request cancelled by client");
            }
            case "initialized" -> {
                // Some clients send initialized as notification
                handleInitialized();
            }
            default -> log.warn("Unknown notification: {}", method);
        }
    }

    /**
     * MCP protocol versions this server is compatible with. We respond
     * with the client's requested version when it's in this set, falling
     * back to the latest entry otherwise. The basic JSON-RPC / tools
     * surface is identical across these versions; we just need to
     * negotiate a value the client will accept.
     *
     * <p>v1.8.6 (Sprint 14a hotfix): pre-v1.8.6 the server always
     * responded with the obsolete "2024-11-05" value, so clients that
     * required a newer spec (Claude Code v2.1.x and similar) disconnected
     * after initialize. Per the MCP spec, the server MUST respond with a
     * version the client supports or the client SHOULD disconnect.
     */
    static final String LATEST_PROTOCOL_VERSION = "2025-06-18";
    static final java.util.Set<String> SUPPORTED_PROTOCOL_VERSIONS = java.util.Set.of(
        "2024-11-05",
        "2025-03-26",
        "2025-06-18"
    );

    /**
     * Handle initialize request - MCP handshake.
     */
    private Object handleInitialize(JsonNode params) {
        String requestedVersion = null;
        if (params != null) {
            JsonNode clientInfo = params.get("clientInfo");
            if (clientInfo != null) {
                clientName = clientInfo.has("name") ? clientInfo.get("name").asText() : "unknown";
                clientVersion = clientInfo.has("version") ? clientInfo.get("version").asText() : "unknown";
                log.info("Client connected: {} v{}", clientName, clientVersion);
            }
            JsonNode versionNode = params.get("protocolVersion");
            if (versionNode != null && versionNode.isTextual()) {
                requestedVersion = versionNode.asText();
            }
        }

        initialized = true;

        Map<String, Object> result = new LinkedHashMap<>();

        // Protocol-version negotiation: echo the client's requested
        // version when it's in our supported set; otherwise return our
        // latest. Pre-v1.8.6 this was hardcoded to "2024-11-05" and the
        // result was that clients targeting newer specs (Claude Code et
        // al.) hung up after initialize.
        String negotiated;
        if (requestedVersion != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requestedVersion)) {
            negotiated = requestedVersion;
        } else {
            negotiated = LATEST_PROTOCOL_VERSION;
        }
        log.info("Protocol version: client requested {}, negotiated {}",
            requestedVersion == null ? "<none>" : requestedVersion, negotiated);
        result.put("protocolVersion", negotiated);

        // Server info
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "GOJA");
        serverInfo.put("version", serverVersion());
        result.put("serverInfo", serverInfo);

        // Capabilities
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of());  // We support tools
        result.put("capabilities", capabilities);

        return result;
    }

    /**
     * Server version for the initialize handshake, resolved from the OSGi
     * bundle manifest. Falls back to the JAR Implementation-Version, then
     * "unknown" on plain-classpath runtimes (unit tests) — never hardcoded.
     * Public so HealthCheckTool (different package) reports the same value
     * the initialize handshake does (bugs.md #14).
     */
    public static String serverVersion() {
        try {
            Bundle bundle = FrameworkUtil.getBundle(McpProtocolHandler.class);
            if (bundle != null) {
                return bundle.getVersion().toString();
            }
        } catch (NoClassDefFoundError ignored) {
            // OSGi framework classes absent on a plain classpath.
        }
        String implementationVersion =
            McpProtocolHandler.class.getPackage().getImplementationVersion();
        return implementationVersion != null ? implementationVersion : "unknown";
    }

    /**
     * Handle initialized notification - client acknowledged handshake.
     */
    private Object handleInitialized() {
        log.info("Client initialization complete");
        return null;
    }

    /**
     * Handle tools/list - return available tools.
     */
    private Object handleToolsList() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", toolRegistry.getToolDefinitions());
        return result;
    }

    /**
     * Handle tools/call - execute a tool.
     */
    private Object handleToolsCall(JsonNode params) throws InvalidParamsException, MethodNotFoundException {
        if (params == null) {
            throw new InvalidParamsException("Missing params");
        }

        JsonNode nameNode = params.get("name");
        if (nameNode == null || !nameNode.isTextual()) {
            throw new InvalidParamsException("Missing or invalid 'name' parameter");
        }
        String toolName = nameNode.asText();

        JsonNode arguments = params.get("arguments");

        log.debug("Calling tool: {} with arguments: {}", toolName, arguments);

        try {
            Object toolResult = toolRegistry.callTool(toolName, arguments);

            // MCP expects content array format for tool results
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", new Object[] {
                Map.of(
                    "type", "text",
                    "text", objectMapper.writeValueAsString(toolResult)
                )
            });

            return result;
        } catch (ToolRegistry.ToolNotFoundException e) {
            throw new MethodNotFoundException("Tool not found: " + toolName);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize tool result", e);
        }
    }

    /**
     * Handle shutdown request.
     */
    private Object handleShutdown() {
        log.info("Shutdown requested");
        return null;
    }

    /**
     * Format a successful response.
     */
    private String formatSuccess(Object id, Object result) {
        try {
            JsonRpcMessage response = JsonRpcMessage.successResponse(id, result);
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize response", e);
            return formatError(id,
                JsonRpcMessage.JsonRpcError.INTERNAL_ERROR,
                "Failed to serialize response",
                null);
        }
    }

    /**
     * Format an error response.
     */
    private String formatError(Object id, int code, String message, Object data) {
        try {
            JsonRpcMessage response = JsonRpcMessage.errorResponse(id, code, message, data);
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize error response", e);
            // Fallback to hardcoded error
            return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32603,\"message\":\"Internal error\"}}";
        }
    }

    public boolean isInitialized() {
        return initialized;
    }

    public String getClientName() {
        return clientName;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    // Exception classes
    public static class MethodNotFoundException extends Exception {
        public MethodNotFoundException(String message) {
            super(message);
        }
    }

    public static class InvalidParamsException extends Exception {
        public InvalidParamsException(String message) {
            super(message);
        }
    }
}
