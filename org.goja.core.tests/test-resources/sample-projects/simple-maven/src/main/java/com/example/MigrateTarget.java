package com.example;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fixture for apply_null_annotations migrate (Sprint 15b). Uses the JetBrains
 * family (Nullable / NotNull) — the simple 1:1 pair that migrates cleanly to
 * JSpecify (Nullable / NonNull). JetBrains is not on the classpath; source-only.
 */
class MigrateTarget {

    @Nullable
    String a(@NotNull String x) {
        return x;
    }
}
