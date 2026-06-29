package com.example;

/**
 * Sprint 17 fixture — Speculative Generality detector.
 * {@code SoleService} has exactly one implementor → flagged. {@code MultiService}
 * has two → not flagged.
 */
public class SpeculativeGeneralityTargets {
}

interface SoleService {
    void op();
}

class SoleServiceImpl implements SoleService {
    public void op() {
    }
}

interface MultiService {
    void op();
}

class MultiA implements MultiService {
    public void op() {
    }
}

class MultiB implements MultiService {
    public void op() {
    }
}
