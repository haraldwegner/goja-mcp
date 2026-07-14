package org.jawata.core;

/**
 * The Java model failed while LISTING a project's source files — so any
 * "all files" collection built from that listing would be silently
 * incomplete, and every verdict computed over it ("no smells found",
 * "nothing unused", "0 naming violations") would be a statement about
 * files that were never enumerated, let alone read.
 *
 * <p>This is thrown instead of returning the partial list because a partial
 * listing is indistinguishable from a complete one at the call site: the
 * pre-fix behaviour returned whatever had been collected before the failure
 * (often nothing), logged the cause at warn — a NOP in the test harness, and
 * stderr nobody reads on the resident — and let the caller present an empty
 * scan as a clean bill of health. That exact shape produced the
 * LazyClassDetector "flaky test": a scan over ZERO listed files reported
 * {@code findings: []} as a successful, complete answer.</p>
 *
 * <p>Unchecked, so the existing tool surface propagates it into each tool's
 * top-level error handling — a LOUD, attributed failure — without forcing 28
 * call sites to re-declare it. Callers that can refuse with a qualified
 * diagnosis (the smell detectors) catch it explicitly.</p>
 */
public class SourceListingException extends RuntimeException {

    private final String projectName;

    public SourceListingException(String projectName, Throwable cause) {
        super("Listing the Java source files of project '" + projectName + "' FAILED ("
            + cause.getClass().getSimpleName()
            + (cause.getMessage() != null ? ": " + cause.getMessage() : "")
            + "). A file list built from this project would be incomplete, so no scan or"
            + " sweep may proceed on it. Run refresh_workspace and retry ONCE; if it fails"
            + " again, the workspace is unhealthy — tell the user instead of working around it.",
            cause);
        this.projectName = projectName;
    }

    public String projectName() {
        return projectName;
    }
}
