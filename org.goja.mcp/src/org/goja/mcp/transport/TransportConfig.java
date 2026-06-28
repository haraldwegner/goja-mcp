package org.goja.mcp.transport;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Parsed transport selection + parameters from the application's CLI args.
 *
 * <p>Sprint 14a Stage 2 introduces this pure value class so the parser can
 * be unit-tested without booting OSGi. The application's {@code start()}
 * resolves CLI args from {@link org.eclipse.equinox.app.IApplicationContext}
 * and feeds them through {@link #fromArgs(String[])}; downstream wiring picks
 * the transport based on {@link #getKind()}.
 *
 * <p>HTTP is the default per Sprint 14a's HTTP/SSE-default decision; stdio is
 * opt-in via {@code -transport stdio}. Unknown flags are ignored (Eclipse's
 * own framework flags like {@code -data} also flow through this array).
 */
public final class TransportConfig {

    public enum Kind {
        STDIO,
        HTTP
    }

    private final Kind kind;
    private final int port;             // 0 = auto-allocate ephemeral
    private final String bindAddress;
    private final String token;          // null = generate at startup
    private final Path tokenFile;        // null = don't write token to disk

    public TransportConfig(Kind kind, int port, String bindAddress, String token, Path tokenFile) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.port = port;
        this.bindAddress = Objects.requireNonNull(bindAddress, "bindAddress");
        this.token = token;
        this.tokenFile = tokenFile;
    }

    public Kind getKind() {
        return kind;
    }

    public int getPort() {
        return port;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public String getToken() {
        return token;
    }

    public Path getTokenFile() {
        return tokenFile;
    }

    /**
     * Parse a flat CLI argv into a TransportConfig. Recognized flags:
     * <ul>
     *   <li>{@code -transport stdio|http} — default HTTP if absent.</li>
     *   <li>{@code -port N} — 0/absent = ephemeral.</li>
     *   <li>{@code -bind X} — default {@code 127.0.0.1}.</li>
     *   <li>{@code -token T} — absent = generate at startup.</li>
     *   <li>{@code -token-file PATH} — absent = don't write.</li>
     * </ul>
     * Unknown flags are ignored (Eclipse {@code -data} / {@code -clean} pass through).
     *
     * @throws IllegalArgumentException if {@code -transport} carries an
     *         unknown value or a numeric flag fails to parse.
     */
    public static TransportConfig fromArgs(String[] args) {
        if (args == null) {
            args = new String[0];
        }
        Kind kind = Kind.HTTP;
        int port = 0;
        String bind = "127.0.0.1";
        String token = null;
        Path tokenFile = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-transport" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("-transport requires a value (stdio|http)");
                    }
                    String value = args[++i];
                    kind = switch (value) {
                        case "stdio" -> Kind.STDIO;
                        case "http" -> Kind.HTTP;
                        default -> throw new IllegalArgumentException(
                            "Unknown -transport value: " + value + " (expected stdio|http)");
                    };
                }
                case "-port" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("-port requires a numeric value");
                    }
                    try {
                        port = Integer.parseInt(args[++i]);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("-port value is not numeric: " + args[i]);
                    }
                }
                case "-bind" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("-bind requires an address value");
                    }
                    bind = args[++i];
                }
                case "-token" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("-token requires a value");
                    }
                    token = args[++i];
                }
                case "-token-file" -> {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("-token-file requires a path");
                    }
                    tokenFile = Path.of(args[++i]);
                }
                default -> {
                    // Unknown flag — silently ignore (Eclipse framework args
                    // like -data / -clean / -consoleLog reach here too).
                }
            }
        }

        return new TransportConfig(kind, port, bind, token, tokenFile);
    }

    @Override
    public String toString() {
        return "TransportConfig{kind=" + kind
            + ", port=" + port
            + ", bind=" + bindAddress
            + ", token=" + (token == null ? "<generated>" : "<provided>")
            + ", tokenFile=" + tokenFile
            + "}";
    }
}
