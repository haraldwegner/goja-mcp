package com.example;

/**
 * Fixture for find_modernization batch-2 kinds (Sprint 15). The candidates are
 * the sibling top-level classes below; this public type is just the file anchor.
 */
public class RecordSealedTargets {
}

/** class_to_record candidate: immutable data class, accessors only. */
class PointData {
    private final int x;
    private final int y;

    PointData(int x, int y) {
        this.x = x;
        this.y = y;
    }

    int getX() {
        return x;
    }

    int getY() {
        return y;
    }
}

/** optional candidate: reference return type that returns null. */
class NullableFinder {
    String findOrNull(boolean present) {
        if (present) {
            return "value";
        }
        return null;
    }
}

/** sealed candidate: abstract base class. */
abstract class ShapeBase {
    abstract double area();
}
