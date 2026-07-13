package org.jawata.mcp.coverage;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IClassCoverage;
import org.jacoco.core.analysis.ICounter;
import org.jacoco.core.analysis.ILine;
import org.jacoco.core.analysis.IMethodCoverage;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.tools.ExecFileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 23 (D3) — the analyzed coverage report: exec data matched against
 * the CURRENT class bytes under the recorded class roots, classified into
 * the honest result states, indexed per symbol for cheap repeated lookups.
 *
 * <p>The EIGHT result states and where they surface:</p>
 * <ol>
 *   <li><b>covered</b> / <li><b>missed</b> — line/branch counters;</li>
 *   <li><b>non-executable</b> — lines with no instructions (ILine EMPTY);</li>
 *   <li><b>generated-or-excluded</b> — constructs JaCoCo's synthetic filters
 *       drop (bridges, enum boilerplate, records' generated members): absent
 *       from the model BY DESIGN, reported as this state when queried;</li>
 *   <li><b>not-instrumented</b> — the class never loaded in the measured run
 *       (no execution data recorded for its name);</li>
 *   <li><b>instrumentation-failure</b> — the artifact has no usable exec
 *       data at all (agent failed); artifact-level;</li>
 *   <li><b>stale-bytes</b> — exec data exists for the class name but the
 *       class id (byte checksum) differs from the CURRENT bytes: the report
 *       would lie, so it is REFUSED for that class;</li>
 *   <li><b>unknown-run-failed</b> — the producing run never finalized its
 *       evidence (timeout/crash/cancel); artifact-level.</li>
 * </ol>
 *
 * <p>Same-FQN-in-two-bundles: classes are keyed by (classRoot, vmName) —
 * two bundles' outputs analyze as SEPARATE facts, each with its own root
 * (bundle identity).</p>
 */
public final class CoverageModel {

    private static final Logger log = LoggerFactory.getLogger(CoverageModel.class);

    public enum State {
        COVERED, MISSED, NON_EXECUTABLE, GENERATED_OR_EXCLUDED,
        NOT_INSTRUMENTED, INSTRUMENTATION_FAILURE, STALE_BYTES, UNKNOWN_RUN_FAILED
    }

    public static final class MethodCov {
        public String name;
        public String desc;
        public int firstLine;
        public int lastLine;
        public State state;
        public int linesCovered;
        public int linesMissed;
        public int branchesCovered;
        public int branchesMissed;
        public List<Integer> uncoveredLines = new ArrayList<>();
        public List<Integer> partlyCoveredLines = new ArrayList<>();
        public List<Integer> coveredLines = new ArrayList<>();
    }

    public static final class ClassCov {
        public String fqn;               // dotted name
        public String classRoot;         // bundle identity: which output tree
        public String sourceFile;
        public State state;              // ANALYZED classes carry per-line detail
        public List<MethodCov> methods = new ArrayList<>();
        public int linesCovered;
        public int linesMissed;
        public int branchesCovered;
        public int branchesMissed;
    }

    public final CoverageManifest manifest;
    /** (classRoot, fqn) → class coverage — bundle identity preserved. */
    public final List<ClassCov> classes = new ArrayList<>();
    /** fqn#method → method coverage (first root wins for duplicate FQNs). */
    private final Map<String, MethodCov> symbolIndex = new HashMap<>();
    private final Map<String, ClassCov> classIndex = new HashMap<>();
    public boolean instrumentationFailure;

    private CoverageModel(CoverageManifest manifest) {
        this.manifest = manifest;
    }

    /** Analyze an artifact against the CURRENT bytes under its class roots. */
    public static CoverageModel analyze(Path execFile, CoverageManifest manifest)
            throws IOException {
        CoverageModel model = new CoverageModel(manifest);

        ExecFileLoader loader = new ExecFileLoader();
        if (Files.isRegularFile(execFile) && Files.size(execFile) > 0) {
            loader.load(execFile.toFile());
        } else {
            model.instrumentationFailure = true;
        }
        ExecutionDataStore execStore = loader.getExecutionDataStore();
        Set<String> executedClassNames = new HashSet<>();
        for (ExecutionData data : execStore.getContents()) {
            executedClassNames.add(data.getName());
        }

        for (String rootStr : manifest.classRoots) {
            Path rootPath = Path.of(rootStr);
            if (!Files.isDirectory(rootPath)) continue;
            CoverageBuilder builder = new CoverageBuilder();
            Analyzer analyzer = new Analyzer(execStore, builder);
            try {
                analyzer.analyzeAll(rootPath.toFile());
            } catch (IOException e) {
                log.warn("coverage analysis failed under {}: {}", rootPath, e.getMessage());
                continue;
            }
            for (IClassCoverage cc : builder.getClasses()) {
                ClassCov clazz = new ClassCov();
                clazz.fqn = cc.getName().replace('/', '.');
                clazz.classRoot = rootStr;
                clazz.sourceFile = cc.getSourceFileName();
                boolean hasExecutable = cc.getLineCounter().getTotalCount() > 0;
                if (cc.isNoMatch()) {
                    // Execution data exists for this NAME but the current
                    // bytes have a different id — reporting would lie.
                    clazz.state = State.STALE_BYTES;
                } else if (!hasExecutable) {
                    // Nothing to execute (marker interfaces, constants-only):
                    // more informative than "never loaded".
                    clazz.state = State.NON_EXECUTABLE;
                } else if (!executedClassNames.contains(cc.getName())) {
                    clazz.state = State.NOT_INSTRUMENTED;
                } else {
                    clazz.state = classState(cc);
                    fillDetail(cc, clazz);
                }
                model.classes.add(clazz);
                model.classIndex.putIfAbsent(clazz.fqn, clazz);
                for (MethodCov m : clazz.methods) {
                    model.symbolIndex.putIfAbsent(clazz.fqn + "#" + m.name, m);
                }
            }
        }
        return model;
    }

    private static State classState(IClassCoverage cc) {
        int covered = cc.getLineCounter().getCoveredCount();
        int missed = cc.getLineCounter().getMissedCount();
        if (covered == 0 && missed == 0) return State.NON_EXECUTABLE;
        return covered > 0 ? State.COVERED : State.MISSED;
    }

    private static void fillDetail(IClassCoverage cc, ClassCov clazz) {
        clazz.linesCovered = cc.getLineCounter().getCoveredCount();
        clazz.linesMissed = cc.getLineCounter().getMissedCount();
        clazz.branchesCovered = cc.getBranchCounter().getCoveredCount();
        clazz.branchesMissed = cc.getBranchCounter().getMissedCount();
        for (IMethodCoverage mc : cc.getMethods()) {
            MethodCov m = new MethodCov();
            m.name = mc.getName();
            m.desc = mc.getDesc();
            m.firstLine = mc.getFirstLine();
            m.lastLine = mc.getLastLine();
            m.linesCovered = mc.getLineCounter().getCoveredCount();
            m.linesMissed = mc.getLineCounter().getMissedCount();
            m.branchesCovered = mc.getBranchCounter().getCoveredCount();
            m.branchesMissed = mc.getBranchCounter().getMissedCount();
            if (m.linesCovered == 0 && m.linesMissed == 0) {
                m.state = State.NON_EXECUTABLE;
            } else {
                m.state = m.linesCovered > 0 ? State.COVERED : State.MISSED;
            }
            for (int line = mc.getFirstLine(); line <= mc.getLastLine() && line >= 0; line++) {
                ILine l = mc.getLine(line);
                switch (l.getStatus()) {
                    case ICounter.NOT_COVERED -> m.uncoveredLines.add(line);
                    case ICounter.PARTLY_COVERED -> m.partlyCoveredLines.add(line);
                    case ICounter.FULLY_COVERED -> m.coveredLines.add(line);
                    default -> { }
                }
            }
            clazz.methods.add(m);
        }
    }

    public ClassCov classOf(String fqn) {
        return classIndex.get(fqn);
    }

    /** Cheap indexed lookup: fqn#method → coverage, or null. */
    public MethodCov method(String fqnHashMethod) {
        return symbolIndex.get(fqnHashMethod);
    }

    /** All same-FQN facts across roots (bundle identity). */
    public List<ClassCov> allOf(String fqn) {
        List<ClassCov> out = new ArrayList<>();
        for (ClassCov c : classes) {
            if (c.fqn.equals(fqn)) out.add(c);
        }
        return out;
    }
}
