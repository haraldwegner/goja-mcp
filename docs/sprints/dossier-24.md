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
