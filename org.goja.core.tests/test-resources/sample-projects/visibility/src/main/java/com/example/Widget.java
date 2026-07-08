package com.example;

/**
 * Visibility-change subject (Sprint 22a change_visibility):
 * <ul>
 *   <li>{@link #open(String)} — public, called by {@link User}; reduce to package.</li>
 *   <li>{@link #hidden()} — package-private, called by {@link User}; widen to public.</li>
 *   <li>{@link #solo()} — public, uncalled; reduce to private.</li>
 * </ul>
 */
public class Widget {

    public String open(String s) {
        return s;
    }

    int hidden() {
        return 0;
    }

    public int solo() {
        return 42;
    }
}
