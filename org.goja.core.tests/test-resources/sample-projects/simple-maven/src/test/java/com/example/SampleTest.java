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
}
