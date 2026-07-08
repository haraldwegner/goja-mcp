package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 22a P1-c — {@code analyze(kind="encapsulation")}: the composed
 * encapsulation audit.
 *
 * <p>{@code find_field_writes} alone answers "who writes this field directly",
 * which reports INTERNAL-ONLY for the classic leak: a private field guarded by
 * a public setter. The field is only ever written inside its own class (by the
 * setter), yet any external caller of that setter is effectively mutating it.
 * This audit closes the gap by composing, per field, the EFFECTIVE external
 * mutators:</p>
 *
 * <pre>
 *   poke-set(field) = { external types that write the field directly }
 *                   ∪ { external types that call a method of the class
 *                       whose body writes the field }
 * </pre>
 *
 * <p>A field with a non-empty poke-set is flagged as an encapsulation leak —
 * external code can change its value, directly or through a mutator — the
 * owner/poke partition the Sprint 6d book-flatten postmortem needed.</p>
 */
public class AnalyzeEncapsulationTool extends AbstractTool {

    public AnalyzeEncapsulationTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_encapsulation";
    }

    @Override
    public String getDescription() {
        return """
            Composed encapsulation audit for a type. Per field, computes the
            EFFECTIVE external mutators — external direct writers UNION external
            callers of the class's field-writing methods — so a private field
            behind a public setter that outside code calls is flagged, where
            find_field_writes alone reports internal-only.

            Needs: typeName. Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("typeName", Map.of("type", "string",
            "description", "Fully qualified or simple type name to audit."));
        schema.put("properties", properties);
        schema.put("required", List.of("typeName"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String typeName = getStringParam(arguments, "typeName");
        if (typeName == null || typeName.isBlank()) {
            return ToolResponse.invalidParameter("typeName", "Required parameter missing");
        }
        try {
            IType type = service.findType(typeName);
            if (type == null) {
                return ToolResponse.symbolNotFound("Type not found: " + typeName);
            }

            List<Map<String, Object>> fieldReports = new ArrayList<>();
            int leakingFields = 0;

            for (IField field : type.getFields()) {
                // Direct writers of the field, partitioned into external types
                // and (internal) methods of this class that write it = mutators.
                Set<String> directExternalWriters = new LinkedHashSet<>();
                Set<IMethod> mutators = new LinkedHashSet<>();
                for (SearchMatch w : service.getSearchService().findWriteAccesses(field, 1000)) {
                    IType wt = enclosingType(w);
                    if (wt != null && !sameType(wt, type)) {
                        directExternalWriters.add(wt.getFullyQualifiedName());
                    } else {
                        IMethod wm = enclosingMethod(w);
                        if (wm != null && wm.getDeclaringType() != null
                                && sameType(wm.getDeclaringType(), type)) {
                            mutators.add(wm);
                        }
                    }
                }

                // External callers of the mutators = the poke set through setters.
                Set<String> externalMutatorCallers = new LinkedHashSet<>();
                for (IMethod m : mutators) {
                    for (SearchMatch c : service.getSearchService()
                            .findReferences(m, IJavaSearchConstants.REFERENCES, 1000)) {
                        IType ct = enclosingType(c);
                        if (ct != null && !sameType(ct, type)) {
                            externalMutatorCallers.add(ct.getFullyQualifiedName());
                        }
                    }
                }

                Set<String> pokeSet = new LinkedHashSet<>(directExternalWriters);
                pokeSet.addAll(externalMutatorCallers);
                boolean leak = !pokeSet.isEmpty();
                if (leak) {
                    leakingFields++;
                }

                Map<String, Object> fr = new LinkedHashMap<>();
                fr.put("field", field.getElementName());
                fr.put("private", Flags.isPrivate(field.getFlags()));
                fr.put("directExternalWriters", new ArrayList<>(directExternalWriters));
                fr.put("mutatingMethods", mutators.stream().map(IMethod::getElementName).toList());
                fr.put("externalMutatorCallers", new ArrayList<>(externalMutatorCallers));
                fr.put("pokeSetCount", pokeSet.size());
                fr.put("encapsulationLeak", leak);
                fieldReports.add(fr);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", type.getFullyQualifiedName());
            data.put("fields", fieldReports);
            data.put("leakingFields", leakingFields);
            return ToolResponse.success(data);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private static IType enclosingType(SearchMatch match) {
        Object el = match.getElement();
        if (el instanceof IJavaElement je) {
            return (IType) je.getAncestor(IJavaElement.TYPE);
        }
        return null;
    }

    private static IMethod enclosingMethod(SearchMatch match) {
        Object el = match.getElement();
        if (el instanceof IJavaElement je) {
            return (IMethod) je.getAncestor(IJavaElement.METHOD);
        }
        return null;
    }

    private static boolean sameType(IType a, IType b) {
        return a != null && b != null
            && a.getFullyQualifiedName().equals(b.getFullyQualifiedName());
    }
}
