package com.example.cov;

/**
 * NON-public class in a freely-renamable file (no public-class/filename
 * coupling) — the pure-rename (-M) proof: moving this file unchanged must
 * never turn its covered lines into false delta gaps.
 */
class CovHelper {

    static String help() {
        return "helped";
    }
}
