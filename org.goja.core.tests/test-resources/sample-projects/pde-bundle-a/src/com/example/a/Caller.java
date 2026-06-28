package com.example.a;

import com.example.b.Greeter;

public class Caller {
    private final Greeter greeter = new Greeter();

    public String hello(String name) {
        return greeter.greet(name);
    }
}
