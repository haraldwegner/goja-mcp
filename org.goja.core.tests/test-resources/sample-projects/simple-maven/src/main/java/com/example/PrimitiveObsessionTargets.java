package com.example;

/**
 * Sprint 17 fixture — Primitive Obsession detector.
 * {@code coords} (3 int params) → flagged. {@code mixed} (1 of 2 primitive) and
 * {@code typed} (all object params) → not flagged.
 */
public class PrimitiveObsessionTargets {

    public void coords(int x, int y, int z) {
    }

    public void mixed(Wallet w, int a) {
    }

    public void typed(Wallet a, Wallet b, Wallet c) {
    }
}
