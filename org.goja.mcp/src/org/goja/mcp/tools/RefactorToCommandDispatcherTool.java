package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.RefactoringChangeCache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 19 (Kerievsky) — <b>Replace Conditional Dispatcher with Command</b> (toward
 * pattern). A method that switches over a type-coded action, with a non-trivial body
 * per case, has each case body lifted into a nested {@code Command} class; the
 * dispatch collapses to a {@code switch}-expression that <em>selects</em> the command
 * and executes it. Command classes are nested (inner) so their bodies keep direct
 * access to the context's members.
 *
 * <p>Conservative so the result compiles: the switch must be its method's only
 * statement; each case label a {@code static final int} constant of the context;
 * each case body ends in break/return with no fall-through and no {@code this}; case
 * bodies must not reference the method's parameters (the generated {@code execute()}
 * takes none). A non-void dispatcher must have a {@code default}; a void one without
 * a default gets a synthesized no-op. Reuses
 * {@code find_quality_issue(kind=switch_statements)} as the trigger.</p>
 */
public class RefactorToCommandDispatcherTool extends AbstractApplyingRefactoringTool {

    public RefactorToCommandDispatcherTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "refactor_to_command_dispatcher";
    }

    @Override
    public String getDescription() {
        return "Replace Conditional Dispatcher with Command — a type-coded action switch becomes nested "
            + "Command classes selected by a switch-expression. Conservative. Delegate of refactor_to_pattern.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of("type", "string", "description", "Source file."),
            "line", Map.of("type", "integer", "description", "Zero-based line on/inside the dispatch switch."),
            "column", Map.of("type", "integer", "description", "Zero-based column on/inside the switch.")));
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
        String source = cu.getSource();
        CompilationUnit ast = PatternSupport.parse(cu);
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "Invalid position"));
        }
        SwitchStatement sw = PatternSupport.enclosing(new NodeFinder(ast, offset, 0).getCoveringNode(),
            SwitchStatement.class);
        if (sw == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "No switch statement at position"));
        }
        MethodDeclaration method = PatternSupport.enclosing(sw, MethodDeclaration.class);
        TypeDeclaration context = PatternSupport.enclosing(sw, TypeDeclaration.class);
        if (method == null || context == null) {
            return Preparation.fail(ToolResponse.invalidParameter("selection", "Switch must be inside a method of a class"));
        }
        if (method.getBody() == null || method.getBody().statements().size() != 1
            || method.getBody().statements().get(0) != sw) {
            return Preparation.fail(ToolResponse.invalidParameter("method",
                "refactor_to_command_dispatcher (v1.4) requires the switch to be the method's only statement."));
        }

        List<PatternSupport.ParsedCase> cases = PatternSupport.parseCases(sw);
        if (cases == null || cases.size() < 2) {
            return Preparation.fail(ToolResponse.invalidParameter("switch",
                "Need >= 2 single-label cases."));
        }
        // method parameters must not be referenced by case bodies (execute() takes none).
        Set<IVariableBinding> params = new HashSet<>();
        for (Object p : method.parameters()) {
            IVariableBinding b = ((SingleVariableDeclaration) p).resolveBinding();
            if (b != null) {
                params.add(b);
            }
        }

        String contextName = context.getName().getIdentifier();
        String commandType = contextName + "Command";
        String returnType = method.getReturnType2() != null ? method.getReturnType2().toString() : "void";
        boolean isVoid = "void".equals(returnType);

        // Build the command classes + the switch-expression arms.
        List<String[]> arms = new ArrayList<>(); // {labelSource, commandClassName}
        List<String> classDecls = new ArrayList<>();
        boolean hasDefault = false;
        for (PatternSupport.ParsedCase c : cases) {
            String bodyText = PatternSupport.caseBodyText(c.body(), source, null);
            if (bodyText == null || referencesParam(c.body(), params)) {
                return Preparation.fail(ToolResponse.invalidParameter("switch",
                    "Each case must end in break/return, no fall-through, no `this`, and not reference the "
                        + "method's parameters."));
            }
            String className;
            if (c.isDefault()) {
                hasDefault = true;
                className = "DefaultCommand";
                arms.add(new String[]{null, className});
            } else {
                if (!PatternSupport.isStaticFinalIntConstant(c.label(), context)) {
                    return Preparation.fail(ToolResponse.invalidParameter("switch",
                        "Each case label must be a static-final int constant of the class."));
                }
                className = PatternSupport.pascal(((SimpleName) c.label()).getIdentifier()) + "Command";
                arms.add(new String[]{source.substring(c.label().getStartPosition(),
                    c.label().getStartPosition() + c.label().getLength()), className});
            }
            classDecls.add(commandClass(className, commandType, returnType, bodyText));
        }
        if (!hasDefault) {
            if (!isVoid) {
                return Preparation.fail(ToolResponse.invalidParameter("switch",
                    "A non-void dispatcher must have a default case."));
            }
            classDecls.add(commandClass("NoOpCommand", commandType, returnType, "// no-op"));
            arms.add(new String[]{null, "NoOpCommand"});
        }

        String indent = "    ";
        String memberIndent = indent + indent;
        String bodyIndent = memberIndent + indent;

        // 1. nested interface + command classes before the context's closing brace.
        StringBuilder nested = new StringBuilder("\n");
        nested.append(memberIndent).append("interface ").append(commandType).append(" {\n")
            .append(bodyIndent).append(returnType).append(" execute();\n")
            .append(memberIndent).append("}\n");
        for (String decl : classDecls) {
            nested.append(decl);
        }
        int insertAt = context.getStartPosition() + context.getLength() - 1;
        List<TextEdit> edits = new ArrayList<>();
        edits.add(new InsertEdit(insertAt, nested.toString()));

        // 2. replace the switch with: <Cmd> __command = switch (sel) { arms }; [return] __command.execute();
        String selector = source.substring(sw.getExpression().getStartPosition(),
            sw.getExpression().getStartPosition() + sw.getExpression().getLength());
        String swIndent = leadingIndent(source, sw.getStartPosition());
        StringBuilder repl = new StringBuilder();
        repl.append(commandType).append(" __command = switch (").append(selector).append(") {\n");
        for (String[] arm : arms) {
            repl.append(swIndent).append(indent);
            if (arm[0] == null) {
                repl.append("default -> new ").append(arm[1]).append("();\n");
            } else {
                repl.append("case ").append(arm[0]).append(" -> new ").append(arm[1]).append("();\n");
            }
        }
        repl.append(swIndent).append("};\n");
        repl.append(swIndent).append(isVoid ? "" : "return ").append("__command.execute();");
        edits.add(new ReplaceEdit(sw.getStartPosition(), sw.getLength(), repl.toString()));

        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits("refactor to command dispatcher " + contextName,
            Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("contextClass", contextName);
        extras.put("commandInterface", commandType);
        extras.put("commands", arms.stream().map(a -> a[1]).distinct().toList());
        String summary = "refactor to command dispatcher: " + contextName + " -> " + commandType
            + " with " + classDecls.size() + " commands";
        return Preparation.of(change, summary, extras);
    }

    private String commandClass(String className, String commandType, String returnType, String bodyText) {
        String indent = "    ";
        String memberIndent = indent + indent;
        String bodyIndent = memberIndent + indent;
        return memberIndent + "final class " + className + " implements " + commandType + " {\n"
            + bodyIndent + "public " + returnType + " execute() {\n"
            + PatternSupport.reindent(bodyText, bodyIndent + indent) + "\n"
            + bodyIndent + "}\n"
            + memberIndent + "}\n";
    }

    private static boolean referencesParam(List<Statement> body, Set<IVariableBinding> params) {
        if (params.isEmpty()) {
            return false;
        }
        boolean[] hit = {false};
        for (Statement s : body) {
            s.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    if (node.resolveBinding() instanceof IVariableBinding b) {
                        for (IVariableBinding p : params) {
                            if (b.isEqualTo(p)) {
                                hit[0] = true;
                            }
                        }
                    }
                    return true;
                }
            });
        }
        return hit[0];
    }

    private static String leadingIndent(String source, int offset) {
        int lineStart = source.lastIndexOf('\n', offset - 1) + 1;
        StringBuilder indent = new StringBuilder();
        for (int i = lineStart; i < offset && i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == ' ' || c == '\t') {
                indent.append(c);
            } else {
                break;
            }
        }
        return indent.toString();
    }
}
