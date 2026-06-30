package com.example;

/**
 * Sprint 20 fixture — Liskov Substitution detector.
 * {@code Rejecter} overrides {@code op} (throws UnsupportedOperationException)
 * and {@code compute} (throws IllegalStateException) → both flagged by lsp
 * (refused_bequest catches only the UOE one). {@code Honorer.run} is a real
 * override → not flagged.
 */
public class LspTargets {
}

class LspBase {
    void op() {
    }

    void run() {
    }

    int compute() {
        return 1;
    }
}

class Rejecter extends LspBase {
    @Override
    void op() {
        throw new UnsupportedOperationException("not here");
    }

    @Override
    int compute() {
        throw new IllegalStateException("nope");
    }
}

class Honorer extends LspBase {
    @Override
    void run() {
        int x = 1;
        int y = x + 1;
    }
}
