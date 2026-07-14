package com.example.debug;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Sprint 24 audit (2026-07-14) fixture — a program in which ONE fully-qualified class name
 * ({@code com.example.debug.DoubleLoaded}) is present under TWO classloaders at once: the
 * application loader, and a child {@link URLClassLoader} over the same classpath that does
 * not delegate to it. This is exactly the shape OSGi produces routinely (the same class in
 * two bundles), and the intended debug target — JATS — is OSGi.
 *
 * <p>It exists so the interactive debugger can be tested for the thing it used to get wrong:
 * {@code vm.classesByName(name).get(0)} silently picked ONE of the two, so redefine patched a
 * coin-flip class and instances counted a coin-flip population, both reporting success. With
 * two live copies present, those actions must REFUSE and name the loaders — not guess.</p>
 */
public final class DoubleLoadTarget {

    private static Object appCopy;
    private static Object childCopy;

    public static void main(String[] args) throws Exception {
        // The application loader's copy.
        appCopy = new DoubleLoaded();

        // A second, independent copy of the SAME class via a child loader over the same
        // classpath, with a NULL parent so it loads its own DoubleLoaded rather than
        // delegating up — two distinct ReferenceTypes, one name.
        String cp = System.getProperty("java.class.path");
        String[] entries = cp.split(java.io.File.pathSeparator);
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            urls[i] = new java.io.File(entries[i]).toURI().toURL();
        }
        URLClassLoader child = new URLClassLoader(urls, null);
        Class<?> other = child.loadClass("com.example.debug.DoubleLoaded");
        childCopy = other.getDeclaredConstructor().newInstance();

        // Idle forever, holding both copies live, until the debugger detaches us.
        while (true) {
            if (appCopy == null || childCopy == null) {
                throw new AssertionError("unreachable, keeps both copies live");
            }
            Thread.sleep(100);
        }
    }

    private DoubleLoadTarget() {
    }
}
