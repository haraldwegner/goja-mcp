package com.example;

/** Sprint 25 extract_superclass JDT-mode fixture: a field + a method the sibling lacks (the conservative mode refuses both). */
public class ShapeCircle {

    final String label = "shape";

    public String describe() {
        return "a " + label;
    }

    public double area(double r) {
        return Math.PI * r * r;
    }
}
