package org.goja.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Detector;
import org.goja.mcp.domain.Finding;
import org.goja.mcp.domain.Findings;
import org.goja.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Sprint 17 (Fowler) — <b>Divergent Change</b>. One class changed in many
 * commits, alongside many unrelated areas, is being edited for many different
 * reasons — it has too many responsibilities. Uses {@link GitHistory}: a file
 * with {@code >= threshold} commits AND {@code >= threshold} distinct co-changed
 * areas (default 5) is flagged. Pointed refactoring: <b>Extract Class</b>.
 *
 * <p>Git-dependent: when the project is not a git work-tree (or git is absent)
 * the history is {@linkplain GitHistory#available() unavailable} and this
 * detector returns no findings — never an error.</p>
 */
public final class DivergentChangeDetector implements Detector {

    private final Function<Path, GitHistory> historyProvider;

    public DivergentChangeDetector() {
        this(GitHistory::forRoot);
    }

    /** Test seam: supply a GitHistory for a given root. */
    DivergentChangeDetector(Function<Path, GitHistory> historyProvider) {
        this.historyProvider = historyProvider;
    }

    @Override
    public String kind() {
        return "divergent_change";
    }

    @Override
    public String description() {
        return "Divergent Change — a file changed in >= `threshold` commits across >= `threshold` "
            + "distinct areas (default 5); changing for many reasons. Points to Extract Class. "
            + "Requires git history (no-op off a git work-tree).";
    }

    @Override
    public ToolResponse detect(IJdtService service, JsonNode arguments) {
        int threshold = AbstractAstDetector.readInt(arguments, "threshold", 5);
        boolean includeTests = AbstractAstDetector.includeTests(arguments);
        List<Finding> out = new ArrayList<>();
        Path root = service.getProjectRoot();
        GitHistory history = historyProvider.apply(root);
        if (!history.available() || root == null) {
            return Findings.toResponse(out); // graceful no-op
        }
        for (Path file : service.getAllJavaFiles()) {
            if (!includeTests && AbstractAstDetector.isTestSource(file, service)) {
                continue;
            }
            String rel = root.relativize(file).toString().replace('\\', '/');
            int commits = history.commitCount(rel);
            int areas = history.coChangeAreaCount(rel);
            if (commits >= threshold && areas >= threshold) {
                out.add(new Finding(
                    "divergent_change", service.getPathUtils().formatPath(file), 1, -1, "warning",
                    "Changed in " + commits + " commits across " + areas + " distinct areas (threshold "
                        + threshold + ") — likely changing for many reasons. Consider Extract Class." + OcpCure.HINT,
                    stem(rel)));
            }
        }
        return Findings.toResponse(out);
    }

    private static String stem(String relPath) {
        int slash = relPath.lastIndexOf('/');
        String name = slash >= 0 ? relPath.substring(slash + 1) : relPath;
        return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
    }
}
