package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

/**
 * Sample test class for testing FindTestsTool.
 * Contains JUnit 5 annotations.
 */
public class SampleTest {

    private Calculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new Calculator();
    }

    @AfterEach
    void tearDown() {
        calculator = null;
    }

    @Test
    void testAddition() {
        int result = calculator.add(2, 3);
        assert result == 5;
    }

    @Test
    void testSubtraction() {
        int result = calculator.subtract(5, 3);
        assert result == 2;
    }

    @Test
    @DisplayName("Test multiplication of two numbers")
    void testMultiplication() {
        int result = calculator.multiply(4, 3);
        assert result == 12;
    }

    @Test
    @Disabled("Not implemented yet")
    void testDivision() {
        // This test is disabled
    }

    @Test
    @Disabled
    void anotherDisabledTest() {
        // Another disabled test without reason
    }

    @Test
    @DisplayName("Custom display name for this test")
    void testWithCustomDisplayName() {
        assert true;
    }

    // Sprint 12 (v1.6.0): deliberately-failing test, used by v1.6.1's
    // happy-path classScope test to assert that test failures land in the
    // tool's `failures[]` array with stack traces.
    @Test
    @DisplayName("Deliberately fails — used by run_tests classScope test")
    void testThatAlwaysFails() {
        throw new AssertionError("expected: <2> but was: <3>");
    }

    /**
     * Not a test method - no @Test annotation.
     */
    void helperMethod() {
        // This should not be detected as a test
    }

    /**
     * Private method - not a test.
     */
    private void privateHelper() {
        // This should not be detected as a test
    }

    // Sprint 17 (v1.2.1): a deliberately long method in TEST source. The smell
    // detectors exclude test sources by default, so long_method must NOT flag
    // this unless includeTests=true. (Not a @Test method → FindTestsTool ignores it.)
    int longTestHelper() {
        int a = 0;
        a += 1;
        a += 2;
        a += 3;
        a += 4;
        a += 5;
        a += 6;
        a += 7;
        a += 8;
        a += 9;
        a += 10;
        a += 11;
        a += 12;
        a += 13;
        a += 14;
        a += 15;
        a += 16;
        a += 17;
        a += 18;
        a += 19;
        a += 20;
        a += 21;
        a += 22;
        a += 23;
        a += 24;
        a += 25;
        a += 26;
        a += 27;
        a += 28;
        a += 29;
        a += 30;
        a += 31;
        a += 32;
        a += 33;
        a += 34;
        a += 35;
        a += 36;
        a += 37;
        a += 38;
        a += 39;
        a += 40;
        a += 41;
        a += 42;
        a += 43;
        a += 44;
        a += 45;
        a += 46;
        a += 47;
        a += 48;
        a += 49;
        a += 50;
        a += 51;
        a += 52;
        a += 53;
        a += 54;
        a += 55;
        a += 56;
        a += 57;
        a += 58;
        a += 59;
        a += 60;
        a += 61;
        a += 62;
        a += 63;
        a += 64;
        a += 65;
        return a;
    }
}
