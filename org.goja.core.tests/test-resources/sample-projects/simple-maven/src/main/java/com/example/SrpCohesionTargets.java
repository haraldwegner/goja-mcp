package com.example;

/**
 * Sprint 20 fixture — SRP low-cohesion (LCOM) detector.
 * {@code TwoJobs} splits into two disjoint field-usage clusters ({a} and {b})
 * → flagged. {@code Cohesive}'s methods all touch the shared {x,y} state (one
 * cluster) → not flagged.
 */
public class SrpCohesionTargets {
}

class TwoJobs {
    private int a;
    private int b;

    int incA() {
        a++;
        return a;
    }

    int doubleA() {
        a = a * 2;
        return a;
    }

    int incB() {
        b++;
        return b;
    }

    int doubleB() {
        b = b * 2;
        return b;
    }
}

// A fluent builder: each withX() touches one field, but it's ONE responsibility.
// v1.3.1: fluent setters are excluded, so this must NOT be flagged as low-cohesion.
class PointBuilder {
    private int x;
    private int y;
    private int z;

    PointBuilder withX(int x) {
        this.x = x;
        return this;
    }

    PointBuilder withY(int y) {
        this.y = y;
        return this;
    }

    PointBuilder withZ(int z) {
        this.z = z;
        return this;
    }

    int build() {
        return x + y + z;
    }
}

class Cohesive {
    private int x;
    private int y;

    int sum() {
        return x + y;
    }

    int scale(int f) {
        x = x * f;
        y = y * f;
        return x + y;
    }

    int reset() {
        x = 0;
        y = 0;
        return 0;
    }

    int report() {
        return sum() + x;
    }
}
