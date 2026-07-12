package org.jawata.spike;

import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 22d Stage 4, explicit probe #1: the headless-Tycho test runtime could not
 * resolve {@code org.eclipse.jdt.core.compiler.IScanner} at OSGi load — the
 * documented reason {@code FindDuplicateCodeTool}'s tokenizer is hand-rolled.
 * In the plain-JUnit runtime this class must simply be there and work.
 */
class SpikeTestRuntimeProbeTest {

    @Test
    @DisplayName("IScanner resolves and scans in the plain test runtime — the old failure class is gone")
    void iScannerResolvesAndWorks() throws Exception {
        assertNotNull(Class.forName("org.eclipse.jdt.core.compiler.IScanner"),
            "IScanner must resolve on the flat classpath");
        IScanner scanner = ToolFactory.createScanner(false, false, false, "21", "21");
        assertNotNull(scanner, "ToolFactory must hand out a scanner");
        scanner.setSource("class A { int x = 1; }".toCharArray());
        int tokens = 0;
        while (scanner.getNextToken() != org.eclipse.jdt.core.compiler.ITerminalSymbols.TokenNameEOF) {
            tokens++;
        }
        assertTrue(tokens >= 9, "the scanner must actually lex; got " + tokens + " tokens");
    }
}
