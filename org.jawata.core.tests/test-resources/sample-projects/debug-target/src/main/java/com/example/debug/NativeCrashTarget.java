package com.example.debug;

import java.lang.reflect.Field;
import sun.misc.Unsafe;

/**
 * Sprint 24 (D14/Stage 19) fixture — deterministically SIGSEGVs from inside a
 * named Java method, via an out-of-bounds {@code Unsafe} write. HotSpot's own
 * hs_err report already correlates the crashing native frame (resolved, in
 * {@code libjvm.so}, to {@code Unsafe_PutInt}) with the Java frames above it
 * (this class's own method names) — grounded empirically (Sprint 24 Stage 19)
 * against a real generated crash, not assumed from documentation.
 *
 * <p>Runs as a plain subprocess (never inside the test JVM itself — this
 * really does bring the process down).</p>
 */
public final class NativeCrashTarget {

    public static void main(String[] args) throws Exception {
        Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Unsafe unsafe = (Unsafe) theUnsafe.get(null);
        triggerCrash(unsafe);
    }

    /** The named method whose frame must appear in the crash's Java stack. */
    static void triggerCrash(Unsafe unsafe) {
        unsafe.putInt(0xBADL, 42);
    }

    private NativeCrashTarget() {
    }
}
