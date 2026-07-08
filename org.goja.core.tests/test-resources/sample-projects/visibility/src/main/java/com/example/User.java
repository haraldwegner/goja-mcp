package com.example;

/**
 * Same-package caller of {@link Widget}. Because it lives in {@code com.example}
 * too, reducing a called method to package-private still compiles here — the
 * reference-impact list reports these call sites without breaking the build.
 */
class User {

    void use() {
        Widget w = new Widget();
        w.open("x");
        w.hidden();
    }
}
