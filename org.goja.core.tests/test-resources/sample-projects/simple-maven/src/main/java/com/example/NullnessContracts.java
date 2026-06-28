package com.example;

import java.util.Objects;

/**
 * Fixture for analyze_nullness infer_contracts (Sprint 15b). A requireNonNull
 * param (→ @NonNull), a return-null method (→ @Nullable), and a sibling type
 * marked @Generated whose member must be skipped (risky signal).
 */
public class NullnessContracts {

    public String requireParam(String key) {
        Objects.requireNonNull(key);
        return key;
    }

    public String maybeNull(boolean b) {
        if (b) {
            return "x";
        }
        return null;
    }
}

/** Risky: a generated type — infer_contracts must emit NO contract here. */
@Generated
class GeneratedHolder {
    String alsoNull() {
        return null;
    }
}
