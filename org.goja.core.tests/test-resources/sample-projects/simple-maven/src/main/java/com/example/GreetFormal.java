package com.example;

/** Sprint 18 extract_superclass sibling A (shares an identical punctuation() with GreetCasual). */
public class GreetFormal {

    public String punctuation() {
        return ".";
    }

    public String greet(String name) {
        return "Good day, " + name + punctuation();
    }
}
