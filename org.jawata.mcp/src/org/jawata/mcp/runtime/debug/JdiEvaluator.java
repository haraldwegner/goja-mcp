package org.jawata.mcp.runtime.debug;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.InterfaceType;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ConditionalExpression;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.InstanceofExpression;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.ThisExpression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sprint 24 (D6) — evaluate a Java expression in a suspended frame of the target
 * JVM.
 *
 * <p><b>How.</b> JDT parses the expression (we already host the best Java parser
 * in this process) and JDI executes it against the live frame — including real
 * method invocation on the target's own threads. That is the whole trick: no
 * second debug model on the event queue, no third-party evaluation engine, and a
 * parser that is exact.</p>
 *
 * <p><b>What it refuses.</b> An expression form this evaluator does not implement
 * is REFUSED BY NAME ({@code EVALUATION_UNSUPPORTED: Assignment}), and an
 * overload it cannot pick between is refused with the candidates listed. A
 * debugger that quietly evaluates something slightly different from what you
 * wrote is worse than one that says no — you would believe the answer.</p>
 *
 * <p>A frame dies whenever the thread resumes, and invoking a method in the target
 * resumes it. So the frame is never cached: it is re-fetched by index after every
 * invocation.</p>
 */
public final class JdiEvaluator {

    /** An evaluation that cannot be performed — with a code the caller can act on. */
    public static class EvalException extends Exception {
        private static final long serialVersionUID = 1L;
        public final String code;

        public EvalException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private final ThreadReference thread;
    private final int frameIndex;
    private final VirtualMachine vm;

    /**
     * Did this evaluation actually RUN code in the target?
     *
     * <p>{@code queue.size()} and {@code queue.clear()} are the same shape to us — we cannot
     * prove a method is pure, and the spec is explicit that an evaluation "may invoke methods
     * and is side-effecting unless proven otherwise". So we record the one thing we know for
     * certain: whether we invoked anything at all. An evaluation that only read fields and
     * folded operators cannot have changed the program; one that invoked a method might have,
     * and the session must not claim otherwise. Sprint-24 audit (T1.15).</p>
     */
    private boolean invokedMethod;

    public JdiEvaluator(ThreadReference thread, int frameIndex) {
        this.thread = thread;
        this.frameIndex = frameIndex;
        this.vm = thread.virtualMachine();
    }

    /** Whether {@link #evaluate} ran any code inside the target JVM. */
    public boolean invokedMethod() {
        return invokedMethod;
    }

    /** Parse and evaluate. The result is a live JDI value in the target JVM. */
    public Value evaluate(String expression) throws EvalException {
        Expression ast = parse(expression);
        Object result = eval(ast);
        if (result instanceof ReferenceType type) {
            throw new EvalException("EVALUATION_NOT_A_VALUE",
                "'" + expression + "' names the type " + type.name() + ", not a value.");
        }
        return (Value) result;
    }

    private Expression parse(String expression) throws EvalException {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_EXPRESSION);
        parser.setSource(expression.toCharArray());
        Object node = parser.createAST(null);
        if (!(node instanceof Expression parsed)) {
            throw new EvalException("EVALUATION_PARSE_ERROR",
                "'" + expression + "' is not a Java expression.");
        }
        return parsed;
    }

    /** Re-fetched every time: an invocation resumes the thread and invalidates frames. */
    private StackFrame frame() throws EvalException {
        try {
            return thread.frame(frameIndex);
        } catch (Exception e) {
            throw new EvalException("EVALUATION_FRAME_GONE",
                "The frame is no longer available (the thread moved on): " + e.getMessage());
        }
    }

    /** Returns a {@link Value}, or a {@link ReferenceType} when the node names a type. */
    private Object eval(Expression node) throws EvalException {
        if (node instanceof ParenthesizedExpression parens) {
            return eval(parens.getExpression());
        }
        if (node instanceof NumberLiteral literal) {
            return numberLiteral(literal.getToken());
        }
        if (node instanceof StringLiteral literal) {
            return vm.mirrorOf(literal.getLiteralValue());
        }
        if (node instanceof BooleanLiteral literal) {
            return vm.mirrorOf(literal.booleanValue());
        }
        if (node instanceof CharacterLiteral literal) {
            return vm.mirrorOf(literal.charValue());
        }
        if (node instanceof NullLiteral) {
            return null;
        }
        if (node instanceof ThisExpression) {
            ObjectReference self = frame().thisObject();
            if (self == null) {
                throw new EvalException("EVALUATION_NO_THIS",
                    "This frame is static — there is no 'this'.");
            }
            return self;
        }
        if (node instanceof SimpleName name) {
            return resolveName(name.getIdentifier());
        }
        if (node instanceof QualifiedName qualified) {
            return resolveQualified(qualified);
        }
        if (node instanceof FieldAccess access) {
            Object target = eval(access.getExpression());
            return fieldOf(target, access.getName().getIdentifier());
        }
        if (node instanceof MethodInvocation invocation) {
            return invoke(invocation);
        }
        if (node instanceof InfixExpression infix) {
            return infix(infix);
        }
        if (node instanceof PrefixExpression prefix) {
            return prefix(prefix);
        }
        if (node instanceof ConditionalExpression conditional) {
            return asBoolean(value(eval(conditional.getExpression())))
                ? eval(conditional.getThenExpression())
                : eval(conditional.getElseExpression());
        }
        if (node instanceof ArrayAccess access) {
            Value array = value(eval(access.getArray()));
            Value index = value(eval(access.getIndex()));
            if (!(array instanceof ArrayReference reference)) {
                throw new EvalException("EVALUATION_NOT_AN_ARRAY",
                    "The expression left of '[' is not an array.");
            }
            int i = (int) asLong(index);
            if (i < 0 || i >= reference.length()) {
                throw new EvalException("EVALUATION_INDEX_OUT_OF_BOUNDS",
                    "index " + i + " is outside [0," + reference.length() + ")");
            }
            return reference.getValue(i);
        }
        if (node instanceof InstanceofExpression check) {
            Value left = value(eval(check.getLeftOperand()));
            String typeName = check.getRightOperand().toString();
            return vm.mirrorOf(isInstance(left, typeName));
        }
        if (node instanceof CastExpression cast) {
            // A reference cast is a no-op on a live value; a primitive cast converts.
            Value inner = value(eval(cast.getExpression()));
            return castTo(cast.getType().toString(), inner);
        }
        throw new EvalException("EVALUATION_UNSUPPORTED",
            "This evaluator does not implement " + node.getClass().getSimpleName()
                + " (in: " + node + "). Supported: literals, names, field access, method "
                + "invocation, arithmetic and comparison, instanceof, casts, array access, "
                + "and the conditional operator.");
    }

    // ---------------------------------------------------------------- names

    private Object resolveName(String name) throws EvalException {
        StackFrame frame = frame();

        // A local wins — it is what the name means at this point in the program.
        try {
            LocalVariable local = frame.visibleVariableByName(name);
            if (local != null) {
                return frame.getValue(local);
            }
        } catch (Exception e) {
            // Compiled without -g: locals are simply not there. Fall through and try
            // fields — but do NOT pretend the local did not exist.
        }

        ObjectReference self = frame.thisObject();
        if (self != null) {
            Field field = self.referenceType().fieldByName(name);
            if (field != null) {
                return self.getValue(field);
            }
        }
        ReferenceType declaring = frame.location().declaringType();
        Field staticField = declaring.fieldByName(name);
        if (staticField != null) {
            return declaring.getValue(staticField);
        }
        ReferenceType type = findType(name);
        if (type != null) {
            return type;
        }
        throw new EvalException("EVALUATION_UNKNOWN_NAME",
            "'" + name + "' is not a visible local, field, or type in this frame."
                + (localsAreAbsent(frame)
                    ? " (This frame has no local-variable table — the target was compiled "
                        + "without -g, so locals cannot be read by name.)"
                    : ""));
    }

    private boolean localsAreAbsent(StackFrame frame) {
        try {
            return frame.visibleVariables().isEmpty();
        } catch (Exception e) {
            return true;
        }
    }

    private Object resolveQualified(QualifiedName qualified) throws EvalException {
        // Either a type's static field (java.lang.System.out) or a field chain (a.b.c).
        ReferenceType type = findType(qualified.getFullyQualifiedName());
        if (type != null) {
            return type;
        }
        Object target;
        try {
            target = eval(qualified.getQualifier());
        } catch (EvalException e) {
            ReferenceType qualifier = findType(qualified.getQualifier().getFullyQualifiedName());
            if (qualifier == null) {
                throw e;
            }
            target = qualifier;
        }
        return fieldOf(target, qualified.getName().getIdentifier());
    }

    private Object fieldOf(Object target, String fieldName) throws EvalException {
        if (target instanceof ReferenceType type) {
            Field field = type.fieldByName(fieldName);
            if (field == null || !field.isStatic()) {
                throw new EvalException("EVALUATION_UNKNOWN_FIELD",
                    type.name() + " has no static field '" + fieldName + "'.");
            }
            return type.getValue(field);
        }
        if (target instanceof ArrayReference array && "length".equals(fieldName)) {
            return vm.mirrorOf(array.length());
        }
        if (!(target instanceof ObjectReference object)) {
            throw new EvalException("EVALUATION_NULL_TARGET",
                "Cannot read field '" + fieldName + "' — the target is null or a primitive.");
        }
        Field field = object.referenceType().fieldByName(fieldName);
        if (field == null) {
            throw new EvalException("EVALUATION_UNKNOWN_FIELD",
                object.referenceType().name() + " has no field '" + fieldName + "'.");
        }
        return object.getValue(field);
    }

    private ReferenceType findType(String name) {
        List<ReferenceType> exact = vm.classesByName(name);
        if (!exact.isEmpty()) {
            return exact.get(0);
        }
        List<ReferenceType> inJavaLang = vm.classesByName("java.lang." + name);
        if (!inJavaLang.isEmpty()) {
            return inJavaLang.get(0);
        }
        // A simple name of a loaded class, unambiguously (e.g. DebugTarget).
        List<ReferenceType> bySimpleName = vm.allClasses().stream()
            .filter(t -> t.name().endsWith("." + name))
            .limit(2)
            .toList();
        return bySimpleName.size() == 1 ? bySimpleName.get(0) : null;
    }

    // ----------------------------------------------------------- invocation

    private Value invoke(MethodInvocation invocation) throws EvalException {
        String name = invocation.getName().getIdentifier();
        List<Value> args = new ArrayList<>();
        for (Object argument : invocation.arguments()) {
            args.add(value(eval((Expression) argument)));
        }

        Object receiver;
        if (invocation.getExpression() != null) {
            receiver = eval(invocation.getExpression());
        } else {
            ObjectReference self = frame().thisObject();
            receiver = self != null ? self : frame().location().declaringType();
        }

        if (receiver instanceof ObjectReference object) {
            Method method = pick(object.referenceType(), name, args, false);
            return invokeChecked(() -> object.invokeMethod(thread, method, args,
                ObjectReference.INVOKE_SINGLE_THREADED), name);
        }
        if (receiver instanceof ClassType classType) {
            Method method = pick(classType, name, args, true);
            return invokeChecked(() -> classType.invokeMethod(thread, method, args,
                ObjectReference.INVOKE_SINGLE_THREADED), name);
        }
        if (receiver instanceof InterfaceType interfaceType) {
            Method method = pick(interfaceType, name, args, true);
            return invokeChecked(() -> interfaceType.invokeMethod(thread, method, args,
                ObjectReference.INVOKE_SINGLE_THREADED), name);
        }
        throw new EvalException("EVALUATION_NULL_TARGET",
            "Cannot call '" + name + "' — the receiver is null or a primitive.");
    }

    private interface Invocation {
        Value run() throws Exception;
    }

    private Value invokeChecked(Invocation invocation, String name) throws EvalException {
        // The single gate every method invocation passes through — mark BEFORE running, so a
        // call that throws inside the target still counts as having run there. It did.
        invokedMethod = true;
        try {
            return invocation.run();
        } catch (com.sun.jdi.InvocationException e) {
            // The target's own code threw. That is a RESULT, and the caller must see it
            // as one — not as our failure.
            String thrown = e.exception() == null
                ? "an exception" : e.exception().referenceType().name();
            throw new EvalException("EVALUATION_TARGET_THREW",
                name + "() threw " + thrown + " in the target JVM.");
        } catch (Exception e) {
            throw new EvalException("EVALUATION_INVOCATION_FAILED",
                "Calling " + name + "() failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    /**
     * Pick the overload. If more than one survives by name and arity, we REFUSE and
     * list them — picking one by guess is how a debugger tells you a confident lie.
     */
    private Method pick(ReferenceType type, String name, List<Value> args, boolean staticOnly)
            throws EvalException {
        List<Method> candidates = type.methodsByName(name).stream()
            .filter(m -> !staticOnly || m.isStatic())
            .filter(m -> m.argumentTypeNames().size() == args.size())
            .toList();
        if (candidates.isEmpty()) {
            throw new EvalException("EVALUATION_UNKNOWN_METHOD",
                type.name() + " has no " + (staticOnly ? "static " : "") + "method '" + name
                    + "' taking " + args.size() + " argument(s).");
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        List<Method> byType = candidates.stream().filter(m -> argumentsFit(m, args)).toList();
        if (byType.size() == 1) {
            return byType.get(0);
        }
        throw new EvalException("EVALUATION_AMBIGUOUS_OVERLOAD",
            "'" + name + "' is ambiguous here — " + candidates.size() + " overloads take "
                + args.size() + " argument(s): "
                + candidates.stream().map(Method::signature).toList()
                + ". Cast an argument to choose one.");
    }

    private boolean argumentsFit(Method method, List<Value> args) {
        List<String> declared = method.argumentTypeNames();
        for (int i = 0; i < declared.size(); i++) {
            Value arg = args.get(i);
            if (arg == null) {
                continue;   // null fits any reference type
            }
            String want = declared.get(i);
            String have = arg.type() == null ? "" : arg.type().name();
            if (want.equals(have)) {
                continue;
            }
            boolean bothNumeric = isNumeric(want) && arg instanceof PrimitiveValue;
            if (!bothNumeric) {
                return false;
            }
        }
        return true;
    }

    private static boolean isNumeric(String typeName) {
        return Map.of("byte", 1, "short", 1, "char", 1, "int", 1,
            "long", 1, "float", 1, "double", 1).containsKey(typeName);
    }

    // ------------------------------------------------------------ operators

    private Value infix(InfixExpression infix) throws EvalException {
        String operator = infix.getOperator().toString();

        // Short-circuit operators must actually short-circuit: the right side may be
        // exactly the thing that would throw.
        if ("&&".equals(operator) || "||".equals(operator)) {
            boolean left = asBoolean(value(eval(infix.getLeftOperand())));
            if ("&&".equals(operator) && !left) {
                return vm.mirrorOf(false);
            }
            if ("||".equals(operator) && left) {
                return vm.mirrorOf(true);
            }
            return vm.mirrorOf(asBoolean(value(eval(infix.getRightOperand()))));
        }

        Value left = value(eval(infix.getLeftOperand()));
        Value right = value(eval(infix.getRightOperand()));
        Value result = binary(operator, left, right);
        for (Object extended : infix.extendedOperands()) {
            result = binary(operator, result, value(eval((Expression) extended)));
        }
        return result;
    }

    private Value binary(String operator, Value left, Value right) throws EvalException {
        if ("==".equals(operator) || "!=".equals(operator)) {
            boolean equal = identical(left, right);
            return vm.mirrorOf("==".equals(operator) == equal);
        }
        if ("+".equals(operator) && (isString(left) || isString(right))) {
            return vm.mirrorOf(stringOf(left) + stringOf(right));
        }
        if (!(left instanceof PrimitiveValue) || !(right instanceof PrimitiveValue)) {
            throw new EvalException("EVALUATION_BAD_OPERANDS",
                "Operator '" + operator + "' needs primitive operands here.");
        }
        boolean floating = isFloating(left) || isFloating(right);
        if (floating) {
            double a = asDouble(left);
            double b = asDouble(right);
            return switch (operator) {
                case "+" -> vm.mirrorOf(a + b);
                case "-" -> vm.mirrorOf(a - b);
                case "*" -> vm.mirrorOf(a * b);
                case "/" -> vm.mirrorOf(a / b);
                case "%" -> vm.mirrorOf(a % b);
                case "<" -> vm.mirrorOf(a < b);
                case ">" -> vm.mirrorOf(a > b);
                case "<=" -> vm.mirrorOf(a <= b);
                case ">=" -> vm.mirrorOf(a >= b);
                default -> throw unsupportedOperator(operator);
            };
        }
        long a = asLong(left);
        long b = asLong(right);
        boolean wide = isLong(left) || isLong(right);
        return switch (operator) {
            case "+" -> number(a + b, wide);
            case "-" -> number(a - b, wide);
            case "*" -> number(a * b, wide);
            case "/" -> divide(a, b, wide);
            case "%" -> modulo(a, b, wide);
            case "<" -> vm.mirrorOf(a < b);
            case ">" -> vm.mirrorOf(a > b);
            case "<=" -> vm.mirrorOf(a <= b);
            case ">=" -> vm.mirrorOf(a >= b);
            case "&" -> number(a & b, wide);
            case "|" -> number(a | b, wide);
            case "^" -> number(a ^ b, wide);
            case "<<" -> number(a << b, wide);
            case ">>" -> number(a >> b, wide);
            case ">>>" -> number(a >>> b, wide);
            default -> throw unsupportedOperator(operator);
        };
    }

    private Value divide(long a, long b, boolean wide) throws EvalException {
        if (b == 0) {
            throw new EvalException("EVALUATION_ARITHMETIC", "division by zero");
        }
        return number(a / b, wide);
    }

    private Value modulo(long a, long b, boolean wide) throws EvalException {
        if (b == 0) {
            throw new EvalException("EVALUATION_ARITHMETIC", "division by zero");
        }
        return number(a % b, wide);
    }

    private EvalException unsupportedOperator(String operator) {
        return new EvalException("EVALUATION_UNSUPPORTED",
            "Operator '" + operator + "' is not implemented.");
    }

    private Value number(long result, boolean wide) {
        return wide ? vm.mirrorOf(result) : vm.mirrorOf((int) result);
    }

    private Value prefix(PrefixExpression prefix) throws EvalException {
        Value operand = value(eval(prefix.getOperand()));
        String operator = prefix.getOperator().toString();
        return switch (operator) {
            case "!" -> vm.mirrorOf(!asBoolean(operand));
            case "-" -> isFloating(operand)
                ? vm.mirrorOf(-asDouble(operand))
                : number(-asLong(operand), isLong(operand));
            case "+" -> operand;
            case "~" -> number(~asLong(operand), isLong(operand));
            default -> throw unsupportedOperator(operator);
        };
    }

    private Value castTo(String typeName, Value value) throws EvalException {
        if (!(value instanceof PrimitiveValue)) {
            return value;   // a reference cast changes nothing about the live object
        }
        return switch (typeName) {
            case "int" -> vm.mirrorOf((int) asLong(value));
            case "long" -> vm.mirrorOf(asLong(value));
            case "short" -> vm.mirrorOf((short) asLong(value));
            case "byte" -> vm.mirrorOf((byte) asLong(value));
            case "char" -> vm.mirrorOf((char) asLong(value));
            case "double" -> vm.mirrorOf(asDouble(value));
            case "float" -> vm.mirrorOf((float) asDouble(value));
            case "boolean" -> vm.mirrorOf(asBoolean(value));
            default -> value;
        };
    }

    private boolean isInstance(Value value, String typeName) {
        if (!(value instanceof ObjectReference object)) {
            return false;
        }
        ReferenceType wanted = findType(typeName);
        if (wanted == null) {
            return false;
        }
        ReferenceType actual = object.referenceType();
        if (actual.equals(wanted)) {
            return true;
        }
        if (actual instanceof ClassType classType) {
            for (ClassType superType = classType.superclass(); superType != null;
                    superType = superType.superclass()) {
                if (superType.equals(wanted)) {
                    return true;
                }
            }
            return classType.allInterfaces().contains(wanted);
        }
        return false;
    }

    // -------------------------------------------------------------- helpers

    private static Value value(Object evaluated) throws EvalException {
        if (evaluated instanceof ReferenceType type) {
            throw new EvalException("EVALUATION_NOT_A_VALUE",
                type.name() + " is a type, not a value.");
        }
        return (Value) evaluated;
    }

    private Value numberLiteral(String token) throws EvalException {
        String text = token.replace("_", "");
        try {
            if (text.endsWith("L") || text.endsWith("l")) {
                return vm.mirrorOf(Long.decode(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("f") || text.endsWith("F")) {
                return vm.mirrorOf(Float.parseFloat(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("d") || text.endsWith("D")) {
                return vm.mirrorOf(Double.parseDouble(text.substring(0, text.length() - 1)));
            }
            if (text.contains(".") || text.contains("e") || text.contains("E")) {
                return vm.mirrorOf(Double.parseDouble(text));
            }
            return vm.mirrorOf(Integer.decode(text));
        } catch (NumberFormatException e) {
            throw new EvalException("EVALUATION_PARSE_ERROR",
                "'" + token + "' is not a number this evaluator understands.");
        }
    }

    private boolean identical(Value left, Value right) throws EvalException {
        if (left == null || right == null) {
            return left == right;
        }
        if (left instanceof PrimitiveValue && right instanceof PrimitiveValue) {
            if (isFloating(left) || isFloating(right)) {
                return asDouble(left) == asDouble(right);
            }
            if (left.type().name().equals("boolean") || right.type().name().equals("boolean")) {
                return asBoolean(left) == asBoolean(right);
            }
            return asLong(left) == asLong(right);
        }
        if (left instanceof ObjectReference a && right instanceof ObjectReference b) {
            return a.uniqueID() == b.uniqueID();   // '==' is identity, as in Java
        }
        return false;
    }

    private static boolean isString(Value value) {
        return value instanceof StringReference;
    }

    private String stringOf(Value value) throws EvalException {
        if (value == null) {
            return "null";
        }
        if (value instanceof StringReference string) {
            return string.value();
        }
        if (value instanceof PrimitiveValue primitive) {
            return String.valueOf(JdiValues.primitiveOf(primitive));
        }
        if (value instanceof ObjectReference object) {
            // The target's own toString(), invoked in the target — the only rendering
            // that is actually true of that object.
            List<Method> toString = object.referenceType().methodsByName("toString");
            for (Method method : toString) {
                if (method.argumentTypeNames().isEmpty()) {
                    Value rendered = invokeChecked(() -> object.invokeMethod(thread, method,
                        List.of(), ObjectReference.INVOKE_SINGLE_THREADED), "toString");
                    return rendered instanceof StringReference string
                        ? string.value() : String.valueOf(rendered);
                }
            }
            return object.referenceType().name() + "@" + object.uniqueID();
        }
        return String.valueOf(value);
    }

    private static boolean isFloating(Value value) {
        String name = value.type() == null ? "" : value.type().name();
        return "float".equals(name) || "double".equals(name);
    }

    private static boolean isLong(Value value) {
        return value.type() != null && "long".equals(value.type().name());
    }

    private static boolean asBoolean(Value value) throws EvalException {
        if (value instanceof com.sun.jdi.BooleanValue bool) {
            return bool.value();
        }
        throw new EvalException("EVALUATION_BAD_OPERANDS",
            "Expected a boolean, got " + (value == null ? "null" : value.type().name()) + ".");
    }

    private static long asLong(Value value) throws EvalException {
        if (value instanceof PrimitiveValue primitive && !(value instanceof com.sun.jdi.BooleanValue)) {
            return primitive.longValue();
        }
        throw new EvalException("EVALUATION_BAD_OPERANDS",
            "Expected a number, got " + (value == null ? "null" : value.type().name()) + ".");
    }

    private static double asDouble(Value value) throws EvalException {
        if (value instanceof PrimitiveValue primitive && !(value instanceof com.sun.jdi.BooleanValue)) {
            return primitive.doubleValue();
        }
        throw new EvalException("EVALUATION_BAD_OPERANDS",
            "Expected a number, got " + (value == null ? "null" : value.type().name()) + ".");
    }
}
