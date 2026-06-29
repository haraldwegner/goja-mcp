package com.example;

/**
 * Sprint 17 fixture — Message Chains detector.
 * {@code longChain} navigates {@code a.b().c().d().e()} (length 4 &gt; default 3)
 * → flagged (Hide Delegate). {@code shortChain} is length 2 → not flagged.
 */
public class MessageChainTargets {

    public int longChain(ChainA a) {
        return a.b().c().d().e();
    }

    public ChainC shortChain(ChainA a) {
        return a.b().c();
    }
}

class ChainA {
    ChainB b() {
        return new ChainB();
    }
}

class ChainB {
    ChainC c() {
        return new ChainC();
    }
}

class ChainC {
    ChainD d() {
        return new ChainD();
    }
}

class ChainD {
    int e() {
        return 0;
    }
}
