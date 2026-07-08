package org.goja.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Detector;
import org.goja.mcp.domain.Finding;
import org.goja.mcp.domain.Findings;
import org.goja.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        try {
            for (Path path : service.getAllJavaFiles()) {
                ICompilationUnit cu = service.getCompilationUnit(path);
                if (cu == null) {
                    continue;
                }
                CompilationUnit ast = parse(cu);
                if (ast == null) {
                    continue;
                }
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
        return Findings.toResponse(out);
    }

    private static CompilationUnit parse(ICompilationUnit cu) {
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(false);   // imports are syntactic — no bindings needed
            return (CompilationUnit) parser.createAST(null);
        } catch (Exception e) {
            return null;
        }
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
