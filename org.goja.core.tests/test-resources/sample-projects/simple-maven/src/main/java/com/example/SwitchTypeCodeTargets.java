package com.example;

/**
 * Sprint 17 fixture — Switch Statements (type code) detector.
 * {@code onCode} switches over an int with 3 cases → flagged. {@code onEnum}
 * switches over an enum → not flagged. {@code small} has 2 cases → not flagged.
 */
public class SwitchTypeCodeTargets {

    enum Color { RED, GREEN, BLUE }

    public int onCode(int code) {
        switch (code) {
            case 1:
                return 10;
            case 2:
                return 20;
            case 3:
                return 30;
            default:
                return 0;
        }
    }

    public int onEnum(Color c) {
        switch (c) {
            case RED:
                return 1;
            case GREEN:
                return 2;
            case BLUE:
                return 3;
            default:
                return 0;
        }
    }

    public int small(int code) {
        switch (code) {
            case 1:
                return 1;
            case 2:
                return 2;
            default:
                return 0;
        }
    }
}
