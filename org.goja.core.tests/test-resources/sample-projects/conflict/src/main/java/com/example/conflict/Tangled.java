package com.example.conflict;

/**
 * One method flagged by two Fowler detectors at one location (with threshold=3):
 * long_method (body over the LOC cutoff) AND long_parameter_list (4 params over the
 * cutoff), both anchored at the declaration line of {@code knot}.
 */
public class Tangled {
    int knot(int a, int b, int c, int d) {
        int x = a + b;
        int y = c + d;
        int z = x + y;
        int w = z + a;
        return w + b;
    }
}
