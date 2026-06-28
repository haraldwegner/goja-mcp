package com.example;

/**
 * Fixture for analyze_javadocs (Sprint 15a). Carries a structured-contract
 * method, a deprecated method, a free-text-only doc, and an undocumented method.
 */
public class JavadocTargets {

    /**
     * Computes a discounted total for the given line items.
     *
     * @param subtotal the pre-discount subtotal, must be non-negative
     * @param rate the discount rate between 0 and 1
     * @return the discounted total, never negative
     * @throws IllegalArgumentException if rate is outside [0,1]
     */
    public double discountedTotal(double subtotal, double rate) throws IllegalArgumentException {
        if (rate < 0 || rate > 1) {
            throw new IllegalArgumentException("rate out of range");
        }
        return subtotal * (1 - rate);
    }

    /**
     * Legacy rounding helper.
     *
     * @deprecated use {@link #discountedTotal(double, double)} instead; this
     *             rounds before discounting and is kept only for old callers.
     */
    @Deprecated
    public double oldRound(double value) {
        return Math.floor(value);
    }

    /**
     * Totals must preserve line-item rounding before invoice-level rounding.
     */
    public void invariantNote() {
        // free-text-only Javadoc (a domain invariant), no structured tags
    }

    public int undocumented(int x) {
        return x + 1;
    }

    // Trivial getter, no Javadoc — validate must NOT flag this (no missing-comment spam).
    public String getName() {
        return "JavadocTargets";
    }

    /**
     * Increments by the given step.
     *
     * @return the incremented value
     */
    public int missingParam(int count) {
        return count + 1;
    }

    /**
     * Helper that delegates.
     *
     * @see Nonexistent#nope broken reference on purpose
     */
    public void brokenLink() {
        // intentionally references an unresolvable type in @see
    }
}
