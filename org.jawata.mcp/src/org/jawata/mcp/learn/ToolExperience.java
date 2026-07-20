package org.jawata.mcp.learn;

/**
 * One captured tool-usage outcome (Sprint 26a, D2): the atom the experience
 * loop stores and retrieves — "in this situation, this tool had this outcome".
 * SELECTIVE by construction: only outcome-bearing events become a
 * {@code ToolExperience} (a mutate's compile result, or a tool error). A
 * routine successful read/search never produces one — the tool always answers,
 * so it carries no experience. A jawata-fallback is captured studio-side as a
 * {@code failure_mode} entry (read by the GENERAL experience recall, not by the
 * precedent retriever's {@code tool_experience} lane), NOT at this tap —
 * {@link #OUTCOME_FALLBACK} exists for that cross-lane value, and no code path
 * writes it into {@code tool_experience}.
 *
 * @param sessionId  the MCP session the event belongs to ({@code null} → "local")
 * @param situation  a keyword-rich description of what was happening (tool +
 *                   salient args) — the key baseline retrieval matches on
 * @param tool       the tool whose outcome this is
 * @param outcome    one of the {@code OUTCOME_*} constants
 * @param detailJson a small JSON blob (gate / errors / undo detail); never PII
 */
public record ToolExperience(String sessionId, String situation, String tool,
                             String outcome, String detailJson) {

    /** A mutate whose subsequent gate came back clean — the tool WORKED. */
    public static final String OUTCOME_COMPILED = "compiled";
    /** A mutate that was undone, or whose gate failed after it — output was BAD. */
    public static final String OUTCOME_REVERTED = "reverted";
    /** A tool returned a structured error — it did not fit / misfired. */
    public static final String OUTCOME_ERROR = "error";
    /** The agent declared a jawata-fallback (also recorded studio-side as a
     *  {@code failure_mode} entry the retriever reads). */
    public static final String OUTCOME_FALLBACK = "fallback";
}
