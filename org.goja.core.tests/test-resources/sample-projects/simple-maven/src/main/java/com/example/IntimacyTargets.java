package com.example;

/**
 * Sprint 17 fixture — Inappropriate Intimacy detector.
 * {@code IntimA} and {@code IntimB} each read/write the other's fields twice
 * → both flagged (default threshold 2 each way). {@code Aloof} touches no one
 * else's fields → not flagged.
 */
public class IntimacyTargets {
}

class IntimA {
    int x;
    IntimB partner;

    void sync() {
        partner.y = this.x;
        int echo = partner.y;
        this.x = echo;
    }
}

class IntimB {
    int y;
    IntimA partner;

    void sync() {
        partner.x = this.y;
        int echo = partner.x;
        this.y = echo;
    }
}

class Aloof {
    int z;

    int own() {
        return z;
    }
}
