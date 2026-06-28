package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.core.resources.IFile;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.RefactoringChangeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Convert an anonymous class implementing a functional interface to a lambda expression.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}.</p>
 */
public class ConvertAnonymousToLambdaTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ConvertAnonymousToLambdaTool.class);

    public ConvertAnonymousToLambdaTool(Supplier<IJdtService> serviceSupplier,
                                        RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "convert_anonymous_to_lambda";
    }

    @Override
    public String getDescription() {
        return """
            Convert an anonymous class implementing a functional interface to a lambda expression.

            Applies the conversion directly (default) and returns
            { filesModified, diff, undoChangeId, summary }. Verify with
            compile_workspace; revert with undo_refactoring(undoChangeId).
            Pass auto_apply: false to stage instead — returns { changeId, diff }.

            USAGE: Position cursor on the 'new' keyword of the anonymous class
            OUTPUT: Modified file + unified diff + undo handle

            IMPORTANT: Uses ZERO-BASED coordinates.
            REQUIREMENTS: The anonymous class must implement a functional interface
            (exactly one abstract method).

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
                "description", "Path to source file"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number of anonymous class (on 'new' keyword)"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
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

        // Parse to AST with bindings
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        // Calculate offset
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "Invalid position"));
        }

        // Find the ClassInstanceCreation with AnonymousClassDeclaration
        NodeFinder finder = new NodeFinder(ast, offset, 0);
        ASTNode node = finder.getCoveringNode();

        ClassInstanceCreation creation = findClassInstanceCreation(node);
        if (creation == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "No anonymous class found at position. Position cursor on 'new' keyword."));
        }

        AnonymousClassDeclaration anonymousClass = creation.getAnonymousClassDeclaration();
        if (anonymousClass == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "This is not an anonymous class declaration"));
        }

        // Check if it's a functional interface
        // Use getType().resolveBinding() to get the interface type, not the anonymous class type
        ITypeBinding typeBinding = creation.getType().resolveBinding();
        if (typeBinding == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Cannot resolve type binding"));
        }

        IMethodBinding samMethod = findSingleAbstractMethod(typeBinding);
        if (samMethod == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type",
                "Not a functional interface - must have exactly one abstract method"));
        }

        // Get the method declaration from the anonymous class
        @SuppressWarnings("unchecked")
        List<?> bodyDecls = anonymousClass.bodyDeclarations();
        MethodDeclaration methodDecl = null;

        for (Object decl : bodyDecls) {
            if (decl instanceof MethodDeclaration md) {
                if (methodDecl != null) {
                    // Multiple methods - can't convert to lambda
                    return Preparation.fail(ToolResponse.invalidParameter("anonymousClass",
                        "Anonymous class has multiple methods, cannot convert to lambda"));
                }
                methodDecl = md;
            }
        }

        if (methodDecl == null) {
            return Preparation.fail(ToolResponse.invalidParameter("anonymousClass",
                "Anonymous class has no method implementation"));
        }

        // Check for 'this' references which have different semantics in lambdas
        if (usesThisExpression(methodDecl)) {
            return Preparation.fail(ToolResponse.invalidParameter("anonymousClass",
                "Method uses 'this' keyword which has different semantics in lambda. " +
                "Manual review required."));
        }

        // Build the lambda expression
        String lambdaExpression = buildLambdaExpression(methodDecl, cu);
        if (lambdaExpression == null) {
            return Preparation.fail(ToolResponse.internalError("Failed to build lambda expression"));
        }

        List<TextEdit> edits = new ArrayList<>();
        edits.add(new ReplaceEdit(
            creation.getStartPosition(), creation.getLength(), lambdaExpression));

        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits(
            "convert anonymous to lambda (" + typeBinding.getName() + ")", Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filePath", service.getPathUtils().formatPath(path));
        extras.put("interfaceType", typeBinding.getName());
        extras.put("methodName", samMethod.getName());
        extras.put("lambdaExpression", lambdaExpression);

        String summary = "convert anonymous " + typeBinding.getName() + " to lambda";
        return Preparation.of(change, summary, extras);
    }

    private ClassInstanceCreation findClassInstanceCreation(ASTNode node) {
        while (node != null) {
            if (node instanceof ClassInstanceCreation cic) {
                if (cic.getAnonymousClassDeclaration() != null) {
                    return cic;
                }
            }
            node = node.getParent();
        }
        return null;
    }

    private IMethodBinding findSingleAbstractMethod(ITypeBinding typeBinding) {
        IMethodBinding samMethod = null;

        // Check declared methods
        for (IMethodBinding method : typeBinding.getDeclaredMethods()) {
            if (isAbstractMethod(method)) {
                if (samMethod != null) {
                    return null; // More than one abstract method
                }
                samMethod = method;
            }
        }

        // Check interface hierarchy
        for (ITypeBinding superInterface : typeBinding.getInterfaces()) {
            IMethodBinding inherited = findSingleAbstractMethod(superInterface);
            if (inherited != null) {
                if (samMethod != null && !methodsMatch(samMethod, inherited)) {
                    return null; // Multiple different abstract methods
                }
                if (samMethod == null) {
                    samMethod = inherited;
                }
            }
        }

        return samMethod;
    }

    private boolean isAbstractMethod(IMethodBinding method) {
        // Check if method is abstract (not default, not static)
        int modifiers = method.getModifiers();
        if ((modifiers & org.eclipse.jdt.core.dom.Modifier.STATIC) != 0) {
            return false;
        }
        if ((modifiers & org.eclipse.jdt.core.dom.Modifier.DEFAULT) != 0) {
            return false;
        }
        // Object methods are not counted
        if (isObjectMethod(method)) {
            return false;
        }
        return true;
    }

    private boolean isObjectMethod(IMethodBinding method) {
        String name = method.getName();
        ITypeBinding[] params = method.getParameterTypes();

        // toString(), hashCode(), equals(Object), etc.
        if ("toString".equals(name) && params.length == 0) return true;
        if ("hashCode".equals(name) && params.length == 0) return true;
        if ("equals".equals(name) && params.length == 1 &&
            "java.lang.Object".equals(params[0].getQualifiedName())) return true;
        if ("clone".equals(name) && params.length == 0) return true;
        if ("finalize".equals(name) && params.length == 0) return true;

        return false;
    }

    private boolean methodsMatch(IMethodBinding m1, IMethodBinding m2) {
        if (!m1.getName().equals(m2.getName())) return false;
        ITypeBinding[] params1 = m1.getParameterTypes();
        ITypeBinding[] params2 = m2.getParameterTypes();
        if (params1.length != params2.length) return false;
        for (int i = 0; i < params1.length; i++) {
            if (!params1[i].getErasure().isEqualTo(params2[i].getErasure())) {
                return false;
            }
        }
        return true;
    }

    private boolean usesThisExpression(MethodDeclaration method) {
        final boolean[] usesThis = {false};

        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(ThisExpression node) {
                usesThis[0] = true;
                return false;
            }
        });

        return usesThis[0];
    }

    private String buildLambdaExpression(MethodDeclaration method, ICompilationUnit cu) {
        StringBuilder lambda = new StringBuilder();

        // Build parameter list
        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> params = method.parameters();

        if (params.isEmpty()) {
            lambda.append("()");
        } else if (params.size() == 1) {
            // Single parameter - can omit parentheses and type
            lambda.append(params.get(0).getName().getIdentifier());
        } else {
            lambda.append("(");
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) lambda.append(", ");
                lambda.append(params.get(i).getName().getIdentifier());
            }
            lambda.append(")");
        }

        lambda.append(" -> ");

        // Build body
        Block body = method.getBody();
        if (body == null) {
            lambda.append("{}");
            return lambda.toString();
        }

        @SuppressWarnings("unchecked")
        List<Statement> statements = body.statements();

        if (statements.isEmpty()) {
            lambda.append("{}");
        } else if (statements.size() == 1) {
            Statement stmt = statements.get(0);

            if (stmt instanceof ReturnStatement rs) {
                Expression expr = rs.getExpression();
                if (expr != null) {
                    // Single return statement - use expression form
                    lambda.append(expr.toString());
                } else {
                    // return; with no value
                    lambda.append("{}");
                }
            } else if (stmt instanceof ExpressionStatement es) {
                // Single expression statement (like method call)
                lambda.append(es.getExpression().toString());
            } else {
                // Other single statement - use block form
                lambda.append("{ ");
                lambda.append(stmt.toString().trim());
                lambda.append(" }");
            }
        } else {
            // Multiple statements - use block form
            lambda.append("{\n");
            String indent = "        "; // Default indentation for lambda body

            for (Statement stmt : statements) {
                lambda.append(indent);
                lambda.append(stmt.toString().trim());
                lambda.append("\n");
            }

            lambda.append("    }");
        }

        return lambda.toString();
    }

}
