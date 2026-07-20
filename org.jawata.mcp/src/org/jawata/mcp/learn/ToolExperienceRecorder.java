package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.knowledge.ToolExperienceStore;
import org.jawata.mcp.models.ToolResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The D2 capture logic (Sprint 26a): turns outcome-bearing tool calls into
 * {@link ToolExperience} rows AS A SIDE EFFECT of normal work — no manual step.
 * SELECTIVE: only events whose outcome VARIES and teaches are captured —
 * <ul>
 *   <li>a tool error → {@code error} (immediately);</li>
 *   <li>a mutate ({@code filesModified > 0}) whose next gate is clean →
 *       {@code compiled}; whose next gate fails, or which is undone →
 *       {@code reverted}.</li>
 * </ul>
 * A routine successful read/search produces NO row — the tool always answers,
 * so it carries no experience (capturing it would flood the store with noise
 * and manufacture false "it worked" precedent). Self-contained: depends only on
 * the store, NOT on the learner models (retired in D4), so it survives that
 * retirement unchanged.
 */
public final class ToolExperienceRecorder {

    private static final Set<String> GATE_TOOLS =
        Set.of("compile_workspace", "get_diagnostics", "run_tests");

    /** Arg keys that make a situation recognizable for keyword retrieval. */
    private static final String[] SALIENT =
        {"symbol", "typeName", "kind", "newName", "name", "query", "filePath"};

    private final ToolExperienceStore store;

    /** Per-session pending mutate: its outcome resolves at the next gate/undo. */
    private record Pending(String tool, String situation) {
    }

    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ToolExperienceRecorder(ToolExperienceStore store) {
        this.store = store;
    }

    /** Called after every completed tool call (success or structured error). */
    public void onCall(String sessionId, String name, JsonNode arguments, ToolResponse response) {
        if (store == null || response == null) {
            return;
        }
        String sid = sessionId == null ? "local" : sessionId;

        if (!response.isSuccess()) {
            store.append(new ToolExperience(sid, situation(name, arguments), name,
                ToolExperience.OUTCOME_ERROR, "{\"error\":true}"));
            return;
        }

        if (GATE_TOOLS.contains(name)) {
            Pending p = pending.remove(sid);
            if (p != null) {
                long errors = errorCount(response);
                store.append(new ToolExperience(sid, p.situation(), p.tool(),
                    errors == 0 ? ToolExperience.OUTCOME_COMPILED : ToolExperience.OUTCOME_REVERTED,
                    "{\"gate\":\"" + name + "\",\"errors\":" + errors + "}"));
            }
            return;
        }

        if (isUndo(name, arguments)) {
            Pending p = pending.remove(sid);
            if (p != null) {
                store.append(new ToolExperience(sid, p.situation(), p.tool(),
                    ToolExperience.OUTCOME_REVERTED, "{\"undo\":true}"));
            }
            return;
        }

        if (filesModified(response) > 0) {
            // A mutate — remember it; its outcome resolves at the next gate/undo.
            // (A second mutate before any gate replaces the first: we capture the
            // outcome of the LAST mutate before the gate — an honest baseline.)
            pending.put(sid, new Pending(name, situation(name, arguments)));
        }
        // Everything else — a routine successful read/search — captures NOTHING.
    }

    private static boolean isUndo(String name, JsonNode args) {
        return "refactoring".equals(name) && args != null
            && args.path("action").asText("").startsWith("undo");
    }

    /** Target-only salient keys (the thing being worked on) — the retrieval
     *  query, so precedent matches ACROSS tools (kind is not a target). */
    private static final String[] TARGET_KEYS =
        {"symbol", "typeName", "filePath", "name", "query"};

    /**
     * The retrieval query for the CURRENT call: the single most salient target
     * identifier (the thing being worked on), tool-independent — so a precedent
     * on the same target surfaces whatever tool produced it. Blank when the call
     * has no target (e.g. {@code compile_workspace}); the push skips those.
     */
    public static String target(String name, JsonNode args) {
        if (args == null) {
            return "";
        }
        for (String key : TARGET_KEYS) {
            String v = args.path(key).asText("");
            if (!v.isBlank()) {
                if ("filePath".equals(key)) {
                    int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
                    v = slash >= 0 ? v.substring(slash + 1) : v;
                }
                return v;
            }
        }
        return "";
    }

    /** A keyword-rich key: the tool plus whatever salient args are present. */
    public static String situation(String name, JsonNode args) {
        StringBuilder sb = new StringBuilder(name == null ? "" : name);
        if (args != null) {
            for (String key : SALIENT) {
                String v = args.path(key).asText("");
                if (!v.isBlank()) {
                    if ("filePath".equals(key)) {
                        int slash = Math.max(v.lastIndexOf('/'), v.lastIndexOf('\\'));
                        v = slash >= 0 ? v.substring(slash + 1) : v;
                    }
                    sb.append(' ').append(v);
                }
            }
        }
        return sb.toString();
    }

    private static int filesModified(ToolResponse response) {
        if (response.getData() instanceof Map<?, ?> map
                && map.get("filesModified") instanceof List<?> files) {
            return files.size();
        }
        return 0;
    }

    private static long errorCount(ToolResponse response) {
        if (response.getData() instanceof Map<?, ?> map) {
            Object v = map.get("errorCount");
            if (v instanceof Number n) {
                return n.longValue();
            }
            Object failed = map.get("failed");
            if (failed instanceof Number n) {
                return n.longValue();
            }
        }
        return 0;
    }
}
