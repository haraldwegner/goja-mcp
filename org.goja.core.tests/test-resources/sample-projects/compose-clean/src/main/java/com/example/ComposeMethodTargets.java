package com.example;

/**
 * Sprint 19 fixture — compose_method. {@code ReportBuilder.build()} is a long
 * method with disjoint sections; compose_method extracts the header section
 * (0-based line 13) and the footer section (0-based line 18) into named
 * sub-methods. Both statements are 36 chars (indent 8 -> end column 44).
 */
public class ComposeMethodTargets {
}

class ReportBuilder {
    void build() {
        System.out.println("header line 1");
        System.out.println("header line 2");
        System.out.println("body line -1");
        System.out.println("body line -2");
        System.out.println("footer line 1");
        System.out.println("footer line 2");
    }
}
