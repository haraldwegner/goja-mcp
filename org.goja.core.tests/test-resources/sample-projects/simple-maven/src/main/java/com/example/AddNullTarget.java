package com.example;

/**
 * Fixture for apply_null_annotations add (Sprint 15b). A package-private method
 * (annotatable without the public-API opt-in) and a public method (guarded).
 */
class AddNullTarget {

    String find(String key) {
        return key;
    }

    public String pub(String x) {
        return x;
    }
}
