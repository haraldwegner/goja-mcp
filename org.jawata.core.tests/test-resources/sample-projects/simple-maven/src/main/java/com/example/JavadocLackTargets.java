package com.example;

/**
 * Sprint 25 javadoc_lack fixture — EXACTLY 8 findings expected:
 * the ctor, undocumentedField, undocumentedMethod, protectedUndoc,
 * DocumentedNested#nestedUndoc, NestedApi (type), NestedApi#implicitlyPublic,
 * Level#HIGH. Everything else is documented, non-API, inherited-doc, or a
 * trivial accessor.
 */
public class JavadocLackTargets {

    /** Documented field — no finding. */
    public int documentedField;

    public int undocumentedField;

    int packageField;

    public JavadocLackTargets() {
    }

    /** Documented method — no finding. */
    public void documentedMethod() {
    }

    public void undocumentedMethod() {
    }

    protected void protectedUndoc() {
    }

    private void privateUndoc() {
    }

    public int getUndocumentedField() {
        return undocumentedField;
    }

    public void setUndocumentedField(int value) {
        this.undocumentedField = value;
    }

    @Override
    public String toString() {
        return "JavadocLackTargets[" + undocumentedField + "]";
    }

    /** Documented nested class — the type itself is fine. */
    public static class DocumentedNested {

        public void nestedUndoc() {
        }
    }

    static class PackageNested {

        public void notApiBecauseChainIsNot() {
        }
    }

    public interface NestedApi {

        void implicitlyPublic();
    }

    /** Documented enum — the type itself is fine. */
    public enum Level {
        HIGH,
        /** Documented constant — no finding. */
        LOW
    }
}
