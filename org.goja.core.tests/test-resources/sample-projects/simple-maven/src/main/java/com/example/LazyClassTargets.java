package com.example;

/**
 * Sprint 17 fixture — Lazy Class detector.
 * {@code LazyLeaf} is empty, standalone, unreferenced → flagged. {@code BusyLeaf}
 * has more than the threshold methods → not flagged.
 */
public class LazyClassTargets {
}

class LazyLeaf {
}

class BusyLeaf {
    public int one() {
        return 1;
    }

    public int two() {
        return 2;
    }

    public int three() {
        return 3;
    }
}
