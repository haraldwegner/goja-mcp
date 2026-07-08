package com.example;

/**
 * A plain class under {@code src/test/java} with no JUnit dependency, so it
 * compiles against the JRE alone. Its purpose is to give the {@code src/test/}
 * source root a real, compile-clean occupant, so scope='test' vs scope='main'
 * marker classification (compile_workspace bugs.md #9) can be exercised by
 * dropping a broken file into each root at test time (Sprint 22a P0-a).
 */
public class CleanSupport {

    public boolean isReady() {
        return true;
    }
}
