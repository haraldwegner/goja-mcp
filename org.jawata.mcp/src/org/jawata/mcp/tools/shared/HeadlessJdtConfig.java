package org.jawata.mcp.tools.shared;

import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.eclipse.jdt.internal.core.manipulation.CodeTemplateContextType;
import org.eclipse.jface.text.templates.Template;
import org.eclipse.text.templates.ContextTypeRegistry;
import org.eclipse.text.templates.TemplatePersistenceData;
import org.eclipse.text.templates.TemplateStoreCore;

/**
 * Sprint 23 (Stage 5) — one-shot headless configuration of
 * {@code org.eclipse.jdt.core.manipulation}. In the IDE the JDT-UI plug-in
 * performs this on activation; a headless embedder must do it itself (the
 * jdt.ls pattern), or manipulation-based refactorings NPE/IAE the first time
 * they read a preference: {@code JavaManipulation.getPreference} passes the
 * PREFERENCE NODE QUALIFIER to {@code ProjectScope.getNode}, and an unset
 * (null) qualifier is rejected with {@code IllegalArgumentException} —
 * observed live on {@code SelfEncapsulateFieldRefactoring.initialize →
 * GetterSetterUtil.getGetterName → StubUtility.useIsForBooleanGetters}.
 */
public final class HeadlessJdtConfig {

    private static volatile boolean initialized;

    /** Idempotent; cheap enough to call before any manipulation refactoring. */
    public static void ensureInitialized() {
        if (initialized) return;
        synchronized (HeadlessJdtConfig.class) {
            if (initialized) return;
            if (JavaManipulation.getPreferenceNodeId() == null) {
                JavaManipulation.setPreferenceNodeId("org.jawata.jdt");
            }
            // Preferences JDT-UI seeds on activation and manipulation code
            // reads UNGUARDED (null → NPE, e.g. the member-sort-order string
            // in getter/setter generation). Same defaults jdt.ls installs.
            IEclipsePreferences defaults =
                DefaultScope.INSTANCE.getNode(JavaManipulation.getPreferenceNodeId());
            defaults.put("outlinesortoption", "T,SF,SI,SM,F,I,C,M");
            defaults.put("org.eclipse.jdt.ui.visibility.order", "B,V,R,D");
            // CodeStyleConfiguration.configureImportRewrite reads these
            // UNGUARDED (order.endsWith NPEs on a missing default).
            defaults.put("org.eclipse.jdt.ui.importorder", "java;javax;org;com");
            defaults.put("org.eclipse.jdt.ui.ondemandthreshold", "99");
            defaults.put("org.eclipse.jdt.ui.staticondemandthreshold", "99");

            // Code-template store: without one, CodeGeneration.get*BodyContent
            // returns null and SelfEncapsulateFieldRefactoring's fallback path
            // hits an upstream bug (a bare Assignment added where a Statement
            // is required). Registering the IDE-default stub bodies makes the
            // template path work and the fallback unreachable.
            if (JavaManipulation.getCodeTemplateStore() == null) {
                ContextTypeRegistry registry = new ContextTypeRegistry();
                CodeTemplateContextType.registerContextTypes(registry);
                TemplateStoreCore store = new TemplateStoreCore(registry,
                    InstanceScope.INSTANCE.getNode(JavaManipulation.getPreferenceNodeId()),
                    "code_templates");
                addTemplate(store, CodeTemplateContextType.GETTERSTUB_ID, "getterstub",
                    CodeTemplateContextType.GETTERBODY_CONTEXTTYPE, "return ${field};");
                addTemplate(store, CodeTemplateContextType.SETTERSTUB_ID, "setterstub",
                    CodeTemplateContextType.SETTERBODY_CONTEXTTYPE, "${field} = ${param};");
                addTemplate(store, CodeTemplateContextType.METHODSTUB_ID, "methodstub",
                    CodeTemplateContextType.METHODBODY_CONTEXTTYPE,
                    "// ${todo} Auto-generated method stub\n${body_statement}");
                addTemplate(store, CodeTemplateContextType.CONSTRUCTORSTUB_ID, "constructorstub",
                    CodeTemplateContextType.CONSTRUCTORBODY_CONTEXTTYPE,
                    "// ${todo} Auto-generated constructor stub\n${body_statement}");
                addTemplate(store, CodeTemplateContextType.CATCHBLOCK_ID, "catchblock",
                    CodeTemplateContextType.CATCHBLOCK_CONTEXTTYPE,
                    "// ${todo} Auto-generated catch block\n${exception_var}.printStackTrace();");
                JavaManipulation.setCodeTemplateStore(store);
                JavaManipulation.setCodeTemplateContextRegistry(registry);
            }
            initialized = true;
        }
    }

    private static void addTemplate(TemplateStoreCore store, String id, String name,
                                    String contextTypeId, String pattern) {
        Template template = new Template(name, "", contextTypeId, pattern, true);
        store.add(new TemplatePersistenceData(template, true, id));
    }

    private HeadlessJdtConfig() {}
}
