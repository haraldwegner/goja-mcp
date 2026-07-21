#!/usr/bin/env bash
# Sprint 23 (D2) — sharded full-suite runner: partition the in-framework test
# classes across N boot JVMs, balanced by MEASURED per-class times (the C0
# baseline timings file; unknown classes get a default estimate), run the
# shards in parallel, merge the summaries. Exit != 0 when any test fails or
# any shard dies.
#
# Usage:  build/run-suite.sh [shards]        (default 4)
# Env:    TIMINGS=<file>  DEFAULT_SECS=<n>   (default 15)
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SHARDS="${1:-4}"
TIMINGS="${TIMINGS:-$ROOT/docs/sprints/dossier-23-timings.txt}"
DEFAULT_SECS="${DEFAULT_SECS:-15}"
DIST="$ROOT/build/dist/target/dist"
FIXTURES="$ROOT/org.jawata.core.tests/test-resources/sample-projects"
OUT="$DIST/suite-shards"

[ -f "$DIST/jawata.jar" ] || { echo "FATAL: dist not built ($DIST/jawata.jar)"; exit 2; }

# issue #1: refuse a contaminated dist BEFORE partitioning — a stale
# prior-version org.jawata jar beside the current one silently shadows fresh
# code (the shards run in classlist mode, which skips the boot's discovery
# guard, so this check is the sharded run's only protection).
for d in "$DIST/bundles" "$DIST/test-bundles"; do
    dupes=$(ls "$d"/org.jawata.*.jar 2>/dev/null | sed 's|.*/||; s|-[0-9][^-]*\.jar$||' | sort | uniq -d)
    if [ -n "$dupes" ]; then
        echo "FATAL (issue #1): two versions of the same org.jawata bundle in $d:"
        ls "$d"/org.jawata.*.jar | sed 's|.*/|  |'
        echo "A stale jar can silently shadow the current one. Rebuild the dist (the purge step removes leftovers)."
        exit 2
    fi
done

rm -rf "$OUT"; mkdir -p "$OUT"

# 1. Discover test classes exactly like the boot does (org.jawata.* test
#    bundles, top-level *Test.class).
ALL_CLASSES="$OUT/all-classes.txt"
for jar in "$DIST"/test-bundles/org.jawata.*.jar; do
    unzip -Z1 "$jar" | grep 'Test\.class$' | grep -v '\$' | sed 's|/|.|g; s|\.class$||'
done | sort -u > "$ALL_CLASSES"
TOTAL_CLASSES=$(wc -l < "$ALL_CLASSES")
echo "Discovered $TOTAL_CLASSES test classes across $SHARDS shards"

# 2. Greedy balance by measured time (longest-first onto the lightest shard).
awk -v shards="$SHARDS" -v deflt="$DEFAULT_SECS" -v timings="$TIMINGS" -v out="$OUT" '
BEGIN {
    while ((getline line < timings) > 0) {
        n = split(line, f, " ");
        if (n >= 2) { t = f[1]; sub(/s$/, "", t); byName[f[2]] = t + 0; }
    }
}
{
    simple = $0; sub(/.*\./, "", simple);
    secs = (simple in byName) ? byName[simple] : deflt;
    names[NR] = $0; times[NR] = secs; total++;
}
END {
    # selection sort desc (small N)
    for (i = 1; i <= total; i++) idx[i] = i;
    for (i = 1; i <= total; i++)
        for (j = i + 1; j <= total; j++)
            if (times[idx[j]] > times[idx[i]]) { tmp = idx[i]; idx[i] = idx[j]; idx[j] = tmp; }
    for (s = 0; s < shards; s++) load[s] = 0;
    for (i = 1; i <= total; i++) {
        best = 0;
        for (s = 1; s < shards; s++) if (load[s] < load[best]) best = s;
        load[best] += times[idx[i]];
        print names[idx[i]] >> (out "/shard-" best ".txt");
    }
    for (s = 0; s < shards; s++)
        printf "shard %d: %ds planned\n", s, load[s] > "/dev/stderr";
}' "$ALL_CLASSES"

# 3. Launch the shards in parallel. Each JVM is told its FAIR SHARE of the
#    cores — otherwise every shard sizes GC/JIT/pool threads for the whole
#    machine and they thrash each other (measured: 6 unpinned shards were
#    SLOWER than 4).
CORES=$(nproc)
SLICE=$(( CORES / SHARDS )); [ "$SLICE" -lt 2 ] && SLICE=2
JVM_OPTS="${JVM_OPTS:--XX:ActiveProcessorCount=$SLICE -Xmx3g}"
# Sprint 27 D1: the embedder's Vector API backend, GUARDED — a JVM given
# --add-modules for a module it lacks refuses to start (exit 1), so probing
# beats assuming. Without it the tests still pass on the scalar backend; the
# suite is simply slower where it embeds.
#
# JAWATA_VECTOR=0 forces the scalar path. That exists so the flagless run is
# actually runnable: it is the configuration every user without the flag gets,
# and a suite that has only ever run one way has not tested the other.
if [ "${JAWATA_VECTOR:-1}" = "0" ]; then
    echo "note: JAWATA_VECTOR=0 — running the SCALAR backend deliberately" >&2
elif java --add-modules jdk.incubator.vector -version >/dev/null 2>&1; then
    JVM_OPTS="--add-modules jdk.incubator.vector $JVM_OPTS"
else
    echo "note: jdk.incubator.vector is not available in this JVM — the suite" \
         "runs on the scalar backend (correct, slower)" >&2
fi
START=$(date +%s)
PIDS=()
for s in $(seq 0 $((SHARDS - 1))); do
    java $JVM_OPTS \
         -Djawata.test.fixtures="$FIXTURES" \
         -Djawata.test.classlist="$OUT/shard-$s.txt" \
         -jar "$DIST/jawata.jar" -runTests > "$OUT/shard-$s.log" 2>&1 &
    PIDS+=($!)
done

FAILED_SHARDS=0
for i in "${!PIDS[@]}"; do
    wait "${PIDS[$i]}" || FAILED_SHARDS=$((FAILED_SHARDS + 1))
done
WALL=$(( $(date +%s) - START ))

# 4. Merge the summaries.
TOT=0; PASS=0; FAIL=0; ABORT=0; SKIP=0; UNLOAD=0; SUMMARIES=0
for s in $(seq 0 $((SHARDS - 1))); do
    line=$(grep 'SPIKE-TESTS' "$OUT/shard-$s.log" | tail -1)
    if [ -z "$line" ]; then
        echo "shard $s: NO SUMMARY (crashed?) — tail:"; tail -5 "$OUT/shard-$s.log"
        continue
    fi
    SUMMARIES=$((SUMMARIES + 1))
    echo "shard $s: $line"
    TOT=$((TOT + $(sed 's/.*total=\([0-9]*\).*/\1/' <<< "$line")))
    PASS=$((PASS + $(sed 's/.*succeeded=\([0-9]*\).*/\1/' <<< "$line")))
    FAIL=$((FAIL + $(sed 's/.*failed=\([0-9]*\).*/\1/' <<< "$line")))
    ABORT=$((ABORT + $(sed 's/.*aborted=\([0-9]*\).*/\1/' <<< "$line")))
    SKIP=$((SKIP + $(sed 's/.*skipped=\([0-9]*\).*/\1/' <<< "$line")))
    UNLOAD=$((UNLOAD + $(sed 's/.*unloadable=\([0-9]*\).*/\1/' <<< "$line")))
done

echo "SHARDED-SUITE shards=$SHARDS wall=${WALL}s total=$TOT succeeded=$PASS failed=$FAIL aborted=$ABORT skipped=$SKIP unloadable=$UNLOAD"
[ "$SUMMARIES" -eq "$SHARDS" ] || { echo "FAILED: $((SHARDS - SUMMARIES)) shard(s) produced no summary"; exit 3; }
[ "$FAIL" -eq 0 ] && [ "$UNLOAD" -eq 0 ] || exit 1
exit 0
