package org.jawata.core;

/**
 * The Java model FAILED while looking up a type by name — which is a different
 * fact from "the type does not exist", and the two must never be conflated.
 *
 * <p>The pre-fix behaviour caught {@code JavaModelException} inside the lookup
 * and returned {@code null}, which every caller renders as
 * {@code SYMBOL_NOT_FOUND: Type not found: X}. Under workspace churn that
 * produced exactly this lie in the wild: {@code find_pattern_usages} answering
 * "Type not found: org.junit.jupiter.api.Test" about a type sitting on the
 * project classpath the whole time — an absence claimed from a lookup that
 * never completed.</p>
 *
 * <p>{@code null} from a lookup now means the model answered and the answer is
 * "no such type". THIS means the model did not answer.</p>
 */
public class TypeLookupException extends RuntimeException {

    public TypeLookupException(String typeName, Throwable cause) {
        super("Looking up type '" + typeName + "' FAILED ("
            + cause.getClass().getSimpleName()
            + (cause.getMessage() != null ? ": " + cause.getMessage() : "")
            + "). This is NOT 'the type does not exist' — the Java model could not answer."
            + " Run refresh_workspace and retry ONCE; if it fails again, the workspace is"
            + " unhealthy — tell the user instead of working around it.",
            cause);
    }
}
