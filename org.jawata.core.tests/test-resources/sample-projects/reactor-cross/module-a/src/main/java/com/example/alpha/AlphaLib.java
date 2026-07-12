package com.example.alpha;

/** Module-a production code exercised by module-b's tests (Stage 3). */
public class AlphaLib {

    public int magic() {
        return 42;
    }

    public String tag(String input) {
        return "alpha:" + input;
    }
}
