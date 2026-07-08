package com.example;

/**
 * The real {@code com.example.Widget} in the main source root. Its
 * {@link #poke()} method is the call-hierarchy target the P0-c test uses to
 * prove the stale scratch copy under {@code .claude/.edit-baks/} does not
 * introduce a phantom duplicate definition.
 */
public class Widget {

    private int state;

    public void poke() {
        state++;
    }

    public int state() {
        return state;
    }
}
