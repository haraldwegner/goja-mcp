package com.example;

/**
 * Use-site fixture (Sprint 25, spec D1a item 6): the local only calls
 * getName(), so extract_interface's use-where-possible rewrite can widen its
 * type to the extracted interface.
 */
public class InterfaceUseSite {
    public String describe() {
        InterfaceExtractTarget target = InterfaceExtractTarget.create("probe");
        return target.getName();
    }
}
