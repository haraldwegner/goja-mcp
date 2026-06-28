package com.example;

/**
 * Sprint 13 fixture for {@code override_methods} tests. Concrete superclass
 * with two overridable methods. Sibling {@code OverrideTarget} extends this
 * class without overriding either; tests then ask the tool to override one.
 */
public class OverrideBase {

    public String describe() {
        return "base";
    }

    public int countItems() {
        return 0;
    }
}
