package com.example;

/**
 * Sprint 19 fixture — refactor_to_command_dispatcher. Calculator.apply(int op)
 * dispatches on a type-coded action (switch at 0-based line 23); each case mutates
 * the result field. refactor_to_command_dispatcher lifts the bodies into nested
 * Command classes selected by a switch-expression.
 */
public class CommandTargets {
}

class Calculator {
    static final int ADD = 0;
    static final int SUB = 1;
    static final int MUL = 2;

    private int result;

    int result() {
        return result;
    }

    void apply(int op) {
        switch (op) {
            case ADD:
                result = result + 1;
                break;
            case SUB:
                result = result - 1;
                break;
            case MUL:
                result = result * 2;
                break;
            default:
                result = 0;
                break;
        }
    }
}
