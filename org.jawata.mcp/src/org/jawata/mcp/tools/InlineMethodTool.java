package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.corext.refactoring.code.InlineMethodRefactoring;
import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.RefactoringEngine;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Inline a method call by replacing it with the method body.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}.</p>
 *
 * <p>Sprint 25 (D1b): the inlining is computed by JDT's own
 * {@link InlineMethodRefactoring} — the engine behind the IDE's Refactor →
 * Inline — driven through the {@link RefactoringEngine} seam. The original
 * implementation was a hand-rolled string substitution that regex-replaced
 * parameter names with argument text and emitted "review needed" comments for
 * any body it could not handle (multiple statements, multiple returns). The JDT
 * engine performs a real inline — proper argument binding, name-capture
 * avoidance, control-flow handling — and REFUSES what it cannot transform, with
 * its reasons in the response. The v2.12.1 compile-verify gate in the contract
 * base stays wrapped around the applied change.</p>
 */
public class InlineMethodTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(InlineMethodTool.class);

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    public InlineMethodTool(Supplier<IJdtService> serviceSupplier,
                            RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "inline_method";
    }

    @Override
    public String getDescription() {
        return """
            Inline a method call by replacing it with the method body (JDT's own Inline Method engine).

            Applies the inlining directly (default) and returns
            { filesModified, diff, undoChangeId, summary }, compile-verified on the
            modified files. Revert with undo_refactoring(undoChangeId).
            Pass auto_apply: false to stage instead — returns { changeId, diff }.

            USAGE: Position on a method CALL (inlines that single call) or on the
            method DECLARATION (inlines all calls). The engine performs the real
            JDT Inline Method and REFUSES a call it cannot safely substitute — the
            refusal names the reason.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of(
                "type", "string",
                "description", "Path to source file containing the method call"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number of method call"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number (on method name)"
            )
        ));
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required"));
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column", "Must be >= 0"));
        }

        Path path = Path.of(filePath);
        ICompilationUnit cu = service.getCompilationUnit(path);
        if (cu == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePath));
        }

        HeadlessJdtConfig.ensureInitialized();

        // Parse with bindings; translate the line/column into an offset for the engine.
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "Invalid position"));
        }

        InlineMethodRefactoring refactoring = InlineMethodRefactoring.create(cu, ast, offset, 0);
        if (refactoring == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "No inlinable method call or declaration at position (JDT's Inline Method "
                    + "applies to a method call site or the method declaration)."));
        }

        CheckedChange checked = engine.propose(refactoring, "inline method");
        if (checked.isRefused()) {
            return Preparation.fail(ToolResponse.error(
                "INLINE_REFUSED",
                "inline_method refused this inline: " + checked.messages(),
                "JDT's Inline Method engine rejected it — a call it cannot safely "
                    + "substitute, an overridden method, or a body it cannot inline. Adjust "
                    + "the target and retry. No files were modified."));
        }

        Change change = checked.change();
        IMethod method = refactoring.getMethod();

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filePath", service.getPathUtils().formatPath(path));
        if (method != null) {
            extras.put("methodName", method.getElementName());
            if (method.getDeclaringType() != null) {
                extras.put("methodClass", method.getDeclaringType().getElementName());
            }
        }
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }

        String summary = "inline method "
            + (method != null ? method.getElementName() : "")
            + " at " + service.getPathUtils().formatPath(path);
        log.debug("inline_method via JDT InlineMethodRefactoring: {}", summary);
        return Preparation.of(change, summary, extras);
    }
}
