package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a whole-project scan actually managed to look at — and the refusal to pretend
 * otherwise.
 *
 * <p><b>The bug this exists to kill.</b> Every scanning tool in jawata used to walk the
 * project like this:</p>
 *
 * <pre>{@code
 * for (Path file : service.getAllJavaFiles()) {
 *     ICompilationUnit cu = service.getCompilationUnit(file);
 *     if (cu == null) continue;          // <-- silently skipped
 *     ...
 * }
 * return "found 0 problems";             // <-- a statement about files we never opened
 * }</pre>
 *
 * <p>{@code getCompilationUnit} returns null when the underlying JDT lookup FAILS — a
 * {@code JavaModelException} while the Java model is rebuilding, an unresolved classpath —
 * because the core swallows that exception. So under load a tool could skip every file it
 * listed and still answer "nothing found", which is not an absence but a <b>failure to
 * look</b>. It showed up as "flaky" tests that went green on a re-run, and a re-run is
 * exactly how a lying tool stays hidden.</p>
 *
 * <p><b>The contract.</b> We LISTED the files ourselves, so if we listed N and read fewer,
 * that gap IS the failure, whatever caused it. Therefore:</p>
 * <ul>
 *   <li>every response says what it examined ({@code filesListed} / {@code filesExamined}),
 *       so a zero can never be read as a clean bill of health;</li>
 *   <li>a scan that examined <b>nothing</b> is REFUSED — it has no verdict to give;</li>
 *   <li>a scan that missed <b>some</b> files reports its findings AND the gap, because real
 *       findings are worth having and a partial picture must not pass for the whole one.</li>
 * </ul>
 */
public final class SourceScan {

    private static final Logger log = LoggerFactory.getLogger(SourceScan.class);

    /** Enough to diagnose; not enough to drown the response. */
    private static final int MAX_LISTED_PATHS = 10;

    private final List<Path> files;
    private int examined;
    private final List<String> unresolvable = new ArrayList<>();
    private final List<String> unparseable = new ArrayList<>();

    private SourceScan(List<Path> files) {
        this.files = files;
    }

    /** Scan these files (the caller's own listing — usually {@code service.getAllJavaFiles()}). */
    public static SourceScan of(List<Path> files) {
        return new SourceScan(files == null ? List.of() : files);
    }

    public List<Path> files() {
        return files;
    }

    /**
     * Resolve a listed file, recording the miss if it cannot be resolved.
     *
     * @return the compilation unit, or null — and a null here is a RECORDED failure, not a
     *         file that quietly did not matter
     */
    public ICompilationUnit resolve(IJdtService service, Path file) {
        ICompilationUnit cu = service.getCompilationUnit(file);
        if (cu == null) {
            log.warn("Listed {} but could not resolve it — it was NOT scanned", file);
            unresolvable.add(String.valueOf(file));
        }
        return cu;
    }

    /** Parse with bindings, recording the failure if it cannot be parsed. */
    public CompilationUnit parse(ICompilationUnit cu, Path file, boolean bindingsRecovery) {
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            if (bindingsRecovery) {
                parser.setBindingsRecovery(true);
            }
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);
            if (ast == null) {
                unparseable.add(String.valueOf(file));
                return null;
            }
            return ast;
        } catch (Exception e) {
            log.warn("Parsing {} FAILED, so it was NOT scanned: {}: {}",
                file, e.getClass().getSimpleName(), e.getMessage());
            unparseable.add(String.valueOf(file));
            return null;
        }
    }

    /** Count a file we actually opened and looked at. */
    public void examined() {
        examined++;
    }

    public int listed() {
        return files.size();
    }

    public int examinedCount() {
        return examined;
    }

    public int missed() {
        return unresolvable.size() + unparseable.size();
    }

    public boolean incomplete() {
        return missed() > 0;
    }

    /**
     * Refuse to give a verdict on code we never opened.
     *
     * @param what what this scan was looking for, in the tool's own words (e.g. "duplicate
     *             code", "long methods") — so the refusal reads as a sentence
     * @return an error response when nothing at all could be examined, else empty
     */
    public Optional<ToolResponse> refuseIfBlind(String what) {
        if (examined > 0 || files.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(ToolResponse.error("SCAN_EXAMINED_NOTHING",
            "This scan listed " + listed() + " source file(s) and could not read a single one ("
                + unresolvable.size() + " unresolvable, " + unparseable.size() + " unparseable). "
                + "Reporting no " + what + " would be a statement about code we never opened. "
                + "Examples: " + firstFew(),
            "The Java model may still be rebuilding, or the project's classpath is broken. Run "
                + "refresh_workspace (or compile_workspace) and try again."));
    }

    /** The scan facts, to be merged into any response. */
    public Map<String, Object> describe() {
        Map<String, Object> described = new LinkedHashMap<>();
        described.put("filesListed", listed());
        described.put("filesExamined", examined);
        if (incomplete()) {
            described.put("filesMissed", missed());
            described.put("scanIncomplete", true);
            if (!unresolvable.isEmpty()) {
                described.put("unresolvable", cap(unresolvable));
            }
            if (!unparseable.isEmpty()) {
                described.put("unparseable", cap(unparseable));
            }
        }
        return described;
    }

    /** The sentence that stops a zero from reading as a clean bill of health. */
    public String steering(int found, String what) {
        if (incomplete()) {
            return "PARTIAL SCAN: " + missed() + " of " + listed() + " file(s) could not be read, "
                + "so this is what survived — not what exists. "
                + (found == 0
                    ? "In particular, 'no " + what + "' here is NOT a clean bill of health."
                    : "There may be more in the files we could not open.")
                + " Run refresh_workspace and re-run for a complete answer.";
        }
        if (found == 0) {
            return "No " + what + " — and the scan was COMPLETE (" + examined + " file(s) "
                + "examined), so this is a real absence, not a failure to look.";
        }
        return null;
    }

    private String firstFew() {
        List<String> all = new ArrayList<>(unresolvable);
        all.addAll(unparseable);
        return String.valueOf(cap(all));
    }

    private static List<String> cap(List<String> paths) {
        return paths.size() <= MAX_LISTED_PATHS
            ? List.copyOf(paths)
            : List.copyOf(paths.subList(0, MAX_LISTED_PATHS));
    }
}
