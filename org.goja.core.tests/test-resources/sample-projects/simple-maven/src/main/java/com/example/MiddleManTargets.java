package com.example;

/**
 * Sprint 17 fixture — Middle Man detector.
 * {@code Broker} delegates all 3 methods to a field (100% ≥ 50%) → flagged
 * (Remove Middle Man). {@code DoesRealWork} does its own work → not flagged.
 */
public class MiddleManTargets {
}

class RealWorker {
    int compute() {
        return 42;
    }

    int calc() {
        return 7;
    }

    int more() {
        return 9;
    }
}

class Broker {
    private RealWorker worker = new RealWorker();

    int compute() {
        return worker.compute();
    }

    int calc() {
        return worker.calc();
    }

    int more() {
        return worker.more();
    }
}

class DoesRealWork {
    private int state = 0;

    int compute() {
        int a = state + 1;
        int b = a * 2;
        return a + b;
    }

    int calc() {
        return 100;
    }

    int more() {
        int total = 0;
        for (int i = 0; i < 3; i++) {
            total += i;
        }
        return total;
    }
}
