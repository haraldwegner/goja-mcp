package com.example;

/**
 * Sprint 19 fixture — type_code detector + replace_type_code_with_class.
 * {@code Order} holds a STATUS_* int type-code group (0-based class line 11) →
 * flagged, and replace_type_code_with_class generates an OrderStatus enum.
 * {@code NoCodes} has unrelated constants → not flagged.
 */
public class TypeCodeTargets {
}

class Order {
    static final int STATUS_NEW = 0;
    static final int STATUS_PAID = 1;
    static final int STATUS_SHIPPED = 2;

    private int status = STATUS_NEW;

    int getStatus() {
        return status;
    }
}

class NoCodes {
    static final int MAX = 100;
    static final String NAME = "order";
}
