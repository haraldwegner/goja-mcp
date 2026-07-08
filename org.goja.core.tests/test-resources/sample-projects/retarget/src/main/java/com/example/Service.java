package com.example;

/**
 * {@link #v1(String)} is the method whose call sites a retarget redirects;
 * {@link #v2(String)} is its same-signature replacement. Both declarations
 * remain after the retarget (retarget rewrites calls only) so the project
 * stays compiling (Sprint 22a retarget_call_sites).
 */
public class Service {

    public String v1(String s) {
        return "v1:" + s;
    }

    public String v2(String s) {
        return "v2:" + s;
    }
}
