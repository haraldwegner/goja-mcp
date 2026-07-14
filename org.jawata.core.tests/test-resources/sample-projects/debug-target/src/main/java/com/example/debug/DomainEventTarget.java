package com.example.debug;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/**
 * Sprint 24 audit (2026-07-14, T2.3) fixture — a program that emits its OWN domain events into
 * Java Flight Recorder, the way a real trading sim commits an "order placed" or "risk breach"
 * event. D12 asks for "the target's own domain events where it emits them"; v2.13.0 surfaced
 * only the JVM's built-in events. This fixture proves jawata can read the application's own
 * vocabulary out of the same recording it profiles.
 */
public final class DomainEventTarget {

    @Name("com.example.debug.OrderPlaced")
    @Label("Order Placed")
    static final class OrderPlaced extends Event {
        @Label("Symbol")
        String symbol;
        @Label("Quantity")
        int quantity;
    }

    public static void main(String[] args) throws Exception {
        int n = 0;
        while (true) {
            OrderPlaced event = new OrderPlaced();
            event.symbol = (n % 2 == 0) ? "AAPL" : "MSFT";
            event.quantity = 100 + n;
            event.commit();     // into whatever recording is running
            n++;
            Thread.sleep(20);
        }
    }

    private DomainEventTarget() {
    }
}
