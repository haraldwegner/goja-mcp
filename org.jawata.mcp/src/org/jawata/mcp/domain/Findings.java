package org.jawata.mcp.domain;

import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 16b/D — renders a {@code List<Finding>} into the MCP {@link ToolResponse}
 * wire shape. The path a Finding-producing detector (Sprint 17/20) takes to the
 * tool surface: compute findings → {@code Findings.toResponse(findings)}.
 */
public final class Findings {

    private Findings() {
    }

    /** {@code { count, findings: [ {kind, filePath, line, column, severity, message, symbol} ] } } */
    public static ToolResponse toResponse(List<Finding> findings) {
        return toResponse(findings, Map.of(), null);
    }

    /**
     * The same, plus <b>what the scan actually managed to examine</b>.
     *
     * <p>Without that, {@code count: 0} is unreadable: it means either "this code is clean"
     * or "we examined nothing", and only one of those is an answer. A detector that skipped
     * every file it could not read — and then reported no findings — is telling you your code
     * is fine on the strength of having looked at none of it.</p>
     */
    public static ToolResponse toResponse(List<Finding> findings,
                                          Map<String, Object> scan,
                                          String steering) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", f.kind());
            if (f.filePath() != null) row.put("filePath", f.filePath());
            if (f.line() >= 0) row.put("line", f.line());
            if (f.column() >= 0) row.put("column", f.column());
            if (f.severity() != null) row.put("severity", f.severity());
            row.put("message", f.message());
            if (f.symbol() != null) row.put("symbol", f.symbol());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", rows.size());
        data.put("findings", rows);
        data.putAll(scan);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(rows.size())
            .returnedCount(rows.size())
            .steering(steering)
            .build());
    }
}
