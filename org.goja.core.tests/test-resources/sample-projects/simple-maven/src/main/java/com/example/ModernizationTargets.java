package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixture for find_modernization (Sprint 15). One clear candidate per batch-1 kind.
 */
public class ModernizationTargets {

    /** anon_to_lambda: a single-method anonymous Runnable (functional interface). */
    public Runnable makeTask() {
        return new Runnable() {
            @Override
            public void run() {
                System.out.println("run");
            }
        };
    }

    /** switch_to_pattern: a classic switch statement. */
    public String describe(int day) {
        String s;
        switch (day) {
            case 1:
                s = "one";
                break;
            default:
                s = "other";
        }
        return s;
    }

    /** loop_to_stream: an enhanced-for accumulation loop. */
    public List<String> upper(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String x : in) {
            out.add(x.toUpperCase());
        }
        return out;
    }
}
