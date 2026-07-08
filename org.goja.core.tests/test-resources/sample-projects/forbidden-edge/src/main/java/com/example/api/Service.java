package com.example.api;

import com.example.internal.Helper;

/**
 * Sits in the {@code api} layer but imports the {@code internal} layer — the
 * forbidden dependency direction the P1-d rule catches.
 */
public class Service {

    private final Helper helper = new Helper();

    public int run() {
        return helper.value();
    }
}
