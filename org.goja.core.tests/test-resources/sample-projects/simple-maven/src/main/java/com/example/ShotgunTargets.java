package com.example;

/**
 * Sprint 17 fixture — Shotgun Surgery detector (v1.3.1 recalibration).
 * {@code ShotgunTarget} (concrete) is referenced by 12 distinct types (> the
 * raised default 10) → flagged. {@code AbstractWidelyUsed} is referenced just as
 * widely but is abstract → NOT flagged (an abstraction is meant to be depended
 * on — DIP). {@code LonelyShotgun} is referenced by none → not flagged.
 */
public class ShotgunTargets {
}

class ShotgunTarget {
    int value;

    int get() {
        return value;
    }
}

abstract class AbstractWidelyUsed {
    abstract int op();
}

class LonelyShotgun {
    int value;
}

class SUser1 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
class SUser2 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
class SUser3 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
class SUser4 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
class SUser5 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
class SUser6 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
class SUser7 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
class SUser8 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
class SUser9 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
class SUser10 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
class SUser11 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
class SUser12 { ShotgunTarget t = new ShotgunTarget(); AbstractWidelyUsed a; }
