package org.jawata.mcp.learn;

import org.jawata.mcp.knowledge.LearnerEventStore;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The per-situation enforcement dial (Sprint 26, D3): the Sprint-22 hand
 * rules are the cold-start levels; recorded consequence signals move each
 * situation's dial — slips that later cost something TIGHTEN it, legitimate
 * relax labels (a jawata failure, a correct fallback) LOOSEN it. The learned
 * policy model refines this dial later; the dial itself never disappears.
 */
public final class PolicyDial {

    public enum Level { BLOCK, STEER, SILENT }

    /** Signals a situation can record. */
    public enum Signal { SLIP, RELAX, CLEAN }

    static final int TIGHTEN_AT = 3;   // net slips that raise a level
    static final int RELAX_AT = 3;     // net relaxes that lower a level

    private final Map<String, int[]> counters = new LinkedHashMap<>(); // {slips, relaxes}
    private final LearnerEventStore store;

    public PolicyDial(LearnerEventStore store) {
        this.store = store;
        if (store != null) {
            store.loadState("policy-dial").ifPresent(this::deserialize);
        }
    }

    public synchronized void record(String situation, Signal signal) {
        int[] c = counters.computeIfAbsent(situation, k -> new int[2]);
        switch (signal) {
            case SLIP -> c[0]++;
            case RELAX -> c[1]++;
            case CLEAN -> {
                // a clean streak slowly decays the slip pressure
                if (c[0] > 0) {
                    c[0]--;
                }
            }
        }
        persist();
    }

    /** The situation's current level — STEER is the cold-start default. */
    public synchronized Level level(String situation) {
        int[] c = counters.getOrDefault(situation, new int[2]);
        int net = c[0] - c[1];
        if (net >= TIGHTEN_AT) {
            return Level.BLOCK;
        }
        if (-net >= RELAX_AT) {
            return Level.SILENT;
        }
        return Level.STEER;
    }

    public synchronized Map<String, String> describe() {
        Map<String, String> out = new LinkedHashMap<>();
        counters.forEach((situation, c) -> out.put(situation,
            level(situation) + " (slips=" + c[0] + ", relaxes=" + c[1] + ")"));
        return out;
    }

    private void persist() {
        if (store == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        counters.forEach((k, v) -> sb.append(k).append('=').append(v[0])
            .append(':').append(v[1]).append('\n'));
        store.saveState("policy-dial", sb.toString());
    }

    private void deserialize(String s) {
        for (String line : s.split("\n")) {
            int eq = line.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            String[] parts = line.substring(eq + 1).split(":");
            if (parts.length == 2) {
                try {
                    counters.put(line.substring(0, eq), new int[] {
                        Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
                } catch (NumberFormatException ignored) {
                    // corrupt line → cold start for that situation
                }
            }
        }
    }
}
