package com.example;

/** Sprint 18 extract_superclass sibling B (shares an identical punctuation() with GreetFormal). */
public class GreetCasual {

    public String punctuation() {
        return ".";
    }

    public String greet(String name) {
        return "Hey " + name + punctuation();
    }
}
