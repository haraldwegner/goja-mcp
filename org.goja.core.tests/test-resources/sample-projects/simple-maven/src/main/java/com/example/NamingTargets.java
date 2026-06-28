package com.example;

/**
 * Fixture for analyze_naming (Sprint 15a). Carries constants that are mostly
 * UPPER_SNAKE_CASE with one deliberate violation, so the inferred constant
 * convention has a recorded exception.
 */
public class NamingTargets {

    public static final int MAX_SIZE = 10;
    public static final int MIN_SIZE = 1;
    public static final String DEFAULT_NAME = "x";
    public static final int BUFFER_LIMIT = 64;
    // Deliberate violation: a constant not in UPPER_SNAKE_CASE.
    public static final int badConstant = 5;

    public int compute(int value) {
        return value + MAX_SIZE;
    }
}
