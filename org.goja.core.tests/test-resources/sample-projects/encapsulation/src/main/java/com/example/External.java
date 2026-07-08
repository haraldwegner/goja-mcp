package com.example;

/**
 * An external mutator: it does not own {@link Account}'s {@code balance} field
 * but changes its value through the public setter — the effective external
 * write that a direct field-write search cannot see.
 */
public class External {

    void poke() {
        Account a = new Account();
        a.setBalance(100);
    }
}
