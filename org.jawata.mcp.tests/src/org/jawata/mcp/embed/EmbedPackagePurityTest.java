package org.jawata.mcp.embed;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Sprint 27 D1 — the {@code embed} package must stay PURE: it may depend on the
 * JDK and on nothing else in jawata.
 *
 * <p>Purity is a load-bearing architectural claim, not tidiness. The dependency
 * direction {@code embed ← knowledge ← learn ← tools} is what lets the embedder
 * be tested, reasoned about and replaced in isolation, and what keeps a model
 * swap from reaching into the store or the choke. A single convenience import
 * would quietly invert it, so the rule is asserted rather than documented.</p>
 *
 * <p>The check reads the compiled CLASS FILES rather than the sources: a class
 * file's constant pool names every type the class actually references,
 * including ones used only inside method bodies, which an import-scan of the
 * source would miss entirely.</p>
 */
class EmbedPackagePurityTest {

    private static final String[] EMBED_CLASSES = {
        "org.jawata.mcp.embed.WordPieceTokenizer",
        "org.jawata.mcp.embed.MatMul",
        "org.jawata.mcp.embed.ScalarMatMul",
        "org.jawata.mcp.embed.MatMuls",
        // VectorApiMatMul IS included even though it is only LINKABLE on a JVM
        // launched with the incubator module: this check reads the class file
        // as a resource and never loads the class, so its bytes are inspectable
        // on any JVM. Excluding it would have left the one backend most likely
        // to reach for a shortcut unchecked.
        "org.jawata.mcp.embed.VectorApiMatMul",
        "org.jawata.mcp.embed.SafeTensors",
        "org.jawata.mcp.embed.EmbedderIdentity",
        "org.jawata.mcp.embed.MiniLmEmbedder",
    };

    @Test
    void no_embed_class_references_any_other_jawata_package() throws Exception {
        Map<String, List<String>> violations = new LinkedHashMap<>();
        for (String fqn : EMBED_CLASSES) {
            byte[] bytes = classBytes(fqn);
            assertNotNull(bytes, "class must be on the test classpath: " + fqn);
            List<String> bad = foreignJawataReferences(bytes);
            if (!bad.isEmpty()) {
                violations.put(fqn, bad);
            }
        }
        assertTrue(violations.isEmpty(),
            "the embed package must depend on nothing else in jawata, but found: " + violations);
    }

    @Test
    void the_purity_check_can_actually_fail() {
        // A check that cannot fail proves nothing. This class itself lives in
        // the embed PACKAGE but is a test and does reference other jawata code
        // via the classes under test - so scanning a known-impure class must
        // report something. Here we use a store class as the positive control.
        byte[] bytes = classBytes("org.jawata.mcp.knowledge.ExperienceRetrieval");
        // Deliberately NOT a silent skip. A control that quietly opts out when
        // it cannot find its subject turns the gate above into a test that can
        // only pass - the exact shape of hollow gate this project has been
        // burned by. If the control cannot run, the checkpoint must know.
        assertNotNull(bytes, "purity CONTROL could not load its subject class; "
            + "the purity assertion above would be unverified");
        List<String> found = foreignJawataReferences(bytes);
        assertTrue(!found.isEmpty(),
            "the scanner must detect cross-package jawata references, "
            + "otherwise the purity assertion above is vacuous");
        System.out.println("[EmbedPackagePurityTest] control saw " + found.size()
            + " foreign refs, e.g. " + found.get(0));
    }

    private static byte[] classBytes(String fqn) {
        String path = "/" + fqn.replace('.', '/') + ".class";
        try (InputStream in = EmbedPackagePurityTest.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Every {@code org/jawata/...} name in the constant pool that is not itself
     * in {@code org/jawata/mcp/embed}. Scanning the raw bytes for the prefix is
     * enough: class and signature entries are stored as plain UTF-8.
     */
    private static List<String> foreignJawataReferences(byte[] bytes) {
        String blob = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> found = new ArrayList<>();
        String needle = "org/jawata/";
        int at = blob.indexOf(needle);
        while (at >= 0) {
            int end = at;
            while (end < blob.length() && isTypeChar(blob.charAt(end))) {
                end++;
            }
            String ref = blob.substring(at, end);
            if (!ref.startsWith("org/jawata/mcp/embed/") && !found.contains(ref)) {
                found.add(ref);
            }
            at = blob.indexOf(needle, at + 1);
        }
        return found;
    }

    private static boolean isTypeChar(char c) {
        return Character.isLetterOrDigit(c) || c == '/' || c == '_' || c == '$';
    }
}
