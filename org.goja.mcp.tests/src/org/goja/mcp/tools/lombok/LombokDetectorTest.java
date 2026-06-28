package org.goja.mcp.tools.lombok;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 15 B5b — source-only Lombok detection (no Lombok on the classpath).
 */
class LombokDetectorTest {

    private CompilationUnit parse(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(source.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        return (CompilationUnit) parser.createAST(null);
    }

    private TypeDeclaration firstType(CompilationUnit cu) {
        return (TypeDeclaration) cu.types().get(0);
    }

    @Test
    @DisplayName("importsLombok detects a lombok.* import")
    void importsLombok() {
        CompilationUnit with = parse("import lombok.Data;\n@Data class A { int x; }");
        CompilationUnit without = parse("import java.util.List;\nclass A { int x; }");
        assertTrue(LombokDetector.importsLombok(with));
        assertFalse(LombokDetector.importsLombok(without));
    }

    @Test
    @DisplayName("lombokAnnotations lists only Lombok annotations on a type")
    void lombokAnnotations() {
        CompilationUnit cu = parse("@Data @Deprecated class A { int x; }");
        var anns = LombokDetector.lombokAnnotations(firstType(cu));
        assertEquals(1, anns.size());
        assertTrue(anns.contains("Data"));
    }

    @Test
    @DisplayName("isDataCarrier is true for @Data/@Value, false for @Getter")
    void isDataCarrier() {
        assertTrue(LombokDetector.isDataCarrier(firstType(parse("@Data class A { int x; }"))));
        assertTrue(LombokDetector.isDataCarrier(firstType(parse("@Value class A { int x; }"))));
        assertFalse(LombokDetector.isDataCarrier(firstType(parse("@Getter class A { int x; }"))));
        assertFalse(LombokDetector.isDataCarrier(firstType(parse("class A { int x; }"))));
    }
}
