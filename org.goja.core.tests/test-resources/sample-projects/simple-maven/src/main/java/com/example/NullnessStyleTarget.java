package com.example;

import org.jspecify.annotations.Nullable;

/**
 * Fixture for analyze_nullness detect_style (Sprint 15b). Uses the JSpecify
 * family; JSpecify is NOT on the fixture classpath — detection is source-only
 * (import scan), so the unresolved import is intentional and harmless.
 */
public class NullnessStyleTarget {

    @Nullable
    private String cachedKey;

    public String find(@Nullable String key) {
        return key == null ? "" : key;
    }
}
