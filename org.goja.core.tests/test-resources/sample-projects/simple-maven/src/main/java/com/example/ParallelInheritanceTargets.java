package com.example;

/**
 * Sprint 17 fixture — Parallel Inheritance detector.
 * The {@code Engine} and {@code Wheel} hierarchies vary together on the
 * {Car, Truck} variants → flagged. The {@code Solo} hierarchy shares no variant
 * → not flagged.
 */
public class ParallelInheritanceTargets {
}

class Engine {
}

class CarEngine extends Engine {
}

class TruckEngine extends Engine {
}

class Wheel {
}

class CarWheel extends Wheel {
}

class TruckWheel extends Wheel {
}

class Solo {
}

class SpecialSolo extends Solo {
}
