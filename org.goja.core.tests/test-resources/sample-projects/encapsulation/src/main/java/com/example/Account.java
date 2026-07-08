package com.example;

/**
 * The setter-false-pass subject (Sprint 22a encapsulation audit). {@code balance}
 * is private and written ONLY inside this class (by {@link #setBalance(int)}), so
 * find_field_writes reports it as internal-only — yet {@link External} mutates it
 * from outside through the public setter. The composed audit must see that.
 */
public class Account {

    private int balance;

    public int balance() {
        return balance;
    }

    public void setBalance(int v) {
        this.balance = v;
    }
}
