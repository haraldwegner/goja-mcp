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

// A fluent builder: its setters chain by returning `this`. Some touch a single field,
// some touch a disjoint pair (withYZ vs withMeta) — under a naive LCOM they look like
// separate responsibilities, but a builder is ONE responsibility. v1.3.2: any method
// whose last statement is `return this` is excluded, so this must NOT be flagged
// (the v1.3.1 2-statement rule missed the multi-field forms — SymbolFact.Builder's
// scope()/source()/addEvidence()).
class PointBuilder {
    private int x;
    private int y;
    private int z;
    private String label;
    private String unit;
    private int a;
    private int b;

    PointBuilder withX(int x) {          // single field, 2 statements
        this.x = x;
        return this;
    }

    PointBuilder withYZ(int y, int z) {  // two fields, 3 statements — the v1.3.1 miss
        this.y = y;
        this.z = z;
        return this;
    }

    PointBuilder withMeta(String label, String unit) {  // DISJOINT field pair {label,unit}
        this.label = label;
        this.unit = unit;
        return this;
    }

    PointBuilder withAB(int a, int b) {  // a third DISJOINT pair {a,b}
        this.a = a;
        this.b = b;
        return this;
    }

    // Under the old 2-statement rule, withYZ/withMeta/withAB + build are 4 candidates
    // that split into disjoint clusters -> PointBuilder WOULD be flagged. The v1.3.2
    // `return this` rule excludes all three fluent setters, so it is not.
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
