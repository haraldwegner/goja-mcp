package com.example;

/**
 * Genuinely compile-clean main source. Uses only {@code java.lang} so the JDT
 * Java builder resolves every binding against the JRE container and emits zero
 * problem markers. This is the non-vacuous baseline for the compile_workspace /
 * get_diagnostics gates (Sprint 22a P0-a) — before the buildSpec fix the
 * synthesized project had no Java builder, so "clean → 0 errors" passed
 * vacuously (no builder ran at all).
 */
public class Clean {

    private final String name;

    public Clean(String name) {
        this.name = name;
    }

    public String greet() {
        return "Hello, " + name;
    }

    public int add(int a, int b) {
        return a + b;
    }
}
