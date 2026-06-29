package com.example;

/**
 * Sprint 17 fixture — Refused Bequest detector.
 * {@code Refuser.doIt} overrides only to throw UnsupportedOperationException
 * → flagged. {@code Honorer.keepIt} honours its inheritance → not flagged.
 */
public class RefusedBequestTargets {
}

class BequestBase {
    void doIt() {
    }

    void keepIt() {
    }
}

class Refuser extends BequestBase {
    @Override
    void doIt() {
        throw new UnsupportedOperationException("not supported");
    }
}

class Honorer extends BequestBase {
    @Override
    void keepIt() {
        int x = 1;
        int y = x + 1;
    }
}
