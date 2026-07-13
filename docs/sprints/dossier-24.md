# Sprint 24 — evidence dossier

> Companion to the signed spec
> (`jawata-enterprise/docs/sprints/jawata-mcp/sprint-24-dynamic-analysis.md`)
> and the GATE-2 plan. Every number expected-vs-actual. Three phases, one
> release each (v2.11.x / v2.12.x / v2.13.x), each with a dogfood-in-anger
> round.

## C0 — Baselines + spec/process sync (2026-07-13)

### Baselines (on the released v2.10.0, HEAD = a7ec390)

| Probe | Expected | Actual |
|---|---|---|
| Suite (sharded) | 1230/1230, 0 skipped | **1230/1230, 0 skipped, wall 164 s** ✓ |
| toolCount (live resident) | 43 | 43 ✓ |
| Resident version | 2.10.0 | 2.10.0 ✓ |
| Self-workspace sources | 500+ | 500 ✓ |
| Self compile_workspace | 0 / 0 | 0 errors / 0 warnings ✓ |

### Spec + process sync (the two GATE-2 decisions)

- SPEC: R1 amended (three phased releases supersede "fallback, not a
  pre-split") + both GATE-2 decisions added to Recorded decisions —
  committed to jawata-enterprise.
- PROCESS: the dogfood-in-anger + re-release rule folded into the /sprint
  skill's execution discipline (`~/.claude/skills/sprint/SKILL.md`) — the
  plan auditor now checks such a stage exists for every release a plan
  contains.

## C1 — D1: name addressing everywhere (2026-07-13)

### The recorded entry-point audit (the F1 gate)

Every registered tool/kind whose target is a Java element was classified before
any wiring — the full table lives in the plan file. Summary: **14 IN**
(whole-named-symbol targets), **8 EXCLUDED** (range/expression/statement
targets — a range has no name), **1 already symbol-based**
(`apply_null_annotations`), the rest not symbol-targeted. The wiring list and
FqnEverywhereTest enumerate against THAT table, never an ad-hoc list.

### What shipped

- **`FqnTarget`** (shared helper): resolves `symbol=`/`typeName=` into the
  element's name-range position and writes filePath/line/column into the
  arguments, so each tool's PROVEN position path runs unchanged. Idempotent;
  an explicit position always wins; a binary element gets an honest refusal
  (no source position exists) rather than an invented one.
- Wired into: `get_call_hierarchy(outgoing)`; `analyze` kinds
  method/control_flow/data_flow/change_impact/symbol (deliberately NOT
  kind=type — it answers for binaries, which have no source position); and
  every whole-symbol refactoring via `AbstractApplyingRefactoringTool` plus
  the front doors — rename_symbol, change_method_signature, inline(method),
  move(class), move_in_hierarchy, move_method, encapsulate_field,
  extract(interface/superclass), generate(all kinds), and the
  type/method-targeted refactor_to_pattern kinds.
- **Schemas relaxed with the wiring**: filePath/line/column dropped from
  `required` on the newly name-addressable tools (a strict client would
  otherwise reject a name-only call) — either form is now valid, validated at
  run time.

### Verification (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| FqnEverywhereTest | one probe per audited-in tool, by name alone | **10/10** ✓ |
| Touched-tool regressions | green | **130/130** across 11 filters ✓ |
| Pattern-family regressions | green | **20/20** across 8 filters ✓ |
| Full suite (sharded) | 1240/1240 | **1240/1240** after one contention-flake rerun (FeatureEnvyDetectorTest: empty search result under 4-shard CPU load; 3/3 green focused — the known Sprint-23 flake class) ✓ |

### SURPRISE — a pre-existing product bug, found by the new battery

`analyze(kind=type)` **crashed with an INTERNAL_ERROR on every BINARY type** —
`java.util.ArrayList`, any dependency class — in the RELEASED v2.10.0
(confirmed against the untouched live resident, so it predates this sprint).
`IType.getCompilationUnit()` is null for a class file, and
`createMethodInfo`/`createFieldInfo` called `getLineNumber(cu, …)` unguarded →
NPE on `cu.getSource()`. The type-info and diagnostics paths guarded it; the
member paths did not.

This matters precisely for the memory-first thesis: a library type is exactly
what an agent addresses **by name**, and asking about it returned a crash.
Fixed by omitting the line for members with no compilation unit — a binary has
members and a hierarchy, just no source lines. Now `analyze(kind=type,
typeName=java.util.ArrayList)` answers with its members. Recorded in the
experience store (b6b42d6c).

## C2 — D2: resolve-or-relocate (2026-07-13)

### What shipped

**`ResolveOrRelocate`** (shared): a name that no longer resolves is answered
with the indexed correction in the SAME response — error code
**`SYMBOL_RELOCATED`**, message naming the new location, hint telling the agent
what to remember. Two shapes: a MOVED/renamed type (search the simple name,
carry the `#member` suffix only when the member really exists on the new type),
and a RENAMED member (the type is still there — name its real members, closest
by name first). A name with nothing similar in the index gets an honest
`SYMBOL_NOT_FOUND` — "it is gone, not moved" — never a dressed-up guess.

**The correction is never acted on.** The call still fails. Silently
retargeting a refactoring at a symbol the caller did not name would be far worse
than a failed call; the agent re-issues once with the corrected name — one hop,
and its memory is now right. That is precisely the human loop (look → miss →
find → re-memorize), compressed into one call.

Wired into all six name-resolution miss-sites: `FqnTarget` (so every tool D1
made name-addressable inherits it) plus the five pre-existing `symbol=`
consumers — find_references, find_implementations, find_method_references,
find_field_writes, get_call_hierarchy(incoming).

### Verification (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| ResolveOrRelocateTest | moved + renamed + genuinely-absent | **4/4** ✓ |
| Moved class | old FQN → new location, flagged | `com.example.Calculator` → `SYMBOL_RELOCATED` naming `com.example.math.Calculator`; the corrected name then resolves ✓ |
| Renamed member | old name → the type's real members | `#multiply` → offers `#times` ✓ |
| Genuinely absent | honest not-found | `SYMBOL_NOT_FOUND` + "gone, not moved" ✓ |
| Touched-tool regressions | green | **71/71** across 9 filters ✓ |

### Note (test-honesty finding)

`relocate()` reads the JDT INDEX, which a tool call refreshes on dispatch. A
direct call to the helper immediately after a move (bypassing any tool) sees a
stale index and finds nothing. In production the helper only ever runs INSIDE a
tool call, so this is a test artifact — the battery now makes the tool call
first, exactly as a caller would, rather than asserting against a stale index.

## C3 — D3 key-teaching + D4 landmarks — STREAM 1 COMPLETE (2026-07-13)

### What shipped

**D3 — a search teaches its own address.** An EXACT-name hit (no wildcards, a
row whose name IS the query) returns steering naming the direct address:
`symbol="com.example.Calculator"` — plus WHY the name is the key (it survives
file moves; a position does not). A wildcard sweep teaches nothing: there is no
single address to teach. Rides the existing steering-precedence contract
(tool-set steering wins; `applySteering` only fills when empty).

**D4 — a session starts oriented.** `inspect(kind=landmarks)`: the workspace's
own types ranked by how much of the code leans on each (incoming references from
the JDT index), cached per workspace load. The head start a human has from
having worked in the codebase.

### Verification (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| KeyTeachingAndLandmarksTest | teach on exact / silent on wildcard; landmarks ranked | **5/5** ✓ |
| Touched-tool regressions | green | 35/35 (SearchSymbols 15, Inspect 11, LibSource 3, FieldsProjection 6) ✓ |
| **Full suite (sharded)** | 1249, 0 failed | **1249/1249, 0 failed — first run, 155 s** ✓ |

Suite grew 1230 → 1249: +10 (D1) +4 (D2) +5 (D3/D4).

### SURPRISE — my own D4 draft had a flaw the battery caught

The first landmarks ranking offered `com.example.ShotgunTarget` — a SECONDARY
type (declared beside a file's primary type). `cu.getTypes()` returns those, but
their FQN does NOT resolve through `findType` — so the top landmark could not be
addressed by the very name it advertised. **A landmark you cannot address by
name is worse than none: it teaches a name that fails.** Every candidate is now
filtered through `findType` before ranking, and the test asserts the invariant
for EVERY landmark, not just the top one — the D4 → D1 loop (orient, then go
straight there) only closes if every offered name works. Recorded as a durable
rule (experience store aebb1dad): any feature that hands an agent a name to
reuse must verify that name resolves through the same path the agent will use.

## C4 — RELEASE v2.11.0 (2026-07-13) — awaiting the word

### Release gates (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| Suite, sharded (clean 2.11.0 build) | 1249/1249 | **1249/1249, 0 skipped, 160 s** ✓ |
| Suite, serial (same build) | same totals | **1249/1249, 0 skipped** ✓ |
| Clean-clone build (from f5cd144) | exit 0, complete dist | **exit 0**, jawata.jar + testrunner-2.11.0.jar ✓ |
| Version sweep | no 2.10.0 left | 9 poms + 5 Bundle-Version manifests bumped; sweep clean ✓ |
| toolCount | 43 unchanged (stream 1 adds no top-level tool) | 43 (landmarks is an `inspect` kind) ✓ |

Release notes: `docs/release-notes/v2.11.0.md`. Commits: 48ea12e (C0) ·
f88a2e9 (C1/D1) · b70bad6 (C2/D2) · 112b0cf (C3/D3+D4) · f5cd144 (release bump).

**RELEASED 2026-07-13** on the word: pushed + tagged v2.11.0 → CI green (244
test classes / **1249 tests / 0 skipped**, verified from the downloaded run
artifact, not just the step status) → GitHub Release published with 5 platform
assets.

⏸ Next: fleet flip (Harald) → the release-day battery (live stream-1 probes on
the resident) → Stage 5's dogfood-in-anger.

### Security note (found while releasing, unrelated to the code)

Three cold emails hit Harald's business address right after the project gained
visibility. The vector was NOT the website: **all 201 commits in the public repo
carried `harald@quantefakt.de` as author + committer**, which the GitHub API
serves unauthenticated for every commit — one of the most industrially scraped
sources there is. GitHub's "keep my email private" profile setting does not
touch commit metadata. Fixed forward: the git identity is now the
`…@users.noreply.github.com` alias (all three repos inherit it; no local
overrides). History is deliberately NOT rewritten — it would break every SHA,
tag and release for an address already harvested.

## C5 — Dogfood-in-anger → v2.11.1 (2026-07-13)

Ten minutes of real use on jawata's own 506-source workspace, through the live
resident on 2.11.0. **Two real findings that 1249 green tests could not see** —
both because the fixtures are too small to expose them. This is the case for the
dogfood-after-every-release rule (Harald, GATE 2).

### What the live probes showed

| Probe | Result |
|---|---|
| D3 teach line (`search_symbols FqnTarget`) | fired live: `Address this directly next time: symbol="org.jawata.mcp.tools.shared.FqnTarget"` ✓ |
| D2 relocate (a GENUINE mistake: I guessed `tools.FieldsProjection`) | corrected to `tools.shared.FieldsProjection` in one call ✓ |
| D2 relocate on a typo (`IJdtService#getLineNumberr`) | led with `getLineNumber` ✓ (but see F2) |
| D1 outgoing-by-name (new in 2.11.0) | 13 callees for `FqnTarget#materializePosition` ✓ |
| D1 `analyze(kind=method, symbol=…)` | resolved + 6 callers ✓ |
| D1 `rename_symbol(symbol=…)` | worked **through a stale-schema client, no restart** ✓ |
| D4 landmarks | ranked — **but see F1** |

### F1 (real) — landmarks: a ranking that could not rank

The top SIX types all reported exactly `200` — the reference cap. The ordering
among precisely the types the feature exists to rank was arbitrary, and the
number read as a count when it was a floor. Fixed: bound raised to 2000 (enough
to discriminate) and a saturated count is now flagged `"atLeast": true` — a
floor is never presented as a count.

### F2 (real) — relocate: three truths were being told as one

- **Typo** → confident correction, but noise rode along (`getPathUtils`,
  `getProjectRoot` share only a `get` prefix with `getLineNumberr`). Now filtered
  by name affinity: a wrong suggestion is worse than one fewer suggestion.
- **Rename to an unrelated word** (`multiply` → `times`) → nothing in the index
  links old to new, so "Found: times" would be a guess in a fact's clothes. Now:
  the member is not there, and here are the ones that are — a directory, not a
  claim. *(A naive affinity floor initially BROKE this case, which is what
  surfaced the deeper design error: typo-correction and rename-tracking are
  different problems and must not share one answer shape.)*
- **Member missing from a type that still exists** → used to answer "gone, not
  moved" while the type sat right there. Now says what is true.

### F3 (positive, no action)

The new name form works through EXISTING client sessions without a restart —
better than the v2.11.0 schema-cache caveat predicted.

### Verification (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| ResolveOrRelocateTest | + 2 dogfood cases | **6/6** ✓ |
| KeyTeaching/Landmarks | + saturation honesty | **5/5** ✓ |
| Touched-tool regressions | green | 56/56 ✓ |
| Full suite | 1251 | **1251/1251** after one contention-flake rerun (GetProjectStructureToolTest; 3/3 focused — the known class) ✓ |

Lesson recorded (experience store dbc242d9): a bounded count used for RANKING
must not saturate; and never dress a guess as a fact.

## C5b — Second dogfood round, on v2.11.1 itself (2026-07-13)

The patch was itself dogfooded on the live 2.11.1 resident. Both v2.11.1 fixes
confirmed in anger, and one further finding.

### The v2.11.1 fixes, live

**Landmarks now actually ranks** — and the fix revealed what the cap had hidden:

| Before (cap 200) | After |
|---|---|
| six types tied at exactly `200`, order arbitrary | `ToolResponse` **2000+** (`atLeast: true`), `IJdtService` 681, `TestProjectHelper` 550, `JdtServiceImpl` 458, `RefactoringChangeCache` 286, `ResponseMeta` 249, … |

The true #1 is `ToolResponse` — every tool in the system returns one — and the
old ranking did not even list it first. A saturated value is now honestly
flagged as a floor.

**Relocate**: a typo (`ToolResponse#applySteerin`) gets a confident correction to
`applySteering` with NO noise candidates (the affinity filter working); a member
that never existed on a real type gets the honest "the type did not move; that
member is not on it".

### F4 (real, minor) — the "It has:" list was noise

Live on `ToolResponse`, the member list read:
`ToolResponse, isSuccess, getData, getError, getMeta, applySteering, success,
success, error, error, … internalError, internalError, success, data, error,
meta` — the constructor, every overload twice, and then the method names AGAIN
as fields. Twenty-five entries, half of them noise, in the one message whose job
is a CLEAN, actionable correction.

Fixed: an addressable name is said exactly once (`Type#name` addresses every
overload at once), the constructor is dropped (it is not addressable as
`Type#member`), and the list is bounded at 30 — with a trailing `…` when it
truncates, because a bounded list presented as a complete one is the same lie as
a capped count presented as an exact one.

### F4 disposition (Harald, 2026-07-13)

Cosmetic — a noisy correction message, not a wrong one. **Folded into v2.12.0**
rather than cutting a release for a list dedupe. The fix is on main (e70d2a3),
green, and ships at the end of Phase 2. Carried forward so it cannot be lost:
**v2.12.0's release notes must mention it.**

### C5 exit — PHASE 1 COMPLETE

| Gate | Actual |
|---|---|
| v2.11.0 released | ✓ CI green, 5 assets, suite artifact-verified 1249/1249 |
| Dogfood-in-anger → findings fixed | ✓ 3 findings → **v2.11.1 released** (CI green, 1251/1251) |
| Second dogfood round on the patch | ✓ both fixes hold; 1 cosmetic finding (F4) → deferred to v2.12.0 by decision |
| Spec D1–D4 | ✓ as-built |

# PHASE 2 — Dev/sim debugging → v2.12.x

## C6 — The JDI-layer decision spike (2026-07-13)

### The decision: the JDK's own JDI (`com.sun.jdi`), Eclipse's eval engine in reserve

**Microsoft `java-debug`: DISQUALIFIED on structure.** It is a DAP adapter
layered ON TOP of `org.eclipse.jdt.debug` — the same JDI stack underneath.
Adopting it means adding jars to obtain a protocol translation we would
immediately discard, because we speak MCP, not DAP. It buys nothing we do not
already have, and puts a third-party layer between us and the JDI we want.

**The Eclipse stack costs zero anyway.** `org.eclipse.jdt.launching` is ALREADY
in `org.jawata.mcp`'s Require-Bundle, and `org.eclipse.jdt.debug` (964 KB) +
`org.eclipse.debug.core` ride in as its dependencies — all three already sit in
`dist/bundles/`. `jdt.debug` needs no UI bundle (core.resources, debug.core,
jdt.core, core.runtime, core.expressions) and exports
`org.eclipse.jdt.debug.eval` — the expression-evaluation engine D6 needs. So the
eval engine is available for free when Stage 8 wants it.

**Until then, raw JDI is simpler and free.** `jdk.jdi@21.0.10` is in the runtime
already.

### The empirical proof (`DebugSpikeTest`, green FIRST run)

Launched a JVM under `-agentlib:jdwp` -> **attached** over `dt_socket` -> set a
**line breakpoint** -> **hit** it (line 12, method `tick`) -> **read the frame**,
pulling a live argument value out of a running program -> reaped the process
tree. All inside our OSGi runtime, with **no new dependency and no boot-config
change**.

That is the question a manifest cannot answer, and it is now answered before a
line of debugger code exists — which is exactly why the plan put this stage
first.

### The fixtures

- **`debug-target`** — runs until killed. One of each thing an interactive
  debugger must handle: a named seam (`computeSignal`), a hot loop (`spin` — a
  non-suspending probe must watch it while it demonstrably keeps RUNNING), a
  changing field (`lastSignal`, for watchpoints), an exception site
  (`riskyStep`), and a flag the debugger flips in the D7 hypothesis proofs.
- **`replay-app`** — deterministic by design, because "capture at the FIRST
  violation" is only testable against a replay that violates in exactly one
  place. Balance walks down to exactly 0, then **event 7** takes it to -40 (the
  first violation); events 8 and 9 violate it again, so "the first one" is a
  real assertion, not a tautology. Arithmetic independently verified.

Both compile with `-g` (locals must be visible or the frame reads nothing).

### C6 exit

| Gate | Expected | Actual |
|---|---|---|
| Layer chosen on breakpoint evidence | proof on both candidates, or a documented disqualification | JDK JDI proven end-to-end; java-debug disqualified on structure ✓ |
| Fixtures compile | in-suite | both compile; replay determinism verified by running it ✓ |

## C7 — D5: the runtime session spine (2026-07-13)

### What shipped

`org.jawata.mcp.runtime`: **DevSimPreset** (the one host-controlled switch —
loopback JDWP, bounded continuous JFR, local JMX, NMT summary, profiler
readiness, quiet console), **JvmTargets** (discover / launch / attach /
capability read), **RuntimeSession** (knows how it began, because teardown
differs), **RuntimeSessionRegistry** (bounded, with a shutdown hook — a launched
JVM with nobody left to reap it is an orphan). Front door #1: **`debug`**
(discover / launch / attach / status / detach / cancel). **toolCount 43 → 44.**

### THE FINDING — debuggability cannot be retrofitted

`loadAgentLibrary("jdwp", …)` fails: *"Agent_OnAttach is not available in jdwp"*.
OpenJDK's debug agent has **no attach entry point**, so a JVM that started
without `-agentlib:jdwp` can NEVER be debugged, for as long as it lives. (Harald,
on being told: *"jvm starts with debug harness. This I could have told you
upfront."* — noted; ask him about JVM/runtime realities before deriving them.)

This does not weaken the design; it **completes** it:

- The preset is not a convenience — it is the ONLY way a JVM becomes debuggable.
- **The safety model becomes structural, not policy.** The spec claims the
  dangerous action is "unrepresentable, not refused". That was an architectural
  aspiration; it is now literally true at the JVM level — a production JVM,
  started without the preset, cannot be debugged by anyone, with any tool.
- The API got better: `discover` flags every JVM `debuggable: true|false` **with
  the reason**, so nobody learns this by failing; `attach` refuses an unprepared
  target with `JVM_NOT_DEBUGGABLE` and says what to do instead, rather than
  returning an internal error.

Recorded in the experience store (dbb91ef3), incl. the contrast that JMX
*can* be loaded dynamically — so phase 3's live-state reads do not share this
restriction.

### Verification (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| Preset capabilities discovered | ALL SIX | all six, read FROM the JVM, never assumed ✓ |
| Launched JVM: detach | terminated, no orphan | pid gone, session forgotten ✓ |
| Foreign PREPARED JVM: detach | released, keeps running | still alive after detach ✓ |
| Unprepared JVM | honest refusal + reason | `JVM_NOT_DEBUGGABLE` + how to fix ✓ |
| discover | never offers the debugger itself | self excluded ✓ |
| DebugSessionSpineTest | green | **6/6** ✓ |
| Full suite | 1258 | **1258/1258** after one contention-flake rerun (RenameSymbolToolTest; 10/10 ×3 focused) ✓ |

## C8 — D6: the interactive debugger (2026-07-13)

### What shipped

`org.jawata.mcp.runtime.debug`: **DebugController** (the JDI event pump — one
thread owns the event queue for the session's life; seven breakpoint kinds;
stepping; snapshots; the bounded instances query), **JdiEvaluator** (JDT parses the
expression, JDI executes it against the live frame — including real method
invocation in the target), **JdiValues** (bounded expansion that reports every
bound that bites), **HeapHistogram** (the exact live count, when the enumeration
cap bites). `debug` gains: breakpoint_set/clear/list · wait · threads · snapshot ·
evaluate · step · resume · instances · artifacts · artifact_delete. toolCount
stays **44** (all behind the one front door).

### THE C7 GAP — disclosed, not absorbed

C7's exit criteria included the artifact store (store/list/delete) and the async
shape. **Neither was built, and the C7 summary did not say so.** Closed here:
`RuntimeArtifactStore` (provenance manifest · expiry · explicit delete · an
unmanifested directory is not an artifact) + 3 tests, and the `artifacts` /
`artifact_delete` actions. Recorded because a checkpoint that quietly passes on an
unmet criterion is the same failure this sprint's product exists to prevent.

### THE FINDINGS — three, all caught by tests, all real

**1. A launched target must start SUSPENDED.** With the host preset's `suspend=n`,
the program is already running before a single breakpoint can be armed: a
breakpoint on the first line of `main` can never hit, and "the third iteration"
means "the third one we happened to catch". `DevSimPreset.jvmArgsForLaunch()` now
uses `suspend=y`; the caller arms, then `debug(action=resume)` starts the program.
The HOST preset keeps `suspend=n` — attaching to a running sim must not change
whether it runs. (The event pump must also NOT resume the `VMStartEvent` set while
the target is held: its policy is SUSPEND_ALL, so resuming it starts the program
behind the caller's back.)

**2. A held JVM cannot answer, so its capabilities are UNKNOWN — never false.**
The attach channel is serviced by the target itself, and a target that has not run
services nothing. The report came back `flightRecording: false, jmx: false,
presetPrepared: false` — about a JVM launched WITH all of them. Reporting `false`
for unknown is the same class of lie as reporting a cap as a count. Now: `null` +
`capabilitiesUnread: true` + the reason, re-read the moment the program runs.

**3. A "resume until suspendCount()==0" loop is a race that eats breakpoints.**
`suspendCount()` is a VM round-trip, so the loop can observe a NEW suspension — the
breakpoint that fired microseconds after the first resume — and resume it away.
Symptom: `wait` returns a hit whose thread is already running again
(`IncompatibleThreadStateException`, `state=SLEEPING, suspendCount=0`); steps land
far past their target. An event set with an event-thread policy suspends once, so
we release exactly once. The drain-to-zero loop is correct ONLY in teardown, and
only after every request is deleted. Found because the error message was made to
report the VM's own state — "it threw" is not a diagnosis.

### Verification (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| 6 breakpoint kinds, field ACCESS and WRITE separate | each set→hit→inspect→step→resume | 7 kinds proven (line · method · conditional · hit_count · exception · field_access · field_write) ✓ |
| conditional | passes over non-matching iterations | stops at `iteration == 4`, verified by evaluating it ✓ |
| hit_count | the Nth, not the first | `hitCount=3` → `iteration == 2` exactly (deterministic: not one iteration ran before arming) ✓ |
| exception | catches a SWALLOWED throw | caught at the throw site in `riskyStep`, with its message ✓ |
| evaluate | incl. a method-invoking expression | `offset()` → 7, `multiply(6,7)` → 42, `iteration*2 + offset()` composed ✓ |
| evaluate: honest refusal | unsupported form refused BY NAME | `iteration = 99` → `EVALUATION_UNSUPPORTED` ✓ |
| paged object expansion | deep graph cut at the bound, and says so | 12-deep graph, depth=2 → `truncated` + the reason; depth=5 yields more ✓ |
| instances-of-type | exact below cap, dump-backed above | Widget → 25 exact (live); String → exact count from the heap histogram, `pagingLimited` ✓ |
| snapshot thread-state honesty | a RUNNING thread yields no stack | `stack: null` + `stackUnavailable`; asking anyway → `THREAD_NOT_SUSPENDED` ✓ |
| deferred breakpoint | not-loaded ≠ never-binds | `bound: false` + `pending` + reason; binds on class load and hits ✓ |
| DebugInteractiveTest | green | **13/13**, soaked **3×13/13** ✓ |
| RuntimeArtifactStoreTest | green | **3/3** ✓ |

## C9 — D7: hypothesis testing (2026-07-13)

### What shipped

`debug` gains **set_value** (overwrite a local or a field), **force_return**
(abandon a method and return a value to its caller now), **pop_frame** (put the
thread back at the call site), **redefine** (replace a class's bytecode), and
**mutations** (everything this session changed, in order). toolCount stays 44.

Every mutation is proven by its EFFECT on the live program, never by the tool's own
report of success: set_value → the program computes from the value WE put there
(`iteration*2 + offset()` = 2007); force_return → main's local holds 999, a number
`computeSignal` never produced; pop_frame → the thread really calls the method
again; redefine → `offset()` returns 42 where it returned 7.

### THE MUTATION LOG — because it is not the same program any more

A debugged program is no longer the program you started, and a finding drawn after a
mutation is a finding about a program WE edited. So the session records every change
and `status` always discloses it (`mutationCount`, `programIsUnmodified`). A session
that cannot say what it changed cannot be trusted to report what it found.

### THE HONEST REFUSAL — a real one, not a synthetic one

HotSpot hot-swaps method BODIES and nothing else. The test compiles a fixture with
an ADDED METHOD and attempts to redefine: refused as
`REDEFINE_SCHEMA_CHANGE_UNSUPPORTED`, saying what IS allowed and what to do instead
(restart the target) — not a raw `UnsupportedOperationException` for the caller to
decode.

### THE FINDING — a JDI quirk that made the debugger lie

**`forceEarlyReturn` pops the frame in the VM but does NOT reset JDI's cached
stack** — `frames()` keeps handing back the method you just abandoned, complete with
its old locals. (`popFrames` DOES reset the cache, so the two mutations behave
differently and it is easy to assume both are fine.) The cache is rebuilt only when
the thread next MOVES. A snapshot immediately after a forced return therefore showed
a frame that no longer exists: a debugger telling a confident lie about where the
program is.

Fix: the thread's stack is marked stale after a forced return, and reading it
(snapshot/evaluate) is REFUSED — `STACK_STALE_AFTER_FORCE_RETURN` — until the thread
has stepped or resumed. We would rather refuse than serve a frame that is not there.
Recorded in the experience store (5ee5b1ec).

### ALSO — a genuinely flaky test, fixed rather than excused

`ResolveOrRelocateTest` failed ~1 run in 6 even FOCUSED (not CPU contention). Its
comment claimed to "let the Java model settle" after a rename, but it asserted
immediately and raced JDT's asynchronous index rebuild. A test that conflates "the
name is absent" with "the lookup could not complete yet" is flaky by construction —
the same distinction the PRODUCT already gets right. Now waits, bounded, for the
settled state it means to assert: 6/6 clean over six consecutive focused runs.

### Verification (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| set_value (local) | the program computes from our value | `iteration`=1000 → `iteration*2+offset()`=2007 ✓ |
| set_value (field) | a dead branch becomes live | `tripped` false → true ✓ |
| force_return | caller receives OUR value | main's `signal` = 999 (would have been 7) ✓ |
| pop_frame | the call happens again | `offset` re-entered from the same call site ✓ |
| redefine | new body takes effect | `offset()` 7 → 42; breakpoints re-armed against the new class ✓ |
| honest refusal | schema change refused by name | `REDEFINE_SCHEMA_CHANGE_UNSUPPORTED` + what to do ✓ |
| mutation log | every change, in order, disclosed on status | 2 mutations, ordered; `programIsUnmodified: false` ✓ |
| DebugMutateTest | green | **7/7** (×3) ✓ |

## C10 — D8: dev probes (2026-07-13)

### What shipped

`debug` gains **probe_set / probe_read / probe_list / probe_clear** — watchers that
read a running program **without stopping it**. Kinds: `field_watch` (every read and
write), `method_trace` (entry and exit, with the RETURN VALUE), `logpoint` (a line,
optionally capturing expressions). Probe events stream to the same `hitStream`
journal, so a file monitor is notified as they happen. toolCount stays 44.

This is the capability that matters on a live simulation, where suspending the world
is exactly what you cannot do. A breakpoint answers "what is the state HERE?" by
stopping everything, which changes the timing of all that follows. A probe answers
"what values flow through here?" at full speed.

### THE HONEST CATCH — declared, not hidden

A JDI event carries only what the JVM puts in it:

- A field's value and a method's **return value ride in the event** — free, and
  SUSPEND_NONE means nothing ever stops.
- **Locals do not.** They can only be read from a stopped thread. So a logpoint with
  `capture` DOES stop the thread — for microseconds, resuming itself at once — and it
  reports **`perturbs: true`**. Calling that "non-suspending" would be a lie, and the
  worst kind: one that silently shifts the timing of the very race you are hunting.
  The steering points such a caller at `field_watch` / `method_trace`, which stop
  nothing.

### BOUNDED, AND SAYS SO

A probe on a hot path would stream until the disk fills. Each has a budget (default
1000); past it the probe disables itself and reports *"the FIRST N events, not all of
them"* — a truncated stream must never read as the whole story. `probe_read`
distinguishes `totalSeen` from `returned` for the same reason.

### Verification (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| **THE PROOF** | values stream WHILE the loop keeps running | field values stream; main checked RUNNING **10× during the live stream** (a single check could get lucky); the stream keeps growing ✓ |
| field_watch | reads AND writes, values from the event | both seen; writes carry `newValue`; `perturbs: false` ✓ |
| method_trace | entry + exit + return value, no stop | `offset()` exit reports `returned: 7` without stopping anything ✓ |
| capturing logpoint | declares that it perturbs | `perturbs: true`, `suspendsTarget: true`; captured `iteration*2 + offset()` evaluated in the live frame; program NOT left suspended ✓ |
| budget | stops itself, disowns the partial stream | stopped at 6; message says "not all of them"; program unaffected ✓ |
| DevProbeTest | green | **4/4** ✓ |

## C11 — D9: replay + invariant capture (2026-07-13)

### What shipped

`debug(action=replay)` — a generic descriptor: launch a program, declare what must
ALWAYS be true, and stop at the FIRST moment it is not. The invariant is armed as its
own **negation** on a conditional breakpoint, so the program runs at speed and stops
only where it is actually broken. What comes back is the first violation — stack,
locals, captured expressions — stored as an artifact with provenance. toolCount stays
44.

The thread is left SUSPENDED in the violation, so you investigate **from the moment it
broke**, not backwards from the wreckage.

### THE FIRST ONE, AND ONLY THE FIRST

The fixture breaks `balance >= 0` at event 7 — and again at 8, and at 9. That is
deliberate: it makes "the FIRST violation" a real assertion rather than a tautology.
A capture at event 8 would be a capture of a program that was ALREADY wrong, which
tells you little about why. The breakpoint is disarmed the instant it fires, so no
later violation can be mistaken for the first: proven by `hitNumber == 1` and by the
captured state (`event 7, amount 40, balance -40`).

### THREE OUTCOMES, NEVER MERGED

- **violated** → the capture, the artifact, the suspended thread.
- **held** → the replay RAN OUT and the invariant held at every check. An answer.
- **inconclusive** → no violation yet, but the program is STILL RUNNING. This is NOT
  "the invariant held" — it is "we do not know", and it says so. (Proven against a
  never-ending program: `programEnded: false`, conclusion says STILL RUNNING.)

Plus: an invariant that cannot be EVALUATED (a name not in scope at that line) STOPS
and reports the condition error — it is never silently treated as "not violated". A
condition that quietly never matches looks exactly like a bug that never happens,
which is the most expensive wrong answer a debugger can give.

### Verification (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| exactly ONE capture at the first violation | event 7, not 8 or 9 | `event 7, amount 40, balance -40`; `hitNumber 1` ✓ |
| suspended IN the violation | investigate from there | thread suspended in `apply`, stack readable ✓ |
| artifact + provenance | what was checked, on what, when | manifest carries invariant / target / sessionId / createdMillis; `capture.json` on disk ✓ |
| invariant HOLDS | reported as an answer | `violated: false`, `programEnded: true`, "held at every check" ✓ |
| inconclusive ≠ pass | never dressed up as clean | `programEnded: false`, "STILL RUNNING … not yet" ✓ |
| unevaluatable invariant | stops, blames the condition | `conditionError` + "not because it was true" ✓ |
| ReplayInvariantTest | green | **5/5** (×2) ✓ |

## C12 — D15 closure + D16 audit + D17 disclaimer (2026-07-13)

### THE LOOP CLOSES — the sprint's central claim, tested

A breakpoint hit names the class and the method. **That IS the key the static tools
take.** The hit now carries `symbol: "com.example.debug.DebugTarget#computeSignal"`
pre-assembled, and the test hands it STRAIGHT to `get_call_hierarchy` — which resolves
it with no intermediate search and reports that `main` calls it. A fact the RUNNING
PROGRAM knew, reached into a fact the COMPILER knows, with nothing in between. The
steering says so in as many words ("do NOT search for what the running program has
just told you"), because a loop that closes only by luck does not close.

The same key works on the **knowledge store**: a lesson recorded against the symbol a
breakpoint produced is recalled by that same symbol. What we learn at runtime is
findable by the name the runtime used.

### D16 — the audit battery

- **Every event carries `sessionId` + `projectKey`** — in the response AND in the
  streamed journal line. Without the project key the symbol a hit hands you is
  ambiguous the moment a workspace has two projects.
- **The subagent hand-off and the wait contract are documented in the front door**
  (`SUBAGENT`, `NOTHING IS EVER MISSED`, `hitStream`), so an agent reads them without
  being told.
- **Artifacts** list and delete explicitly; deleting what is not there is an honest
  miss, not a crash.

### D17 — the disclaimer, in both places, verbatim-checked

The `debug` description and the README both carry all three statements, and a test
fails if either drifts:

1. **It suspends threads and changes state** — this is intervention, not observation.
2. **It is for a development or simulation machine** — and *why that is structural*: a
   JVM can only be debugged if it was STARTED with a debug agent, so a production JVM
   is not protected by policy, it is **unreachable**.
3. **Elsewhere it is the operator's professional judgment** — a debuggable test/staging
   box is an ordinary thing for a professional to want; jawata does not second-guess
   it. But if you do not know what suspending that JVM would do, that is the answer.

### Verification (expected vs actual)

| Gate | Expected | Actual |
|---|---|---|
| debug fact → static tool | no intermediate search | hit's `symbol` → `get_call_hierarchy` resolves; `main` found as caller ✓ |
| steering | tells the agent not to search | contains the exact key + "do NOT search" ✓ |
| runtime symbol → memory | record + recall by the same key | lesson recorded at the hit's symbol, recalled by it ✓ |
| every event | sessionId + projectKey, response AND journal | both ✓ |
| front door | subagent hand-off + wait contract documented | ✓ |
| artifacts | list + explicit delete; honest miss | ✓ |
| D17 | three statements, tool AND README | verbatim-checked, both ✓ |
| DebugClosureTest | green | **7/7** ✓ |

## C13 — RELEASE v2.12.0 prepared (2026-07-13) — ⏸ AWAITING THE WORD

Version bumped 2.11.1 → 2.12.0 (poms + 3 manifests); release notes written
(`docs/release-notes/v2.12.0.md`), including the carried F4 fix (the "It has:" member
list in a stale-symbol correction — deduped, constructor-free, bounded with a visible
`…`).

### Gates

| Gate | Expected | Actual |
|---|---|---|
| Suite, sharded | green | **1301/1301**, zero flakes ✓ |
| Suite, SERIAL | green | **1301/1301** ✓ (first serial run found 2 — see below) |
| Clean-clone build | green, from the release commit | ✓ (`2.12.0`, jar built) |
| toolCount | **44 EXACT** | 44 — enumerated: 43 inline registrations + `experienceTool` (registered via a variable, which is why a naive grep says 43) ✓ |

### WHAT THE SERIAL RUN CAUGHT — and the sharded run had masked

**1. My own test over-claimed, and the product text with it.** `DevProbeTest` asserted a
capturing logpoint is never OBSERVED suspended. But such a logpoint genuinely suspends
the thread to read the frame — that is exactly what `perturbs: true` means — so the
assertion contradicted our own documentation. Worse, the docs called the cost
*"microseconds"*. **Measured:** a capture whose expression INVOKES A METHOD, on a 25 ms
loop, keeps the thread suspended so much that a 50 ms poll rarely catches it running.
That is not microseconds; it is **a different program**, and on a race or a timing bug it
would hide the bug or invent one. The docs now say so plainly, and the test asserts what
actually matters: the program keeps making progress on its own, and **removing the probe
leaves it whole**.

**2. An unexplained one-off.** `ReplaceDuplicatesToolTest` failed once in serial ("clone
group not found in: []") and did **not** reproduce — clean on a second full serial run and
2× focused. Recorded as unexplained rather than explained away. If it returns, it is real
order-dependence, not CPU contention.
