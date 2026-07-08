package com.example;

/**
 * Two call sites of {@link Service#v1(String)}. After a retarget to {@code v2}
 * both must invoke {@code v2} instead, and the project must still compile.
 */
public class Client {

    String a() {
        return new Service().v1("a");
    }

    String b() {
        Service svc = new Service();
        return svc.v1("b");
    }
}
