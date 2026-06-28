package com.example.a;

import com.example.b.Greeter;

public class SpecificGreeter extends Greeter {
    public String greetWithEmphasis(String name) {
        return greet(name) + "!!!";
    }
}
