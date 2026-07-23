#!/usr/bin/env bash
# end-to-end-test.sh — the end-to-end test: prove each promise works in the
# PRODUCT, not in unit tests.
#
# WHY THIS EXISTS
# ---------------
# v3.4.0 was released with its central feature completely inert. The unit suite
# was 1591/1591 four different ways and coverage ROSE on all three ratchets,
# because every test constructed the recall engine and handed it the embedding
# index by hand. Production never did. No test could notice: they were each
# supplying the very wiring that was missing.
#
# This script cannot do that. It has one way in — the same JSON-RPC endpoint an
# editor uses — so it can only ask the product to do the thing it claims. It
# starts the BUILT ARTIFACT, on a THROWAWAY store, asserts one live claim per
# deliverable, and tears the resident down.
#
# Sprint 27a (Stage 7): the store is POPULATED from the committed fixture
# (build/e2e-fixture/entries.json — invented entries, hash-compared pristine
# after the run), and the resident is started THREE times against the same
# store: lifecycle 1 seeds and checks the write side; lifecycle 2 proves the
# startup reconciliation embeds the restored rows and checks the read side;
# lifecycle 3 runs with the embedder DISABLED and proves the degrade paths.
# Checks new in 27a are labelled "27a-..." — they are the audited reasons this
# script must be RED against a v3.4.1 dist and GREEN against this build.
#
# It runs BEFORE the release sign-off ask, and its output belongs IN that ask.
#
# Usage:  build/end-to-end-test.sh [path/to/dist]
# Exit:   0 = every claim held · 1 = a claim failed · 2 = could not run at all
#         ("could not run" is never reported as a pass)

set -uo pipefail

DIST="${1:-build/dist/target/dist}"
JAR="$DIST/jawata.jar"
PORT="${JAWATA_GATE_PORT:-8899}"
TOKEN="end-to-end-test-$$"
WS="$(mktemp -d)"                 # throwaway workspace AND store: the gate must
STORE="$(mktemp -d)"              # never read or write the developer's real one
LOG="$WS/resident.log"
RESIDENT_PID=""
FIXTURE="$(cd "$(dirname "$0")" && pwd)/e2e-fixture/entries.json"

cleanup() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    rm -rf "$WS" "$STORE"
}
trap cleanup EXIT

fail() { printf '  FAIL  %s\n' "$1"; FAILED=$((FAILED + 1)); }
pass() { printf '  ok    %s\n' "$1"; PASSED=$((PASSED + 1)); }
FAILED=0
PASSED=0

[ -f "$JAR" ] || { echo "no artifact at $JAR — build first" >&2; exit 2; }
[ -f "$FIXTURE" ] || { echo "no fixture at $FIXTURE" >&2; exit 2; }
FIXTURE_SHA_BEFORE="$(sha256sum "$FIXTURE" | cut -d' ' -f1)"

VECTOR=""
java --add-modules jdk.incubator.vector -version >/dev/null 2>&1 \
    && VECTOR="--add-modules jdk.incubator.vector"

start_resident() {   # start_resident [extra JVM args...]
    LOG="$WS/resident-$((PASSED + FAILED)).log"
    # shellcheck disable=SC2086
    java $VECTOR "$@" -Djawata.experience.shared.dir="$STORE" \
         -jar "$JAR" -data "$WS/ws" -port "$PORT" -token "$TOKEN" > "$LOG" 2>&1 &
    RESIDENT_PID=$!
    for _ in $(seq 1 120); do
        grep -q "READY\|Server started\|listening" "$LOG" 2>/dev/null && break
        kill -0 "$RESIDENT_PID" 2>/dev/null || { echo "resident died on startup:" >&2
                                                 tail -20 "$LOG" >&2; exit 2; }
        sleep 1
    done
    call health_check '{}' | grep -q '"status"' \
        || { echo "resident never answered tools/call:" >&2; tail -20 "$LOG" >&2; exit 2; }
}

stop_resident() {
    [ -n "$RESIDENT_PID" ] && kill "$RESIDENT_PID" 2>/dev/null
    [ -n "$RESIDENT_PID" ] && wait "$RESIDENT_PID" 2>/dev/null
    RESIDENT_PID=""
}

call() {   # call <tool> <json-args> -> the tool's answer, UNESCAPED
    # A tool result arrives as JSON nested inside a JSON string, so every quote
    # comes back backslash-escaped. Unescape before matching: a pattern written
    # in the readable form silently never matches the wire form — which would
    # make this gate fail everything, or worse, pass everything.
    curl -s --max-time 180 -X POST "http://127.0.0.1:$PORT/mcp" \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: e2e-$$" \
        -H 'Content-Type: application/json' \
        -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",
             \"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
        | sed 's/\\"/"/g'
}

call_file() {   # call_file <tool> <file-with-json-args> — for large payloads
    local body="$WS/body.json"
    python3 - "$1" "$2" > "$body" << 'PY'
import json, sys
tool, argfile = sys.argv[1], sys.argv[2]
print(json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                  "params": {"name": tool, "arguments": json.load(open(argfile))}}))
PY
    curl -s --max-time 180 -X POST "http://127.0.0.1:$PORT/mcp" \
        -H "Authorization: Bearer $TOKEN" -H "Mcp-Session-Id: e2e-$$" \
        -H 'Content-Type: application/json' \
        -d @"$body" | sed 's/\\"/"/g'
}

no_score() {   # 27a: no similarity number in any user-facing payload
    if printf '%s' "$2" | grep -qE '0\.[0-9]{2}'; then
        fail "27a-noscore $1 leaked a similarity number"
    else
        pass "27a-noscore $1 carries no similarity number"
    fi
}

echo "end-to-end test — against $JAR"

# ============================ lifecycle 1 ====================================
start_resident

# --- D1: the embedder is loaded AND says which backend actually won ---------
H="$(call health_check '{}')"
case "$H" in
    *'"available":true'*) pass "D1 embedder available; backend reported" ;;
    *) fail "D1 embedder not available in the running product" ;;
esac

# --- D2: recall by MEANING, the release's central claim ---------------------
call experience '{"kind":"record","type":"lesson",
  "summary":"the roof leaked because nobody swept the gutters in autumn",
  "operation":"end-to-end-test","language":"process"}' >/dev/null
R="$(call experience '{"kind":"recall",
  "symptom":"water came through the ceiling after the drains clogged with leaves",
  "format":"text"}')"
case "$R" in
    *gutters*) pass "D2 a paraphrase sharing no words with the entry found it" ;;
    *) fail "D2 RECALL BY MEANING IS NOT RUNNING IN THE PRODUCT" ;;
esac

# --- D5: recording a near-duplicate proposes a merge ------------------------
D="$(call experience '{"kind":"record","type":"lesson",
  "summary":"the roof leaked because nobody swept the gutters in autumn",
  "operation":"end-to-end-test","language":"process"}')"
case "$D" in
    *duplicate_of*) pass "D5 a re-recorded entry is flagged as a duplicate" ;;
    *) fail "D5 write-path dedup did not fire on an identical entry" ;;
esac

# --- D6: the counters actually move, and say how to read themselves --------
S="$(call experience '{"kind":"stats"}')"
case "$S" in
    *unavailable*)      fail "D6 the counter table is missing on this store" ;;
    *'"fired.'*|*question_hook*)
                        pass "D6 counters advanced from the calls above" ;;
    *)                  fail "D6 no counter moved despite live recalls" ;;
esac
case "$S" in
    *CORRELATION*) pass "D6 the counts carry their how-to-read sentence" ;;
    *) fail "D6 counts rendered without the correlation label" ;;
esac

# --- 27a: import the committed fixture through the front door ----------------
python3 - "$FIXTURE" > "$WS/import-args.json" << 'PY'
import json, sys
entries = json.load(open(sys.argv[1]))["data"]["entries"]
print(json.dumps({"kind": "import", "entries": entries}))
PY
IMP="$(call_file experience "$WS/import-args.json")"
case "$IMP" in
    *'"imported":48'*) pass "27a-fixture the 48 committed entries imported" ;;
    *) fail "27a-fixture import did not land 48 entries: $(printf '%s' "$IMP" | head -c 200)" ;;
esac

# --- 27a-D5cov: a restored store is honestly PART-embedded ------------------
S1="$(call experience '{"kind":"stats"}')"
EMB="$(printf '%s' "$S1" | grep -o '"embedding".\{0,220\}')"
if [ -z "$EMB" ]; then
    fail "27a-D5cov stats carries no embedding block (v3.4.1 shape)"
else
    EMB_N="$(printf '%s' "$EMB" | grep -oE '"embedded":[0-9]+' | head -1 | cut -d: -f2)"
    TOT_N="$(printf '%s' "$EMB" | grep -oE '"total":[0-9]+' | head -1 | cut -d: -f2)"
    if [ -n "$EMB_N" ] && [ -n "$TOT_N" ] && [ "$EMB_N" -lt "$TOT_N" ]; then
        pass "27a-D5cov stats shows the restored rows honestly unembedded ($EMB_N/$TOT_N)"
    else
        fail "27a-D5cov expected n<total right after a restore, got ${EMB_N:-?}/${TOT_N:-?}"
    fi
fi

# --- 27a-D10: a wrong-kind record is refused with the teaching redirect -----
A="$(call experience '{"kind":"record","type":"lesson",
  "summary":"a lesson about the ordering notes",
  "symptoms":["client-app/docs/ordering-notes.md"]}')"
case "$A" in
    *REPHRASE*) pass "27a-D10 a path standing as a symptom is refused with the teaching message" ;;
    *'"stored":true'*) fail "27a-D10 THE ADMISSION GATE IS NOT RUNNING (garbage stored)" ;;
    *) fail "27a-D10 unexpected admission response: $(printf '%s' "$A" | head -c 200)" ;;
esac

# --- 27a-D4: a genuine paraphrase in different words is NOT flagged ---------
# (the corrected release-note claim, live: high-precision dedup only ever
# proposes, and only for near-identical wording)
P="$(call experience '{"kind":"record","type":"lesson",
  "summary":"crawling glaze at cone six traces back to dust left on the pots",
  "operation":"end-to-end-test"}')"
case "$P" in
    *duplicate_of*) fail "27a-D4 a genuine paraphrase was flagged — the corrected claim is false" ;;
    *'"stored":true'*) pass "27a-D4 a paraphrase in different words is admitted unflagged" ;;
    *) fail "27a-D4 unexpected record response" ;;
esac

# --- 27a-load: the memory-file ingest reports its routing -------------------
printf -- "---\nname: e2e-load-probe\ndescription: a probe note for the load report\ntype: lesson\n---\nThe \`WidgetRenderer.paint()\` call fails on scale change.\n\n## Root cause:\n\nThe **native buffer** is sized before the scale factor arrives.\n" > "$WS/mem.md"
L="$(call experience "{\"kind\":\"load\",\"path\":\"$WS/mem.md\"}")"
case "$L" in
    *keywords_suppressed*) pass "27a-load the load report carries the route/skip count" ;;
    *) fail "27a-load NO ROUTE/SKIP REPORT from the ingest (v3.4.1 shape)" ;;
esac

stop_resident

# ============================ lifecycle 2 ====================================
# Same store, fresh resident: the STARTUP RECONCILIATION must embed the
# restored rows to convergence — D5's second half, proven on the artifact.
start_resident

EMB_N=""; TOT_N=""; CONVERGED=""
for _ in $(seq 1 60); do
    S2="$(call experience '{"kind":"stats"}')"
    EMB_N="$(printf '%s' "$S2" | grep -oE '"embedded":[0-9]+' | head -1 | cut -d: -f2)"
    TOT_N="$(printf '%s' "$S2" | grep -oE '"total":[0-9]+' | head -1 | cut -d: -f2)"
    if [ -n "$EMB_N" ] && [ -n "$TOT_N" ] && [ "$TOT_N" -gt 0 ] && [ "$EMB_N" -eq "$TOT_N" ]; then
        CONVERGED="yes"; break
    fi
    sleep 3
done
if [ -n "$CONVERGED" ]; then
    pass "27a-D5cov2 the startup reconciliation converged ($EMB_N/$TOT_N)"
else
    fail "27a-D5cov2 the backfill never reached total/total (${EMB_N:-?}/${TOT_N:-?} after 180s)"
fi

# --- 27a-D1a: fixture knowledge is reachable by MEANING ---------------------
M="$(call experience '{"kind":"recall",
  "symptom":"my sourdough fell in on itself after I let it rise for too long",
  "format":"text"}')"
case "$M" in
    *poke-test*|*sourdough*) pass "27a-D1a a fixture lesson is found by meaning after the restore" ;;
    *) fail "27a-D1a the restored fixture is invisible to meaning recall" ;;
esac
no_score "recall(meaning)" "$M"

# --- 27a-D1b: nonsense produces NO VOUCHED ANSWER, nominees labelled --------
N="$(call experience '{"kind":"recall",
  "symptom":"the marzipan barometer forgot its velvet inventory",
  "format":"text"}')"
case "$N" in
    *'"result":"match"'*) fail "27a-D1b nonsense produced a VOUCHED answer" ;;
    *) pass "27a-D1b nonsense is never vouched" ;;
esac
case "$N" in
    *meaning-near*|*"shares distinctive wording"*|*analogy*)
        pass "27a-D1b whatever nonsense surfaces is labelled a nominee (basis in words)" ;;
    *) fail "27a-D1b nominees rendered without their basis labels" ;;
esac
no_score "recall(nonsense)" "$N"

# --- 27a-D6r: a rejected note stays gone BY MEANING -------------------------
G="$(call experience '{"kind":"recall",
  "symptom":"when in the lunar cycle is the right time to prune fruit trees",
  "format":"text"}')"
case "$G" in
    *"moon phase determines"*) fail "27a-D6r the REJECTED note came back through the meaning path" ;;
    *) pass "27a-D6r the rejected note stays gone by meaning" ;;
esac

# --- 27a-D2d: dispatch rides recall — the seeded seat run is found ----------
DS="$(call experience '{"kind":"recall",
  "symptom":"how was the scheduler retry loop covered before its refactor",
  "format":"json"}')"
case "$DS" in
    *dispatch*) pass "27a-D2d the seeded seat run arrives dispatch-decorated" ;;
    *) fail "27a-D2d no dispatch decoration on the seat-run recall" ;;
esac
no_score "recall(dispatch)" "$DS"

# --- 27a-choke: the LIVE warning cycle on a real project --------------------
# fire (a reverted refactor becomes a precedent) → warn (advisory steer,
# uncharged) → charge (the identity tier refuses an unjustified repeat) →
# pay (a written justification proceeds) → the outcome-after counter fills.
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cp -r "$REPO_ROOT/org.jawata.core.tests/test-resources/sample-projects/compile-clean" "$WS/proj"
LP="$(call load_project "{\"projectPath\":\"$WS/proj\"}")"
case "$LP" in
    *'"success":true'*|*sourceFiles*|*packages*) pass "27a-choke a real project loads in the throwaway resident" ;;
    *) fail "27a-choke load_project failed: $(printf '%s' "$LP" | head -c 200)" ;;
esac

# pre-advice seeding: a prose lesson the refactor's pre-advice can reach
call experience '{"kind":"record","type":"lesson",
  "summary":"renaming a method that a test references by its string name breaks the test silently",
  "operation":"end-to-end-test"}' >/dev/null

REN="$(call rename_symbol '{"symbol":"com.example.Clean#greet","newName":"salute"}')"
UNDO_ID="$(printf '%s' "$REN" | grep -oE '"undoChangeId":"[^"]+"' | head -1 | cut -d'"' -f4)"
if [ -n "$UNDO_ID" ]; then
    pass "27a-choke the rename ran and returned its undo handle"
else
    fail "27a-choke rename_symbol gave no undoChangeId: $(printf '%s' "$REN" | head -c 200)"
fi
UN="$(call refactoring "{\"action\":\"undo\",\"undoChangeId\":\"$UNDO_ID\"}")"
case "$UN" in
    *'"success":true'*) : ;;
    *) fail "27a-choke the undo itself failed: $(printf '%s' "$UN" | head -c 160)" ;;
esac
# the JDT model re-serves the restored member a moment after the undo — settle
for _ in $(seq 1 30); do
    call analyze '{"kind":"type","typeName":"com.example.Clean"}' | grep -q '"greet"' && break
    sleep 1
done

AN="$(call analyze '{"kind":"type","typeName":"com.example.Clean"}')"
case "$AN" in
    *'⚠ PRECEDENT'*) pass "27a-choke the advisory tier WARNS on the reverted target (uncharged — the call ran)" ;;
    *) fail "27a-choke no precedent warning surfaced after the revert" ;;
esac
# arm the charge on the EXACT (tool, target) pair the repeat will use — the
# ledger is exact-match by design (a warning about the class does not tax the
# member); a read on the member surfaces the warning for that member.
call find_references '{"kind":"references","symbol":"com.example.Clean#greet"}' >/dev/null

R2="$(call rename_symbol '{"symbol":"com.example.Clean#greet","newName":"salute2"}')"
case "$R2" in
    *precedentOverride*) pass "27a-choke the identity tier CHARGES an unjustified repeat (and names the payment)" ;;
    *'"success":true'*) fail "27a-choke the repeat ran uncharged — the justification-cost is words only" ;;
    *) fail "27a-choke unexpected charge response: $(printf '%s' "$R2" | head -c 200)" ;;
esac

R3="$(call rename_symbol '{"symbol":"com.example.Clean#greet","newName":"salute2",
  "precedentOverride":"the earlier undo was an experiment; this rename is intended"}')"
case "$R3" in
    *filesModified*|*'"success":true'*) pass "27a-choke a written justification PAYS the cost — the call proceeds" ;;
    *) fail "27a-choke the paid call did not proceed: $(printf '%s' "$R3" | head -c 200)" ;;
esac
call compile_workspace '{}' >/dev/null    # the gate call that classifies the outcome

# the pre-advice surface consults on the PLAN flow (its wired home)
call refactoring "{\"action\":\"plan\",\"kind\":\"compose_method\",
  \"filePath\":\"$WS/proj/src/main/java/com/example/Clean.java\",
  \"sections\":[{\"startLine\":18,\"startColumn\":8,\"endLine\":18,\"endColumn\":40,\"methodName\":\"partOne\"},
                {\"startLine\":22,\"startColumn\":8,\"endLine\":22,\"endColumn\":25,\"methodName\":\"partTwo\"}]}" >/dev/null

SQ="$(call experience '{"kind":"stats"}')"
case "$SQ" in
    *choke*) pass "27a-choke the choke surfaces feed the quality counters" ;;
    *) fail "27a-choke no choke counter moved through the whole cycle" ;;
esac
case "$SQ" in
    *pre_advice*) pass "27a-choke the pre-advice surface was consulted (counter present)" ;;
    *) fail "27a-choke the pre-advice surface never consulted" ;;
esac
case "$SQ" in
    *'outcome_after":{"'*) pass "27a-choke the outcome-after counter FILLS — the cycle closes" ;;
    *) fail "27a-choke the warning cycle never closed (outcome_after empty)" ;;
esac

stop_resident

# ============================ lifecycle 3 ====================================
# Embedder DISABLED: the degrade contract — the store still answers by WORDS
# (D9), and the write-side gates hold without any model.
start_resident -Djawata.embed.disabled=true

H3="$(call health_check '{}')"
case "$H3" in
    *'"available":false'*|*'"available": false'*)
        pass "27a-deg the resident is honestly degraded (embedder off)" ;;
    *) fail "27a-deg the disable switch did not take" ;;
esac

W="$(call experience '{"kind":"recall",
  "symptom":"why did my cone-six glaze crawl on the bisque",
  "format":"text"}')"
case "$W" in
    *crawl*|*cone-six*|*bisque*) pass "27a-D9 with the embedder OFF the store answers a prose question by WORDS" ;;
    *) fail "27a-D9 KEYWORD-ONLY DEGRADE CANNOT ANSWER PROSE (v3.4.1 shape)" ;;
esac

A3="$(call experience '{"kind":"record","type":"lesson",
  "summary":"another lesson about ordering notes",
  "symptoms":["--enable-preview"]}')"
case "$A3" in
    *REPHRASE*) pass "27a-D10d the admission gate holds with no embedder" ;;
    *) fail "27a-D10d the admission gate needs the embedder (it must not)" ;;
esac

stop_resident

# --- the fixture is PRISTINE ------------------------------------------------
FIXTURE_SHA_AFTER="$(sha256sum "$FIXTURE" | cut -d' ' -f1)"
if [ "$FIXTURE_SHA_BEFORE" = "$FIXTURE_SHA_AFTER" ]; then
    pass "27a-fixture the committed fixture is byte-identical after the run"
else
    fail "27a-fixture THE RUN MUTATED THE COMMITTED FIXTURE"
fi

echo "end-to-end test: $PASSED passed, $FAILED failed"
[ "$FAILED" -eq 0 ] || exit 1
