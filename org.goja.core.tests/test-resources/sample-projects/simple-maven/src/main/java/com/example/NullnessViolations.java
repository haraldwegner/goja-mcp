package com.example;

/**
 * Fixture for analyze_nullness find_violations (Sprint 15b). Flow-based null
 * bugs that JDT's compiler null analysis flags WITHOUT any nullness annotations
 * on the classpath.
 */
public class NullnessViolations {

    public int definiteDeref() {
        String s = null;
        return s.length(); // definite null pointer access
    }

    public int potentialDeref(boolean b) {
        String s = b ? "x" : null;
        return s.length(); // potential null pointer access
    }
}
