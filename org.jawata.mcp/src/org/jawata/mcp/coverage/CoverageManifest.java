package org.jawata.mcp.coverage;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 23 (D3) — the provenance side of a coverage artifact: WHAT was
 * measured, against WHICH bytes/revision, HOW the run ended. Serialized as
 * manifest.json beside the exec data; a report is only as trustworthy as
 * this record, so every field is written at collection time, never
 * reconstructed later.
 */
public class CoverageManifest {

    public String artifactId;
    public String createdAt;                 // ISO-8601
    public String jawataVersion;
    public String jacocoVersion;
    public String jdkVersion;

    /** Git provenance: revision + working-tree dirty fingerprint, or "unversioned". */
    public String gitRevision;
    public String dirtyFingerprint;

    public String projectKey;
    public String projectRoot;
    public List<String> classRoots = new ArrayList<>();
    public List<String> sourceRoots = new ArrayList<>();

    /** The test selection that produced the data. */
    public List<String> selectClasses = new ArrayList<>();
    public List<String> selectMethods = new ArrayList<>();
    public List<String> selectPackages = new ArrayList<>();
    public String framework;

    /**
     * Evidence kind: unit | integration | system | replay | manual —
     * explicit tag from the caller (default unit).
     */
    public String evidenceKind = "unit";

    /** Environment attribution (os/arch of the measuring host). */
    public String environment;

    /** Per-test segments were collected (Stage 9 attribution run). */
    public boolean attribution;

    /** Run outcome: did the evidence get finalized (run-finish + exit 0)? */
    public boolean runFinalized;
    public String completionStatus;          // FINALIZED | TIMED_OUT | CANCELLED | ABNORMAL

    /** Totals of the producing run. */
    public int testsTotal;
    public int testsPassed;
    public int testsFailed;
    public int testsSkipped;

    /**
     * Standing honesty marker: the measurement boundary. Code executed in
     * CHILD processes of the runner is outside this artifact — never
     * silently zero, always declared.
     */
    public String measurementBoundary =
        "runner JVM only — code executed in child processes forked by tests is "
            + "OUTSIDE this measurement";
}
