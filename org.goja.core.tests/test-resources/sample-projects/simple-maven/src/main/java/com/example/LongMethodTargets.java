package com.example;

/**
 * Sprint 17 fixture — Long Method detector targets.
 * <ul>
 *   <li>{@code longOne} — long but simple (LOC &gt; 40, cyclomatic 1): LOC-gated.</li>
 *   <li>{@code branchy} — short but complex (cyclomatic &gt; 10): CC-gated.</li>
 *   <li>{@code clean} — short and simple: must NOT be flagged.</li>
 * </ul>
 */
public class LongMethodTargets {

    // Long but straight-line (cyclomatic complexity 1) — flagged by the LOC cutoff,
    // and NOT flagged once the threshold is raised above its length.
    public int longOne() {
        int a = 0;
        a += 1;
        a += 2;
        a += 3;
        a += 4;
        a += 5;
        a += 6;
        a += 7;
        a += 8;
        a += 9;
        a += 10;
        a += 11;
        a += 12;
        a += 13;
        a += 14;
        a += 15;
        a += 16;
        a += 17;
        a += 18;
        a += 19;
        a += 20;
        a += 21;
        a += 22;
        a += 23;
        a += 24;
        a += 25;
        a += 26;
        a += 27;
        a += 28;
        a += 29;
        a += 30;
        a += 31;
        a += 32;
        a += 33;
        a += 34;
        a += 35;
        a += 36;
        a += 37;
        a += 38;
        a += 39;
        a += 40;
        a += 41;
        a += 42;
        a += 43;
        a += 44;
        a += 45;
        a += 46;
        a += 47;
        a += 48;
        a += 49;
        a += 50;
        a += 51;
        a += 52;
        a += 53;
        a += 54;
        a += 55;
        a += 56;
        a += 57;
        a += 58;
        a += 59;
        a += 60;
        a += 61;
        a += 62;
        a += 63;
        a += 64;
        a += 65;
        return a;
    }

    // Short, but highly branchy (cyclomatic complexity > 15) — always flagged.
    public int branchy(int x) {
        int r = 0;
        if (x > 1) { r++; }
        if (x > 2) { r++; }
        if (x > 3) { r++; }
        if (x > 4) { r++; }
        if (x > 5) { r++; }
        if (x > 6) { r++; }
        if (x > 7) { r++; }
        if (x > 8) { r++; }
        if (x > 9) { r++; }
        if (x > 10) { r++; }
        if (x > 11) { r++; }
        if (x > 12) { r++; }
        if (x > 13) { r++; }
        if (x > 14) { r++; }
        if (x > 15) { r++; }
        if (x > 16) { r++; }
        return r;
    }

    // Short and simple — must never be flagged.
    public int clean(int x) {
        return x * 2;
    }
}
