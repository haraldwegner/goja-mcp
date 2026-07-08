package com.example.recipe;

public class Registry {
    private static final Registry INSTANCE = new Registry();

    private Registry() {
    }

    public static Registry getInstance() {
        return INSTANCE;
    }

    public String lookup(String key) {
        return key.toUpperCase();
    }
}
