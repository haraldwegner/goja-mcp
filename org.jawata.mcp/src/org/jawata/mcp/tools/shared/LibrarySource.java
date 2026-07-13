package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.util.ClassFileBytesDisassembler;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Sprint 23 (D8) — readable source for ANY type by FQN: workspace source,
 * JDT-attached source (JDK src.zip), the sibling {@code …-sources.jar} in
 * the local repository (read directly, no classpath mutation, EXISTING jars
 * only — never a silent network fetch), or an honestly-labeled disassembled
 * stub as the last resort.
 */
public final class LibrarySource {

    private static final int MAX_SOURCE_CHARS = 120_000;

    /** null = the FQN resolves in no loaded project. */
    public static Map<String, Object> sourceOf(IJdtService service, String typeName)
            throws Exception {
        for (LoadedProject project : service.allProjects()) {
            IType type = project.javaProject().findType(typeName);
            if (type == null) continue;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("typeName", typeName);
            result.put("projectKey", project.projectKey());

            if (type.getCompilationUnit() != null) {
                result.put("origin", "workspace-source");
                result.put("source", bounded(type.getCompilationUnit().getSource(), result));
                return result;
            }

            IClassFile classFile = type.getClassFile();
            if (classFile == null) continue;
            IPackageFragmentRoot root = (IPackageFragmentRoot)
                classFile.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
            if (root != null) {
                result.put("container", root.getPath().toOSString());
            }

            String attached = classFile.getSource();
            if (attached != null && !attached.isBlank()) {
                result.put("origin", "attached-source");
                result.put("source", bounded(attached, result));
                return result;
            }

            String fromSourcesJar = root == null ? null
                : readSiblingSourcesJar(root, type);
            if (fromSourcesJar != null) {
                result.put("origin", "sources-jar");
                result.put("source", bounded(fromSourcesJar, result));
                return result;
            }

            byte[] bytes = classFile.getBytes();
            ClassFileBytesDisassembler disassembler =
                ToolFactory.createDefaultClassFileBytesDisassembler();
            String stub = disassembler.disassemble(bytes, "\n",
                ClassFileBytesDisassembler.WORKING_COPY);
            result.put("origin", "disassembled-stub");
            result.put("source", "// DISASSEMBLED STUB — no source attachment and no "
                + "sibling -sources.jar exists for this type's container.\n"
                + "// Signatures are accurate; bodies are omitted. Fetching a sources jar "
                + "is an explicit user action (e.g. mvn dependency:sources), never done "
                + "silently.\n\n" + bounded(stub, result));
            return result;
        }
        return null;
    }

    /** {@code foo-1.0.jar} → {@code foo-1.0-sources.jar} beside it, existing only. */
    private static String readSiblingSourcesJar(IPackageFragmentRoot root, IType type) {
        try {
            Path jar = Path.of(root.getPath().toOSString());
            String name = jar.getFileName().toString();
            if (!name.endsWith(".jar")) return null;
            Path sourcesJar = jar.resolveSibling(
                name.substring(0, name.length() - 4) + "-sources.jar");
            if (!Files.isRegularFile(sourcesJar)) return null;
            String pkg = type.getPackageFragment().getElementName();
            String primary = type.getTypeQualifiedName();      // Outer$Nested
            int dollar = primary.indexOf('$');
            if (dollar > 0) primary = primary.substring(0, dollar);
            String entryName = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/")
                + primary + ".java";
            try (ZipFile zip = new ZipFile(sourcesJar.toFile())) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) return null;
                return new String(zip.getInputStream(entry).readAllBytes(),
                    StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String bounded(String source, Map<String, Object> result) {
        if (source == null) return "";
        if (source.length() <= MAX_SOURCE_CHARS) return source;
        result.put("truncated", true);
        return source.substring(0, MAX_SOURCE_CHARS) + "\n// …truncated…";
    }

    private LibrarySource() {}
}
