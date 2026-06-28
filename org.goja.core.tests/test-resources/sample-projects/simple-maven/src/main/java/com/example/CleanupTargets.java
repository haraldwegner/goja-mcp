package com.example;

/**
 * Fixture for apply_cleanup (Sprint 15). Carries clear targets for both kinds
 * plus a negative case (a reassigned local) that must NOT be marked final.
 */
public class CleanupTargets {

    /** add_final: `value` and `doubled` are never reassigned → both finalizable. */
    int compute(int value) {
        int doubled = value * 2;
        return doubled + 1;
    }

    /** add_final negative: `acc` is reassigned in the loop → must stay non-final. */
    int sum(int n) {
        int acc = 0;
        for (int i = 0; i < n; i++) {
            acc += i;
        }
        return acc;
    }

    /** redundant_modifiers: every modifier below is implicit on an interface. */
    interface Service {
        public static final int LIMIT = 10;

        public abstract void run();
    }
}
