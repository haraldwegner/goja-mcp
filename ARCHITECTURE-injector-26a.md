# ARCHITECTURE-injector-26a — the intelligent injector, redesigned

> Design-mode artifact (architect seat convention). Version 1, 2026-07-20 —
> produced between GATE-1 spec sign-off and Phase-B plan promotion (the mandatory
> /sprint design step). **Supersedes `ARCHITECTURE-injector.md` (Sprint 26)** —
> that picture's LEARNERS box (logreg/tree/rolling-record models that
> DECIDE/SHADOW) is REMOVED; 26a builds zero ML models. Watch mode diffs
> checkpoint changes against THIS picture.
>
> Spec: `jawata-enterprise/docs/sprints/jawata-mcp/sprint-26a-intelligent-injector-redesign.md`
> (GATE 1, Harald 2026-07-20). Deliverables D1–D5 mapped below.

## The picture

```
   CAPABILITY CATALOG (D1) ── deep how-to + pitfalls per tool, authored
   surfaced proactively via: primer · seat prompts · MCP server-instructions
   · conductor. The agent KNOWS the toolbox + how to drive it from the start.
        │ (context injection at session start / seat load)
        ▼
   ┌──────────────────── mcp (the resident) ─────────────────────────────┐
   │ HttpTransport: Mcp-Session-Id (from Sprint 26)                       │
   │ ToolRegistry.callTool ── THE CHOKE (serve + tap) ───────────────┐    │
   │  ├─ DiskSync / MechanicalChangeJournal: the delta ──────┐       │    │
   │  ├─ CAPTURE (D2, SELECTIVE) ─ EventTap ──────► STORE ◄──┼───┐   │    │
   │  │   outcome-bearing ONLY: refactor compile      (H2 general  │   │    │
   │  │   result · tool error · fallback.              store +     │   │    │
   │  │   NOT routine reads/searches.                  tool-usage  │   │    │
   │  │   [already selective in the tap]               lane, new   │   │    │
   │  │                                                migration)  │   │    │
   │  ├─ ARCHITECT-INVOLVEMENT GATE (D3b) ◄──────────────────┘       │   │    │
   │  │   rule on the delta: smell (quality detectors) OR              │   │    │
   │  │   signature/hierarchy change OR >~500 LoC → involve architect  │   │    │
   │  │   (staged diff, auto_apply=false, reviewable before apply)     │   │    │
   │  └─ STEERING ATTACH (applySteering compose — append, never       │   │    │
   │      overwrite a tool's own line):                                │   │    │
   │        • REFLEX→CAPABILITY injection (D1): stopwatch→profile,     │   │    │
   │          armor→debug, grep→search, hand-edit→refactor (extends    │   │    │
   │          the existing jawata-fallback guard to runtime reflexes)  │   │    │
   │        • WEIGHTED PRECEDENT push (D2): retrieval hit → "tool X     │   │    │
   │          worked in a case like this" as a steer with a            │   │    │
   │          justification-cost to defect (not an optional hint)      │   │    │
   │        • architect-gate flag · seat-discipline flags (ServerChecks)│  │    │
   │ RETRIEVE (D2, BASELINE non-ML) ─ keys + keyword over the store ───┘   │    │
   │   (extends Sprint-26 D6 keyword recall + primer; over tool-usage      │    │
   │   rows AND all general categories). → Sprint 27 replaces with          │    │
   │   MiniLM embeddings, SAME contract.                                    │    │
   │                                                                        │    │
   │ RULES, not models: PolicyDial · ServerChecks · WatchEngine (findings)  │    │
   │ RETIRED (D4): OnlineLogreg · HandTree · Learner · RollingRecord ·      │    │
   │   FeatureVector · LearnerService(model orchestration). KEPT: EventTap ·│    │
   │   SessionLedger · LearnerEvent(row) · PolicyDial · ServerChecks ·      │    │
   │   WatchEngine(as rule).                                                │    │
   └──────────▲───────────────────────────────────────────▲───────────────┘
   events also from:                                       │ /train /memorize
   observer hook (grep slips) · runner journal             │ (client commands)
   (seat_run + verdict re-record)                          │
   ┌──────────────────────── studio ───────────────────────┴───────────────┐
   │ HOOKS: reflex detection client-side (Stop / PreToolUse / Cursor        │
   │   beforeSubmitPrompt) → surface the matching tool (D1) · Stop-gate     │
   │   bounce (D5 of Sprint 26, kept)                                       │
   │ CONDUCTOR + RUNNER (D3a — SEAT WORKFLOW, coded): the architect seat    │
   │   wired into the sprint/plan/design-mode/checkpoint flow; javadoc/test │
   │   seats at their gates; debug/profile at the reflex points. NOT the    │
   │   agent remembering — the process runs it.                            │
   └────────────────────────────────────────────────────────────────────────┘
```

## Deliverable → seam map

| D | What | Primary seam(s) | New vs extend |
|---|---|---|---|
| D1 capability awareness | deep how-to catalog + reflex→capability injection | catalog: primer / seat prompts / MCP server-instructions / conductor; injection: `applySteering` at the choke + studio reflex hooks | catalog authored NEW; injection EXTENDS the `jawata-fallback` guard to runtime reflexes |
| D2 experience loop | selective capture → store → baseline retrieve → weighted push | `EventTap` (capture, already selective) → H2 store tool-usage lane (`SchemaMigrations` sibling) → keyword recall (extend Sprint-26 D6) → `applySteering` push | capture/store EXTEND; retrieval EXTENDS D6; weighted-push NEW at the choke |
| D3a seat workflow | per-seat where/what/when, coded | studio conductor + runner + hooks | EXTEND (grep reflex wired; fill architect-in-sprint/plan + runtime reflexes) |
| D3b tool-usage rules | reflex→capability map + architect-involvement gate | the gate at `ToolRegistry.callTool` on the delta (`DiskSync`/`MechanicalChangeJournal` + `find_quality_issue` filePath detectors) | NEW rule reusing existing delta + detector seams |
| D4 edit-ML retired | remove the model; keep capture | delete `OnlineLogreg`/`HandTree`/`Learner`/`RollingRecord`/`FeatureVector`, gut `LearnerService` model orchestration; keep `EventTap`/`SessionLedger`/`LearnerEvent` | REMOVE code; the capture path folds into D2 |
| D5 F1 store patch | LOCK_TIMEOUT + widened retry | `H2ExperienceStore` URL + `LearnerEventStore` retry | independent patch (already written) |

## Dependency direction

- The **capture/store/retrieve** lane depends on `knowledge` (the H2 store) + the
  event/delta seams; it does NOT depend on the tool implementations.
- The **registry/choke** depends on the loop (tap to capture, retrieve to push)
  and on the rules (PolicyDial/ServerChecks/architect-gate); nothing in the loop
  knows individual tools.
- The **catalog (D1)** is data + surfacing; the conductor/primer/hooks consume it.
- **studio** depends on mcp only through the hook/curl→`experience` contract and
  the conductor's command rendering — unchanged contracts, extended content.

## Where new code lands

- mcp: the D2 retrieval-push + weighted-steer compose in `applySteering`; the
  architect-involvement gate beside the choke; the H2 tool-usage lane migration;
  the capability catalog as surfaced knowledge (store/primer content + a
  server-instructions/seat-prompt block). Deletions per D4.
- studio: the reflex-detection hooks (client-side surfacing of the matching
  tool) and the conductor/runner seat-workflow wiring (architect into
  sprint/plan/checkpoints; runtime reflex points).

## What MUST NOT be touched

- The 45-tool surface (new kinds/actions only; toolCount stays 45).
- The seat files' *content* semantics (their stances) — only their **workflow
  placement** (D3a) is wired.
- The guard/observer/REFUSE contracts — EXTENDED (runtime reflexes, weighted
  precedent), never relaxed. The jawata-fallback justification floor survives.
- The runner loop/gates behavior as the floor.

## Seam facts (to drift-check at Phase B)

Carried from Sprint-26 design (verified 2026-07-18, mcp `921f1e3`) — re-verify at
plan time: steering choke `ToolRegistry.callTool` (`applySteering` writes-if-null,
widen `steeringFor`); `Mcp-Session-Id` now threaded (Sprint 26); the delta at
`MechanicalChangeJournal` + `DiskSyncGuard`; per-file detectors =
`find_quality_issue` kinds accepting `filePath`; the smell baseline machinery;
`EventTap` already selective (errors, edit outcomes, undos, gate calls); H2
`SchemaMigrations` additive; keyword recall + primer from Sprint-26 D6; studio
hook quartet + conductor `COMMAND_MAP`/`UTILITY_MAP` + observer curl→`experience`.

## The 26a → 27 boundary (drawn here so the plan honours it)

26a ships the loop end-to-end with **baseline keyword retrieval** — a complete,
tested slice, not a storage layer. Sprint 27 replaces ONLY the retrieval box with
**MiniLM embeddings** (compress context → nearest-neighbour), on the SAME capture
schema and the SAME `applySteering` push seam. The plan must keep the retrieval
call behind a seam so 27 swaps the implementation without touching capture,
store, or surfacing.
