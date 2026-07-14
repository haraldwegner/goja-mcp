# Sprint 24 — adversarial implementation audit (spec vs code)

> Requested by Harald 2026-07-14 ("Make a controversary audit of 24 sprint doc vs the
> implementation. Code review expected.") AFTER the sprint was closed out and v2.13.0
> shipped. Four fresh-context auditors, one per scope, each given the spec + plan +
> dossier and instructed to assume the implementation narrowed or over-claimed until
> the code proved otherwise. Author-blindness is a context property: the agent that
> wrote most of this code and ALL of the dossier/close-out claims under audit did not
> perform the audits.
>
> **RESULT: 4 scopes, 4 × REFUSE.** The close-out's own sentence — *"Deferred edges:
> none newly created this sprint — D1–D18 as scoped in the signed spec, no narrowing
> found at close-out"* — is **falsified**. This document is the correction of record.

## Verification status

The audits are themselves subject to audit. The four BLOCKER-class claims were
independently re-verified in the code by the main agent before being recorded here.
All four confirmed:

| # | Claim | Verified at | Status |
|---|---|---|---|
| 1 | The incident alarm-file JSON fallback throws NPE | `ProfileTool.java:1017` — `Map.of("symbol", null, …)`; `java.util.Map.of` rejects null values | **CONFIRMED** |
| 2 | Every `profile(threads)` thread `id` is numerically wrong | `ProfileParsers.java:20` captures `nid=(\d+)` (decimal, correct for JDK 21) but `:62` parses it `Long.parseLong(nid, 16)` (radix 16) | **CONFIRMED** — and visible in our own Stage-21 dogfood output, where `main` was reported `id=57156960` (the decimal string `3682560` misread as hex). It was looked at and not noticed. |
| 3 | `native_handoff` gdb correlation can never fire | hs_err captures `Unsafe_PutInt+0xa4` (offset included, `HsErrParser.java:51`); gdb's `function` is bare `Unsafe_PutInt`; `ProfileTool.java:1345` does an exact `Set.contains` | **CONFIRMED** — `correlatedWithHsErr` has never once been true; no test covers it; gdb is absent on this box so the live path never ran either |
| 4 | 4 audited-in tools have no name-form probe though C1 claims "10/10 ✓" | tests-bundle sweep: `inline`, `move_in_hierarchy`, `move_method`, `refactor_to_pattern` → **0** test files drive the action AND a `symbol=`/`typeName=` argument | **CONFIRMED** |

The two spec narrowings the main agent independently suspected before reading the audits
(D11 wall time, D12 domain events) were also found by the stream-3 auditor, and the spec
wording was re-read verbatim to confirm — both are in the deliverable BODY, and both
passed because the gate was taken from the narrower "Measure:" line.

## Why 1362 green tests caught none of this

The tests were written by the same agent, against the same misunderstanding, at the same
time as the code. Where a claim was wrong, the test encoding that claim was wrong in the
same direction — e.g. the D3 teach-line test (`KeyTeachingAndLandmarksTest.java:95-98`)
fabricates a row shape production never emits, so it asserts a behavior that cannot occur;
`NativeTriageTest` tests gdb's *parser* and the dossier's gate row relabels that as
"correlated-evidence path proven". Green ≠ correct. This is the sprint's own thesis
(runtime evidence over static assumption) failing on the sprint itself.

---

## TIER 1 — Wrong answers in shipped surfaces (v2.13.1 material)

These produce confidently incorrect output, hang, or leak on the deployed v2.13.0 binary.
The project's own deepest bug class ("an empty result on failure is a LIE"; "never
over-claim what you know") is represented several times over.

| # | D | File | Defect | Failure scenario |
|---|---|---|---|---|
| T1.1 | D10 | `ProfileParsers.java:62` | `nid` parsed radix-16 though captured as decimal | Every thread `id` from `profile(threads)` is wrong; an operator cross-referencing `top -H`/`kill -QUIT` lands on a nonexistent thread |
| T1.2 | D13 | `ProfileTool.java:1017` | `Map.of(…, null, …)` NPEs inside the catch block meant to tolerate a non-JSON alarm | A target that alarms with plain text (the exact case the fallback exists for) permanently wedges its incident: every poll re-throws, the bundle is never captured, and the capture window (a live process at the moment of alarm) is lost |
| T1.3 | D14 | `ProfileTool.java:1345` | gdb↔hs_err symbol match compares `Unsafe_PutInt` against `Unsafe_PutInt+0xa4` | `correlatedWithHsErr` is always false, `correlatedFrameCount` always 0 — the adapter's entire value-add over the free hs_err baseline is dead code |
| T1.4 | D10/D14 | `Jcmd.java:45-57`, `GdbAdapter.java:71-78` | `readAllBytes()`/readLine-to-EOF runs BEFORE `waitFor(timeout)` | A wedged jcmd or gdb (SIGSTOPped target, mismatched core) blocks the MCP call **forever** instead of failing at 30s as the code promises |
| T1.5 | D12 | `ProfileTool.java:86-88` + `DebugController.java:1071` | 500-slot global probe ring silently evicts; loss is detectable from the `sequence` field the code already has, but no `samplesLost` is reported; the 200k budget silently disarms the probe mid-window | Above ~1,250 calls/s — routine for the order-flow seams this deliverable names — percentiles are computed on a biased subset, and a surviving exit can LIFO-pair with an earlier call's entry, **fabricating inflated tail values**. A 30s request can silently cover 13s. |
| T1.6 | D5/D9 | `JvmTargets.java:200-215` | A launched target's stdout is read until the JDWP port line, then never drained (`redirectErrorStream(true)`, no consumer) | Any chatty real program blocks in `write()` at ~64KB. `debug(action=replay)` on a real JATS journal replay — the flagship D9 use case — stalls before reaching its violation and reports "no violation yet, inconclusive" |
| T1.7 | D6/D8 | `DebugController.java:349-369`, `:1294-1305` | Conditional/logpoint expressions are evaluated ON the event-pump thread via blocking `invokeMethod` | If the invoked code hits any armed SUSPEND_EVENT_THREAD event (a method breakpoint on the invoked method, a lock held by a suspended thread), the pump — the only queue drainer — wedges for the session's life. `wait` times out forever. |
| T1.8 | D13 | `ProfileTool.java:1050-1118` | Capture is non-atomic; only part 1 is failure-guarded; the manifest is written last | A jcmd failure at parts 2-4 leaves an **unmanifested** artifact dir — invisible to `artifacts`, undeletable by id, and never pruned by expiry. Each failed re-poll leaks another, potentially with a multi-GB `heap.hprof` inside. |
| T1.9 | D13 | `ProfileTool.java:945-975` | `capturedSummary` is a bare volatile with no lock/CAS | Two concurrent polls (exactly the main-agent + subagent pattern the tool's own description advertises) both capture: two full bundles, two heap dumps against a JVM already in distress, one artifactId orphaned |
| T1.10 | D13 | `ProfileTool.java:912-922` | `MAX_ARMED_INCIDENTS` eviction only removes an *already-captured* incident, then puts unconditionally | With 50 uncaptured watchers armed — precisely the state a bound exists for — the map grows unbounded. When eviction *does* fire it silently invalidates a captured incident's id. |
| T1.11 | D13 | `ProfileTool.java:1219-1238` | `log_level` revert is a fire-and-forget daemon thread per call | Stacked calls corrupt the baseline (INFO→FINE→FINEST reverts to FINE, losing INFO); a jawata restart silently cancels all pending reverts, leaving the target verbose forever. The description promises the bump "cannot outlive the reason you set it". |
| T1.12 | D4 | `Landmarks.java:47-75` | Static cache keyed by project *name* only, never invalidated; `invalidate()` has zero callers; the Javadoc describes a source-count key that isn't in the code | On the long-lived resident, the first call's ranking is served forever — including deleted/renamed types whose advertised names now FAIL name-addressing, the exact invariant D4 exists to protect. A different tree with the same basename gets the other workspace's landmarks. |
| T1.13 | D3 | `SearchSymbolsTool.java:220` | For method/field hits, the taught address is built from `containingType`, which holds the **simple** name | `search_symbols(query="add", kind="Method")` teaches `symbol="Calculator#add"`, which `FqnResolver` cannot resolve → a misleading `SYMBOL_RELOCATED` "it appears to have moved" on first use. Violates the project's own recorded rule (store `aebb1dad`): a name handed to an agent must resolve through the path the agent will use. |
| T1.14 | D16 | `RuntimeArtifactStore.java:192` | `pruneExpired()` has **no production caller** (test-only) while `DebugTool.java:952` steering says "expired ones are pruned" | Week-old multi-GB heap dumps sit past expiry forever; the operator, told pruning happens, never deletes. Found independently by **three** of four auditors. |
| T1.15 | D7 | `DebugController.java:935-951` | `evaluate` calls `recordMutation` nowhere, though the spec's Approach (an audit-CURED clause) requires evaluation to be recorded in the session outcome | Agent evaluates `list.clear()` in the target; `status` then reports `programIsUnmodified: true` — a confident false statement about an edited program. Found independently by **two** auditors. |
| T1.16 | D7 | `DebugController.java:1590-1597`, `:567-581` | Every class lookup is `vm.classesByName(name).get(0)` — first loader wins, silently — though the spec's Approach requires "OSGi classloader identity accounted for" | On the actual intended target (JATS **is** OSGi), `redefine` patches an arbitrary one of two same-FQN classes and `breakpoint_set` binds to an arbitrary one, both reporting success |

## TIER 2 — Spec clauses silently narrowed (Harald's decision: implement, or record an amendment)

Each of these is a deliverable-BODY clause that no recorded decision disposes of. Each
passed its checkpoint because the gate was taken from the narrower "Measure:" line.
**This is the ranking that is Harald's, not mine.**

| # | D | Spec says (verbatim) | Shipped |
|---|---|---|---|
| T2.1 | D11 | "ranked, paginated hotspots: CPU, allocation, **wall time**, locks, GC, and call counts" | `dimension` enum is `cpu \| alloc \| lock \| gc`. `dimension=wall` returns `invalidParameter "Unknown dimension"` — not even a capability-absent report |
| T2.2 | D11 | "…and **call counts**" | Top-of-stack **sample** counts, relabeled. Honestly described in the tool text, but never amended. A method called 10,000× at 1µs yields ~0 samples. The product already owns a true call counter (the D8 `method_trace` probe `latency_seam` uses) — it was not wired to `hotspots` |
| T2.3 | D12 | "plus **the target's own domain events where it emits them**" | Nothing. No domain-event handling exists anywhere; dossier C17 does not dispose of the clause |
| T2.4 | D2 | Plan Stage 2 (Harald-signed): "Wire into … find_references, get_call_hierarchy incoming, **inspect type kinds**, find_field_writes" | `inspect(kind=type_hierarchy/type_members/type_usage)` answer a stale name with a bare "Type not found" — no correction. Dossier C2 silently substitutes other tools and calls the set complete |
| T2.5 | D15 | Measure: "a diagnosis session **demonstrably recalls a previously recorded incident first**" | No product wiring at all (zero experience-store integration in `DebugTool`/`DebugController`); the one test does hit → record → recall — the **inverted** order, no seeded prior incident, no probing after a recall |
| T2.6 | D5 | Measure: "after teardown … **no recording is left behind**"; plan C7 exit: "dir sweep" | Teardown is SIGKILL-only; the preset's continuous JFR recording cleans its chunk repository only on orderly exit. No test sweeps for recordings; the C7 gate table has no recording row at all. Every launch+detach plausibly leaks JFR chunk dirs in tmp |
| T2.7 | D5 | Capability report "read FROM the JVM, never assumed" (dossier C7, test title, and `DebugTool.java:1111` steering) | 4 of 6 (`nativeMemoryTracking`, `quietConsole`, `flightRecording`, `profilerReady`) are inferred from the preset **marker property**, not discovered. Attach to a hand-prepared JVM with real NMT but no marker → reports `nativeMemoryTracking: false` while `profile(nmt)` on the same target succeeds |
| T2.8 | Approach | "Sprint 22's guard redirects an attempted print-debug source edit toward the shipped probe workflow" | The deployed guard message names rename/move/extract/etc. and the fallback valve — **no mention of probes/logpoints anywhere**. Owned by no stream, disclosed nowhere |

## TIER 3 — False claims of record (correct regardless of the above)

| # | Where | Claim | Reality |
|---|---|---|---|
| T3.1 | Spec close-out | "no narrowing found at close-out" | Falsified — see all of Tier 2 |
| T3.2 | Dossier C1 | "one probe per audited-in tool … 10/10 ✓" | 4 (arguably 5, with `extract(superclass)`) of the 12 name-form additions have **no probe anywhere** |
| T3.3 | Dossier C8 | "6 breakpoint kinds each set→hit→inspect→step→resume ✓" | The full cycle is demonstrated for `line` only; exception/field_access/field_write are **set→hit only** |
| T3.4 | Dossier C19 | "Correlated-evidence path proven" | The test proves gdb's *parser*, not correlation — which is broken (T1.3) |
| T3.5 | Dossier C12 / close-out D15 row | "recall-before-probe … proven" | See T2.5 — order inverted, nothing recalled before anything |
| T3.6 | Dossier C18 | "`incident_status` … never re-captures — **tested explicitly**" | Only the sequential half is tested; concurrent polls both capture (T1.9) |
| T3.7 | `DebugClosureTest.java:292-303` | D17 "verbatim-checked, both ✓" | The README half is inside `if (Files.isRegularFile(readme))` — it silently no-ops when cwd isn't the repo root. (The README content itself IS compliant — verified.) |
| T3.8 | Plan Stage 3 rider | "dossier records stream-1 token evidence" | No token trace exists anywhere in the dossier |

## Per-deliverable verdicts

| D | Verdict | Note |
|---|---|---|
| D1 | **NOT-MET (as measured)** | Wiring is real and complete for all 14 rows; the *proof* required by the signed gate is absent for 4–5 of them |
| D2 | MET-WITH-FINDINGS | Measure met, tests exemplary; plan-listed inspect miss-sites unwired |
| D3 | MET-WITH-FINDINGS | Correct for type hits; **member hits teach an address that fails** |
| D4 | MET-WITH-FINDINGS | Ranking/filtering/honesty real; the cache is not what was specified and fossilizes on the resident |
| D5 | MET-WITH-FINDINGS | Spine, teardown, refusal honesty all real; "no recording left behind" unverified/plausibly false; capabilities inferred not discovered |
| D6 | MET-WITH-FINDINGS | 7 kinds genuinely set+hit on a live JVM; per-kind full-cycle evidence overstated; pump-thread deadlock hazard |
| D7 | MET-WITH-FINDINGS | All four mutations proven by effect; **evaluate unrecorded**; OSGi identity dropped |
| D8 | MET-WITH-FINDINGS | SUSPEND_NONE genuinely proven; `perturbs:true` honesty real; stdout stall + capture deadlock hazards |
| D9 | MET-WITH-FINDINGS | First-violation discrimination genuinely proven; `violated:true` misnomer on the 4th outcome; real replays stall on our own pipe |
| D10 | MET-WITH-FINDINGS | Floor real, deadlock chain genuinely named; **every thread id wrong**; timeout unreachable; "bounded" dumps enforce no bound |
| D11 | **borderline NOT-MET on body text** | Measure passes honestly; **wall time absent, call counts substituted** |
| D12 | **borderline NOT-MET on body text** | Percentile/correction math verified correct by hand; **domain events absent**; "measured honestly" breaks in the very regime named |
| D13 | MET-WITH-FINDINGS | Measure genuinely proven; the weakest engineering in the sprint (T1.2, T1.8–T1.11) |
| D14 | MET-WITH-FINDINGS | hs_err baseline is the real deal, grounded on genuine crashes; **the adapter's one value-add never fires** |
| D15 | **half-met** | Closure (both doors) real, search-free, well-tested; **recall-before-probe not built** |
| D16 | AMENDED-AND-MET, with findings | The Stage-8 async amendment is legitimate and its claims verified in code; expiry-prune drift; projectKey stripped on bare `resume` |
| D17 | **MET** | All three statements at full strength in both places (verified verbatim); only the guarding test is weak |
| D18 | **MET** | Tags, notes, version carriers, exactly-two-new-tools all verified locally |

## Recommendation

1. **v2.13.1 patch** — Tier 1. These are wrong answers, hangs and leaks on a shipped
   binary; several are one-line fixes (T1.1 radix, T1.2 `HashMap` instead of `Map.of`,
   T1.3 strip the offset suffix before matching, T1.4 drain-then-wait ordering). The
   larger ones (T1.5 loss accounting, T1.6 stdout drain, T1.7 invocation off the pump
   thread) are contained.
2. **Harald's call** — Tier 2. Each clause is either built or gets a recorded amendment
   with his signature. It is not my ranking to make, and filing them away as "not today's
   fight" is exactly what the standing rule forbids.
3. **Immediate, regardless** — Tier 3: correct the dossier gate rows and the spec
   close-out sentence. They are false statements of record and they are mine.

## The process finding

Every checkpoint in this sprint gated on the deliverable's **"Measure:"** line. The spec's
requirements live in the deliverable's **body**. Where the two differ, the body is the
contract — and three capabilities (D11 wall time, D11 call counts, D12 domain events) plus
several Approach clauses were lost precisely in that gap, then certified absent-of-narrowing
by a close-out written by the same agent that wrote the gates. The /sprint protocol already
knows this failure mode — it is why the auditor is a fresh context and why it audits against
the RAW, not the editor's summary. The lesson is that the same discipline must extend past
the spec into **execution**: the checkpoint gate must enumerate the body clauses, not the
measure, and the close-out's "no narrowing" claim must be produced by someone who did not
write the stages.
