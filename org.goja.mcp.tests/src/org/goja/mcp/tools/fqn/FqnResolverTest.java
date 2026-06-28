package org.goja.mcp.tools.fqn;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.goja.core.JdtServiceImpl;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 14 Phase B.2 — focused contract tests for {@link FqnResolver}.
 * Drives against the simple-maven fixture which has predictable types,
 * methods, and fields under {@code com.example.*}.
 */
class FqnResolverTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    @Test
    @DisplayName("type FQN resolves to IType")
    void typeFqn_resolvesToIType() {
        Optional<IJavaElement> resolved =
            FqnResolver.resolveWorkspace("com.example.HelloWorld", service);
        assertTrue(resolved.isPresent(), "type FQN must resolve");
        assertTrue(resolved.get() instanceof IType, "must be an IType; got " + resolved.get().getClass());
        assertEquals("HelloWorld", resolved.get().getElementName());
    }

    @Test
    @DisplayName("method FQN without args resolves to ANY overload (first match)")
    void methodFqn_noArgs_resolvesAnyOverload() {
        // HelloWorld has two constructors but also overloaded methods; getGreeting() is a plain method
        Optional<IJavaElement> resolved =
            FqnResolver.resolveWorkspace("com.example.HelloWorld#getGreeting", service);
        assertTrue(resolved.isPresent(), "method FQN must resolve");
        assertTrue(resolved.get() instanceof IMethod, "must be an IMethod");
        assertEquals("getGreeting", resolved.get().getElementName());
    }

    @Test
    @DisplayName("method FQN with empty () resolves to no-arg overload")
    void methodFqn_emptyArgs_resolvesNoArgOverload() {
        Optional<IJavaElement> resolved =
            FqnResolver.resolveWorkspace("com.example.HelloWorld#getGreeting()", service);
        assertTrue(resolved.isPresent(), "no-arg method FQN must resolve");
        assertTrue(resolved.get() instanceof IMethod);
        IMethod m = (IMethod) resolved.get();
        assertEquals(0, m.getParameterTypes().length, "must have zero parameters");
    }

    @Test
    @DisplayName("method FQN with String arg resolves to that specific overload")
    void methodFqn_withStringArg_resolvesSpecificOverload() throws Exception {
        // HelloWorld has setGreeting(String greeting)
        Optional<IJavaElement> resolved =
            FqnResolver.resolveWorkspace(
                "com.example.HelloWorld#setGreeting(java.lang.String)", service);
        assertTrue(resolved.isPresent(), "specific-overload method FQN must resolve");
        IMethod m = (IMethod) resolved.get();
        assertEquals(1, m.getParameterTypes().length);
    }

    @Test
    @DisplayName("field FQN resolves to IField")
    void fieldFqn_resolvesToIField() {
        // HelloWorld has `private String greeting;`
        Optional<IJavaElement> resolved =
            FqnResolver.resolveWorkspace("com.example.HelloWorld#greeting", service);
        assertTrue(resolved.isPresent(), "field FQN must resolve");
        assertTrue(resolved.get() instanceof IField, "must be an IField");
        assertEquals("greeting", resolved.get().getElementName());
    }

    // ===== Sprint 15 DX#1: dot-form member fallback =====
    // Agents naturally write `Type.method` / `Type.field` (dot), not the `#`
    // form. These must resolve the same as the `#` forms above.

    @Test
    @DisplayName("DX#1: dot-form method FQN resolves (com.example.HelloWorld.getGreeting)")
    void dotFormMethod_resolves() {
        Optional<IJavaElement> resolved =
            FqnResolver.resolveWorkspace("com.example.HelloWorld.getGreeting", service);
        assertTrue(resolved.isPresent(), "dot-form method FQN must resolve");
        assertTrue(resolved.get() instanceof IMethod, "must be an IMethod");
        assertEquals("getGreeting", resolved.get().getElementName());
    }

    @Test
    @DisplayName("DX#1: dot-form field FQN resolves (com.example.HelloWorld.greeting)")
    void dotFormField_resolves() {
        Optional<IJavaElement> resolved =
            FqnResolver.resolveWorkspace("com.example.HelloWorld.greeting", service);
        assertTrue(resolved.isPresent(), "dot-form field FQN must resolve");
        assertTrue(resolved.get() instanceof IField, "must be an IField");
        assertEquals("greeting", resolved.get().getElementName());
    }

    @Test
    @DisplayName("DX#1: type-only dot FQN still resolves to the type (no regression)")
    void dotForm_typeStillResolvesToType() {
        Optional<IJavaElement> resolved =
            FqnResolver.resolveWorkspace("com.example.HelloWorld", service);
        assertTrue(resolved.isPresent());
        assertTrue(resolved.get() instanceof IType, "type-only form must stay a type, not a member");
    }

    @Test
    @DisplayName("DX#1: dot form with unknown trailing member returns empty")
    void dotForm_unknownMember_returnsEmpty() {
        Optional<IJavaElement> resolved =
            FqnResolver.resolveWorkspace("com.example.HelloWorld.noSuchMember", service);
        assertFalse(resolved.isPresent(), "unknown dot-form member must not resolve");
    }

    @Test
    @DisplayName("unknown type FQN returns empty")
    void unknownType_returnsEmpty() {
        Optional<IJavaElement> resolved =
            FqnResolver.resolveWorkspace("com.no.such.Type", service);
        assertFalse(resolved.isPresent(), "unknown FQN must NOT resolve");
    }

    @Test
    @DisplayName("project-scoped lookup honours the supplied projectKey")
    void projectScope_resolvesInGivenProject() {
        String projectKey = service.allProjects().iterator().next().projectKey();
        Optional<IJavaElement> resolved = FqnResolver.resolve(
            "com.example.HelloWorld", service,
            FqnResolver.Scope.PROJECT, projectKey);
        assertTrue(resolved.isPresent(), "FQN must resolve in scoped lookup");
    }

    @Test
    @DisplayName("project-scoped lookup with unknown projectKey returns empty")
    void projectScope_unknownProjectKey_returnsEmpty() {
        Optional<IJavaElement> resolved = FqnResolver.resolve(
            "com.example.HelloWorld", service,
            FqnResolver.Scope.PROJECT, "no-such-project");
        assertFalse(resolved.isPresent(),
            "unknown projectKey under PROJECT scope must return empty");
    }

    @Test
    @DisplayName("null or blank FQN returns empty")
    void nullOrBlankFqn_returnsEmpty() {
        assertFalse(FqnResolver.resolveWorkspace(null, service).isPresent());
        assertFalse(FqnResolver.resolveWorkspace("", service).isPresent());
        assertFalse(FqnResolver.resolveWorkspace("   ", service).isPresent());
    }
}
