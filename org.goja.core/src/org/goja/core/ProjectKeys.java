package org.goja.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Helpers for deriving stable short project keys from filesystem paths.
 *
 * <p>A project key is the short, sanitized identifier used by Sprint 10
 * multi-project workspaces to address one of N projects loaded into a
 * shared MCP server. Two design properties matter:
 *
 * <ul>
 *   <li><b>Stable across sessions</b> — the same path always derives the
 *       same key, so MCP-tool clients can persist references safely.</li>
 *   <li><b>Collision-free across loaded projects</b> — when two project
 *       paths share a last-segment name (e.g. <code>foo/strategies</code>
 *       and <code>bar/strategies</code>), the second key gets a hash
 *       suffix to disambiguate.</li>
 * </ul>
 *
 * <p>Pure {@link java.nio.file.Path} input, pure {@link String} output —
 * no JDT, OSGi, or Eclipse Workspace types in the signature. Liftable into
 * a future Eclipse IDE plugin or LSP-based standalone server verbatim
 * (ADR&nbsp;0004).
 */
public final class ProjectKeys {

    private ProjectKeys() {}

    /**
     * Derive a stable short project key from a project path.
     *
     * <p>The base form is the sanitized last segment of the absolute,
     * normalized path: lowercase letters / digits / hyphens only. Use
     * {@link #disambiguate(String, Path)} when you need to resolve a
     * collision against keys already loaded.
     *
     * @param projectPath filesystem path to the project root
     * @return base project key, e.g. {@code "payments-api"}
     */
    public static String derive(Path projectPath) {
        Path abs = projectPath.toAbsolutePath().normalize();
        Path filename = abs.getFileName();
        String last = filename != null ? filename.toString() : abs.toString();
        return sanitize(last);
    }

    /**
     * Disambiguate a project key against a collision by appending a short
     * hash of the absolute path. Use when {@link #derive(Path)} returns a
     * key that already exists in the workspace.
     *
     * @param baseKey     the colliding base key from {@link #derive(Path)}
     * @param projectPath the path being registered
     * @return collision-free key, e.g. {@code "payments-api-7af3c2"}
     */
    public static String disambiguate(String baseKey, Path projectPath) {
        return baseKey + "-" + shortHash(projectPath);
    }

    private static String sanitize(String raw) {
        String lower = raw.toLowerCase();
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            } else if (c == '-' || c == '_' || c == '.' || c == ' ' || c == '/' || c == '\\') {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '-') {
                    out.append('-');
                }
            }
        }
        // Trim leading/trailing hyphens.
        int start = 0;
        int end = out.length();
        while (start < end && out.charAt(start) == '-') start++;
        while (end > start && out.charAt(end - 1) == '-') end--;
        if (start == end) {
            return "project";  // fallback for pathological inputs
        }
        return out.substring(start, end);
    }

    private static String shortHash(Path projectPath) {
        try {
            String input = projectPath.toAbsolutePath().normalize().toString();
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 3; i++) {
                hex.append(String.format("%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 is universally available on the JREs goja runs on,
            // but if it's not, fall back to a deterministic numeric hash.
            return Integer.toHexString(projectPath.hashCode() & 0xffffff);
        }
    }
}
