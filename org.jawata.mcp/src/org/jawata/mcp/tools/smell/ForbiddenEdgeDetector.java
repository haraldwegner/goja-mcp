package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Detector;
import org.jawata.mcp.domain.Finding;
import org.jawata.mcp.domain.Findings;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.SourceScan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 22a P1-d — forbidden dependency-direction rule (layering / clean
 * architecture). Given a rule {@code {from, forbidden}} of package prefixes,
 * reports every import in a {@code from} package that targets a
 * {@code forbidden} package.
 *
 * <p>Rule-driven: with no {@code from}/{@code forbidden} it reports nothing, so
 * it stays quiet inside a {@code family="quality"} sweep and only fires when a
 * caller supplies a rule. Reuses no cross-file search — an import declaration
 * carries both its own file:line and the target package syntactically.</p>
 */
public class ForbiddenEdgeDetector implements Detector {

    @Override
    public String kind() {
        return "forbidden_edge";
    }

    @Override
    public String description() {
        return "Forbidden dependency direction: imports from a `from` package into a "
            + "`forbidden` package (both are package prefixes). Reports nothing without a rule.";
    }

    @Override
    public ToolResponse detect(IJdtService service, JsonNode arguments) {
        String from = str(arguments, "from");
        String forbidden = str(arguments, "forbidden");
        List<Finding> out = new ArrayList<>();
        if (from == null || from.isBlank() || forbidden == null || forbidden.isBlank()) {
            return Findings.toResponse(out);   // no rule → no violations
        }
        SourceScan scan = SourceScan.of(service.getAllJavaFiles());
        try {
            for (Path path : scan.files()) {
                ICompilationUnit cu = scan.resolve(service, path);
                if (cu == null) {
                    continue;   // RECORDED, not swallowed — see SourceScan
                }
                CompilationUnit ast = scan.parse(cu, path, false);
                if (ast == null) {
                    continue;
                }
                scan.examined();
                PackageDeclaration pkg = ast.getPackage();
                String pkgName = pkg != null ? pkg.getName().getFullyQualifiedName() : "";
                if (!pkgMatches(pkgName, from)) {
                    continue;
                }
                String formatted = service.getPathUtils().formatPath(path);
                for (Object o : ast.imports()) {
                    ImportDeclaration imp = (ImportDeclaration) o;
                    String importName = imp.getName().getFullyQualifiedName();
                    String importPkg = imp.isOnDemand() ? importName : packageOf(importName);
                    if (pkgMatches(importPkg, forbidden)) {
                        int line = ast.getLineNumber(imp.getStartPosition());
                        int col = ast.getColumnNumber(imp.getStartPosition()) + 1;
                        out.add(new Finding("forbidden_edge", formatted, line, col, "error",
                            "forbidden dependency: " + pkgName + " must not depend on " + forbidden
                                + " (imports " + importName + ")", null));
                    }
                }
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }

        // "No forbidden edges" is only sayable if we managed to read the imports.
        Optional<ToolResponse> blind = scan.refuseIfBlind("forbidden dependency edges");
        if (blind.isPresent()) {
            return blind.get();
        }
        return Findings.toResponse(out, scan.describe(),
            scan.steering(out.size(), "forbidden dependency edges"));
    }

    /** True iff {@code pkg} equals {@code prefix} or is a sub-package of it. */
    private static boolean pkgMatches(String pkg, String prefix) {
        return pkg.equals(prefix) || pkg.startsWith(prefix + ".");
    }

    private static String packageOf(String fqn) {
        int i = fqn.lastIndexOf('.');
        return i < 0 ? "" : fqn.substring(0, i);
    }

    private static String str(JsonNode args, String name) {
        return (args != null && args.has(name) && !args.get(name).isNull())
            ? args.get(name).asText() : null;
    }
}
