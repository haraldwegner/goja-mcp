package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.ltk.core.refactoring.Change;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.CreateCompilationUnitChange;
import org.goja.mcp.refactoring.RefactoringChangeCache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 19 (Kerievsky) — <b>Replace Type Code with Class</b> (toward pattern). A
 * group of same-typed {@code static final} type-code constants ({@code STATUS_NEW},
 * {@code STATUS_PAID}, …) is given a type-safe home: a generated {@code enum} in a
 * new compilation unit (same package). Conservative for v1.4 — it introduces the
 * abstraction and reports the constant→enum mapping; it does NOT auto-retype fields
 * or rewrite every usage (that cascades through arithmetic/switch/serialization and
 * is the agent's call). The new file is created atomically and reverts via
 * {@code undo_refactoring} (which deletes it).
 *
 * <p>A delegate of {@link RefactorToPatternTool} (kind
 * {@code replace_type_code_with_class}). Find candidates via
 * {@code find_quality_issue(kind=type_code)}.</p>
 */
public class ReplaceTypeCodeWithClassTool extends AbstractApplyingRefactoringTool {

    public ReplaceTypeCodeWithClassTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "replace_type_code_with_class";
    }

    @Override
    public String getDescription() {
        return "Replace Type Code with Class — generate a type-safe enum from a group of static-final "
            + "type-code constants into a new file (same package). Delegate of refactor_to_pattern.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of("type", "string", "description", "Source file of the class holding the codes."),
            "line", Map.of("type", "integer", "description", "Zero-based line on the class."),
            "column", Map.of("type", "integer", "description", "Zero-based column on the class."),
            "newTypeName", Map.of("type", "string", "description", "Name for the generated enum type."),
            "prefix", Map.of("type", "string",
                "description", "Optional: the constant prefix to convert (e.g. STATUS). Default: the largest group.")));
        schema.put("required", List.of("filePath", "line", "column", "newTypeName"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required"));
        }
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column", "Must be >= 0"));
        }
        String newTypeName = getStringParam(arguments, "newTypeName");
        if (newTypeName == null || !isIdentifier(newTypeName)) {
            return Preparation.fail(ToolResponse.invalidParameter("newTypeName", "A valid Java type name is required"));
        }
        String prefixArg = getStringParam(arguments, "prefix");

        java.nio.file.Path path = java.nio.file.Path.of(filePath);
        IType type = service.getTypeAtPosition(path, line, column);
        if (type == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "No type at " + line + ":" + column));
        }
        ICompilationUnit cu = type.getCompilationUnit();
        if (cu == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Source not available"));
        }
        CompilationUnit ast = parse(cu);
        TypeDeclaration td = findType(ast, type.getElementName());
        if (td == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Cannot locate " + type.getElementName()));
        }

        // Group static-final int/String constants by typeTag + prefix (prefix = up to first underscore).
        Map<String, List<String>> groups = new LinkedHashMap<>();
        Map<String, String> keyPrefix = new LinkedHashMap<>();
        for (FieldDeclaration f : td.getFields()) {
            int mods = f.getModifiers();
            if (!Modifier.isStatic(mods) || !Modifier.isFinal(mods)) {
                continue;
            }
            String tag = typeTag(f.getType());
            if (tag == null) {
                continue;
            }
            for (Object frag : f.fragments()) {
                String name = ((VariableDeclarationFragment) frag).getName().getIdentifier();
                String prefix = name.contains("_") ? name.substring(0, name.indexOf('_')) : name;
                String key = tag + ":" + prefix;
                groups.computeIfAbsent(key, k -> new ArrayList<>()).add(name);
                keyPrefix.putIfAbsent(key, prefix);
            }
        }
        String chosenKey = groups.entrySet().stream()
            .filter(e -> prefixArg == null || prefixArg.equals(keyPrefix.get(e.getKey())))
            .max(Map.Entry.comparingByValue(java.util.Comparator.comparingInt(List::size)))
            .map(Map.Entry::getKey)
            .orElse(null);
        if (chosenKey == null || groups.get(chosenKey).size() < 2) {
            return Preparation.fail(ToolResponse.invalidParameter("type",
                "No type-code constant group of >= 2 same-typed static-final constants found"
                    + (prefixArg != null ? " for prefix '" + prefixArg + "'" : "") + "."));
        }
        List<String> constants = groups.get(chosenKey);
        String prefix = keyPrefix.get(chosenKey);

        // enum constant name = strip the shared prefix (STATUS_NEW -> NEW) when it stays a valid identifier.
        Map<String, String> mapping = new LinkedHashMap<>();
        List<String> enumConstants = new ArrayList<>();
        for (String name : constants) {
            String en = name;
            if (name.startsWith(prefix + "_")) {
                String stripped = name.substring(prefix.length() + 1);
                if (isIdentifier(stripped)) {
                    en = stripped;
                }
            }
            enumConstants.add(en);
            mapping.put(name, en);
        }

        String pkg = ast.getPackage() != null ? ast.getPackage().getName().getFullyQualifiedName() : null;
        String source = buildEnumSource(pkg, newTypeName, enumConstants, type.getElementName(), prefix);

        IContainer parent = (IContainer) cu.getResource().getParent();
        IFile newFile = parent.getFile(new Path(newTypeName + ".java"));
        if (newFile.exists()) {
            return Preparation.fail(ToolResponse.invalidParameter("newTypeName",
                "A file named " + newTypeName + ".java already exists in this package."));
        }
        Change change = new CreateCompilationUnitChange(newFile, source);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("newType", newTypeName);
        extras.put("package", pkg == null ? "" : pkg);
        extras.put("sourceClass", type.getElementName());
        extras.put("typeCodePrefix", prefix);
        extras.put("constantMapping", mapping);
        extras.put("note", "Usages are not auto-migrated: replace " + prefix + "_* references with "
            + newTypeName + ".<CONSTANT> and retype fields to " + newTypeName + " where safe.");
        String summary = "replace type code: generated enum " + newTypeName + " from "
            + constants.size() + " '" + prefix + "_*' constants on " + type.getElementName();
        return Preparation.of(change, summary, extras);
    }

    private static String buildEnumSource(String pkg, String enumName, List<String> constants,
                                          String sourceClass, String prefix) {
        StringBuilder sb = new StringBuilder();
        if (pkg != null && !pkg.isBlank()) {
            sb.append("package ").append(pkg).append(";\n\n");
        }
        sb.append("/**\n")
            .append(" * Type-safe replacement for the ").append(prefix).append("_* type codes on ")
            .append(sourceClass).append(".\n")
            .append(" * Generated by refactor_to_pattern (replace_type_code_with_class); migrate usages incrementally.\n")
            .append(" */\n");
        sb.append("public enum ").append(enumName).append(" {\n");
        for (int i = 0; i < constants.size(); i++) {
            sb.append("    ").append(constants.get(i)).append(i < constants.size() - 1 ? "," : ";").append("\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String typeTag(Type type) {
        if (type.isPrimitiveType() && ((PrimitiveType) type).getPrimitiveTypeCode() == PrimitiveType.INT) {
            return "int";
        }
        ITypeBinding b = type.resolveBinding();
        if (b != null && "java.lang.String".equals(b.getQualifiedName())) {
            return "String";
        }
        return null;
    }

    private static CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }

    private static TypeDeclaration findType(CompilationUnit ast, String simpleName) {
        for (Object t : ast.types()) {
            if (t instanceof TypeDeclaration td && simpleName.equals(td.getName().getIdentifier())) {
                return td;
            }
        }
        return null;
    }

    private static boolean isIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
