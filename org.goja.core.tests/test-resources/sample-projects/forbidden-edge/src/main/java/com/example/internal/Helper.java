package com.example.internal;

/**
 * The {@code internal} layer. Nothing here imports {@code api}, so the reverse
 * rule (from=internal, forbidden=api) reports no violation.
 */
public class Helper {

    public int value() {
        return 42;
    }
}
