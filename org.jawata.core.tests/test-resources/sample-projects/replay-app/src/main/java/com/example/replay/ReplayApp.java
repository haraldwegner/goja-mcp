package com.example.replay;

/**
 * Sprint 24 fixture — the deterministic replay the invariant proofs (D9) run
 * against. ORB/JATS owns its real journal and replay semantics; jawata owns the
 * generic descriptor and the orchestration, so this fixture stands in for "an
 * application-provided replay" without borrowing anything private.
 *
 * <p><b>Determinism is the entire point.</b> It emits a fixed sequence of events
 * and its declared invariant — {@code balance >= 0} — is violated for the FIRST
 * time at a known event, every run, on every machine. A capture-at-first-violation
 * claim is only testable against a replay that violates in exactly one place.</p>
 *
 * <p>The balance walks safely down to 10, then event {@value #VIOLATING_EVENT}
 * withdraws 40 and takes it negative. Later events would violate it again — which
 * is what makes "the FIRST one" a real assertion rather than a tautology.</p>
 */
public final class ReplayApp {

    /** The invariant: this must never go below zero. It does, exactly here. */
    public static final int VIOLATING_EVENT = 7;

    private static int balance = 100;

    public static void main(String[] args) {
        int[] withdrawals = {10, 20, 15, 25, 5, 15, 10, 40, 5, 30};

        for (int event = 0; event < withdrawals.length; event++) {
            apply(event, withdrawals[event]);
        }
        System.out.println("replay complete, balance " + balance);
    }

    /**
     * One replayed event. The debugger breaks here and evaluates the invariant
     * after each application — the first time it fails is event 7.
     */
    static void apply(int event, int amount) {
        balance = balance - amount;
        System.out.println("event " + event + " amount " + amount + " balance " + balance);
    }

    public static int getBalance() {
        return balance;
    }

    /** The declared invariant, as the application itself states it. */
    public static boolean invariantHolds() {
        return balance >= 0;
    }

    private ReplayApp() {
    }
}
