package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 20 fixture — Dependency Inversion detector.
 * {@code items} is a concrete ArrayList used only via List methods → flagged
 * (depend on List). {@code tuned} calls a concrete-only method (ensureCapacity)
 * → not flagged. {@code already} is declared as the interface → not a candidate.
 */
public class DipTargets {

    private ArrayList<String> items = new ArrayList<>();
    private ArrayList<String> tuned = new ArrayList<>();
    private List<String> already = new ArrayList<>();

    void useItems() {
        items.add("x");
        int n = items.size();
        String s = items.get(0);
    }

    void useTuned() {
        tuned.ensureCapacity(10);
        tuned.add("y");
    }

    void useAlready() {
        already.add("z");
    }
}
