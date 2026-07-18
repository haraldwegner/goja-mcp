# ARCHITECTURE-injector — the intelligent injector (Sprint 26)

> Design-mode artifact (architect seat convention). Version 1, 2026-07-18 —
> produced between spec sign-off and plan promotion (the mandatory /sprint
> design step). Watch mode diffs checkpoint changes against THIS picture.

```
                    ┌──────────── mcp (the resident) ────────────────┐
 every tool call ──►│ HttpTransport: + Mcp-Session-Id ──┐            │
                    │ ToolRegistry.callTool (THE CHOKE) │            │
                    │  ├─ DiskSync report (files changed)▼           │
                    │  ├─ EVENT TAP ───────────► SessionLedger       │
                    │  │   tool errors, undo,    (per session:       │
                    │  │   mechanical touches,    call sequence)     │
                    │  │   gate calls             │                  │
                    │  ├─ WATCH ENGINE ◄──────────┘                  │
                    │  │   delta → per-file detectors → vs baseline  │
                    │  │   → finding + "design fix or bandage?"      │
                    │  │   (noise budget, store decay)               │
                    │  └─ STEERING ATTACH (applySteering compose):   │
                    │      watch findings · decision test on the     │
                    │      three message shapes · seat-discipline    │
                    │      flags · seat SUGGESTION (promoted seat    │
                    │      switch) · steer/flag (promoted edit sw.)  │
                    │ LEARNERS (new pkg org.jawata.mcp.learn):       │
                    │  online logreg (per event) + tree (rebuild/N)  │
                    │  features = AST-diff facts ONLY                │
                    │  rolling record vs rules → DECIDES/SHADOW      │
                    │  (+ demotion guard) — state + events in H2     │
                    │  (SchemaMigrations sibling tables)             │
                    └───────────────▲───────────────▲────────────────┘
      events also arrive from:     │               │ /train, /memorize
      observer hook (grep slips,   │               │ (client commands →
      curl→experience) · runner    │               │  existing tools as
      journal (seat_run records;   │               │  kinds — toolCount 45)
      + NEW verdict re-record)     │               │
                    ┌──────────────┴─── studio ────┴─────────────────┐
                    │ STOP HOOK (Claude Code only — platform fact):  │
                    │  decision-test bounce + seat-gate block        │
                    │ conductor: UTILITY_MAP → /memorize /train      │
                    │  rendered per client, deployed like seats      │
                    │ runner: record_human_verdict → store re-record │
                    └────────────────────────────────────────────────┘
```

Where new code lands: mcp — `org.jawata.mcp.learn` (learners, features,
event tap types), session threading in transport/protocol/registry, watch
engine beside the registry, H2 migrations; studio — the Stop-hook quartet,
`UTILITY_MAP` + utility renderers/writers/removers in conductor +
manager_service, the verdict re-record in runner.rs.
Must not touch: the 45-tool surface (new kinds/actions only), the seat
files' semantics, the runner loop/gates, the guard/observer contracts
(extended, not changed), the Sprint-22 rule behavior as the floor.
Dependency direction: learn pkg depends on knowledge (store) + core AST;
registry depends on learn (tap/serve); nothing in learn knows tools.


Seam facts verified at design time (2026-07-18, mcp at 921f1e3): the
steering choke ToolRegistry.callTool :256-296; no session identity exists;
MechanicalChangeJournal/DiskSyncGuard know the delta; fallback slips
already land as store rows; tool errors are log-only (the tap site); H2
SchemaMigrations is additive; the studio hook machinery has a generic
section writer; Cursor has no stop-equivalent event.
