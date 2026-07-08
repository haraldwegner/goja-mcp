package com.example.recipe;

public class RegistryUser {
    public String run(String k) {
        return Registry.getInstance().lookup(k);
    }

    public String run2(String k) {
        Registry r = Registry.getInstance();
        return r.lookup(k);
    }
}
