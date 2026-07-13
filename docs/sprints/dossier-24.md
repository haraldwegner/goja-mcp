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
