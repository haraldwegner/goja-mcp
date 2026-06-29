package com.example;

/**
 * Sprint 17 fixture — Temporary Field detector.
 * {@code temp} is referenced by only {@code compute} → flagged. {@code shared}
 * is referenced by two methods → not flagged.
 */
public class TemporaryFieldTargets {

    private int temp;
    private int shared;

    int compute() {
        temp = 5;
        return temp * 2;
    }

    int reader() {
        return shared;
    }

    int writer() {
        shared = 7;
        return shared;
    }
}
