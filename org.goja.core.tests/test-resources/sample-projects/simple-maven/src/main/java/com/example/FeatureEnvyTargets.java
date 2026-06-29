package com.example;

/**
 * Sprint 17 fixture — Feature Envy detector.
 * {@code envious} touches {@code Wallet}'s members 3 times and none of its own
 * → flagged (Move Method to Wallet). {@code homebody} only uses its own members
 * → not flagged.
 */
public class FeatureEnvyTargets {

    private int local = 1;

    public int envious(Wallet w) {
        int b = w.getBalance();
        int l = w.getLimit();
        w.setBalance(b + l);
        return b;
    }

    public int homebody() {
        return ownHelper() + local;
    }

    private int ownHelper() {
        return local * 2;
    }
}

class Wallet {
    private int balance;
    private int limit;

    public int getBalance() {
        return balance;
    }

    public int getLimit() {
        return limit;
    }

    public void setBalance(int balance) {
        this.balance = balance;
    }
}
