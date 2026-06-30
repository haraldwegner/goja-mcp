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
