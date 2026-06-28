package org.goja.mcp.tools.build;

import org.goja.core.LoadedProject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 13 (v1.7.0) — Ring 3 build/dep helper. Locates and parses
 * {@code pom.xml} for a {@link LoadedProject}. Maven-only; Gradle support
 * arrives in v1.8.x.
 *
 * <p>Mutation tools (add/update) operate on the raw pom XML text (not a
 * DOM serialize round-trip) so user-formatted whitespace and comments
 * survive the edit. Read paths use {@link DocumentBuilder} for safety.</p>
 */
final class MavenPomSupport {

    private MavenPomSupport() {}

    /**
     * Locate {@code pom.xml} for the given project. Returns {@code null}
     * if the project isn't a Maven project (no {@code pom.xml} at the
     * project root).
     *
     * <p>Uses {@link LoadedProject#projectRoot()} (the original on-disk
     * path passed at load time) rather than {@code IProject.getLocation()},
     * because Eclipse's ProjectImporter creates virtual projects with
     * linked-folder source roots — the IProject's location may be the
     * Eclipse {@code .metadata} workspace folder, not the actual project
     * directory.</p>
     */
    static Path locatePom(LoadedProject loaded) {
        Path root = loaded.projectRoot();
        if (root == null) return null;
        Path pom = root.resolve("pom.xml");
        return Files.isRegularFile(pom) ? pom : null;
    }

    /**
     * Read all declared {@code <dependency>} entries from a pom file.
     * Includes {@code groupId}, {@code artifactId}, {@code version}, and
     * {@code scope} (defaults to {@code "compile"} when omitted on the
     * dependency).
     */
    static List<DeclaredDep> readDependencies(Path pomFile) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(pomFile.toFile());
        Element root = doc.getDocumentElement();

        List<DeclaredDep> result = new ArrayList<>();
        Element deps = childElement(root, "dependencies");
        if (deps == null) return result;
        NodeList depNodes = deps.getElementsByTagName("dependency");
        for (int i = 0; i < depNodes.getLength(); i++) {
            Node n = depNodes.item(i);
            if (!(n instanceof Element dep)) continue;
            String g = textOf(childElement(dep, "groupId"));
            String a = textOf(childElement(dep, "artifactId"));
            String v = textOf(childElement(dep, "version"));
            String s = textOf(childElement(dep, "scope"));
            if (g != null && a != null) {
                result.add(new DeclaredDep(g, a, v, s == null ? "compile" : s));
            }
        }
        return result;
    }

    /**
     * Whether the pom already declares a dependency matching
     * {@code groupId} + {@code artifactId}.
     */
    static boolean hasDependency(List<DeclaredDep> deps, String groupId, String artifactId) {
        for (DeclaredDep d : deps) {
            if (d.groupId.equals(groupId) && d.artifactId.equals(artifactId)) {
                return true;
            }
        }
        return false;
    }

    private static Element childElement(Element parent, String tagName) {
        if (parent == null) return null;
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n instanceof Element e && tagName.equals(e.getTagName())) {
                return e;
            }
        }
        return null;
    }

    private static String textOf(Element e) {
        if (e == null) return null;
        String text = e.getTextContent();
        return text == null ? null : text.trim();
    }

    /**
     * Single declared dependency entry from the pom.
     */
    record DeclaredDep(String groupId, String artifactId, String version, String scope) {}
}
