# ARCHITECTURE — Sprint 27a: recall honesty (Version 1)

> Design-mode artifact per /sprint, produced after GATE 1 (spec:
> `jawata-enterprise/docs/sprints/jawata-mcp/sprint-27a-recall-honesty.md`,
> signed 2026-07-22). The Phase-B plan is written against THIS picture; the
> architect's end-of-sprint as-built pass names the production caller of
> every capability drawn here.

## The picture

```
                                cue (any surface)
                                      │
                     ┌────────────────┴────────────────┐
                     │  full-corpus scan (existing)     │
                     │  EmbeddingIndex — ALL scores,    │
                     │  not just the top-K              │
                     └────────────────┬────────────────┘
                                      │ the cue's SCORE PROFILE
                     ┌────────────────┴────────────────┐
                     │  AnalogyPolicy  (NEW, knowledge) │
                     │  peak-vs-background: abstain OR  │
                     │  speak top-N (N ≤ 3), margins    │
                     │  DERIVED from committed labels   │
                     └────────────────┬────────────────┘
              ┌──────────────┬────────┴───────┬──────────────────┐
              │              │                │                  │
        recall front    refactoring      dispatch          precedent
        door (Exp.-     pre-advice       recall            advisory tier
        Retrieval)      (Exp.Advisor —   (DispatchRecall   (ToolRegistry —
                        F3: stops        rides the same    best similar case
                        discarding)      carrier)          goes through the
                                                           policy too)
              │              │                │                  │
              └──────────────┴───────┬────────┴──────────────────┘
                                     │  ONE rendering contract:
                                     │  labeled analogy, basis in words,
                                     │  provenance, NO similarity number,
                                     │  absence = "nothing here"

   dedup (write path): same score machinery, its own two-band operating
   point from dedup-labels.json — certain "duplicate_of" / softer
   "possible_duplicate_of" — computed, not guessed.
```

## Where new code lands

- **`org.jawata.mcp.knowledge.AnalogyPolicy` (NEW)** — the single authority.
  Input: the cue's full score profile (id → score, unfloored). Output: an
  ordered spoken set (0–3 ids) or abstention. Margins are static constants
  WITH provenance comments naming the labeled data that derived them.
  Pure decision logic: no store access, no rendering — trivially testable.
- **`ExperienceRetrieval`** — `meaningScores()` already computes the full
  unfloored profile; it feeds the policy instead of applying
  `NOMINATION_FLOOR`+cap inline. `NOMINATION_FLOOR` keeps its ORIGINAL job
  (volume cap for fact-gate nomination) and loses the user-facing one.
- **`ExperienceAdvisor`** — consumes analogies from the recall result
  instead of discarding non-MATCH results (the F3 line); renders through
  the same carrier.
- **`ToolRegistry`** — the advisory-tier pick (best non-identity hit) asks
  the policy before speaking. Identity warn+charge path UNTOUCHED.
- **`EmbeddingService.textOf`** — symptoms join summary+details;
  `EmbedderIdentity.CURRENT_VERSION` bumps to 2 → the shipped self-heal
  re-embeds the corpus. No new migration.
- **`EmbeddingIndex`** — dedup gains the two-band operating point (both
  bands derived from `dedup-labels.json`); `embeddedCount`/`totalCount`
  surfaced through `ExperienceTool` stats (D5).
- **`build/end-to-end-test.sh` + committed fixture store** — the fixture is
  a small committed dataset of invented entries (schema-versioned content:
  old-version rows, no-vector rows, a rejected row, a superseded row, a
  near-duplicate pair, a seeded seat_run); the script COPIES it per run.
  Exact carrier format (H2 file vs JSON import) is the plan's freedom.
- **Fix round** — each issue in its own lane; none touches the seams above
  except #9 (the main/test source-root classifier, `org.jawata.core`
  project model), which `compile_workspace(scope=)` and later detectors
  consume.

## Dependency direction (unchanged)

`embed ← knowledge ← learn ← tools` — the policy sits in `knowledge`,
below `learn` and `tools`, above `embed`. The `embed` package stays PURE.

## Must not touch

- The FACT gate (address-bound, terminal) and its semantics.
- The identity tier: `IdentityMatch`, `PrecedentLedger`, warn+charge.
- The keyword nominator — it remains the degrade path, exactly v3.3.1.
- Choke order; capture selectivity; toolCount 45; one-universal-archive.
- The no-score rule: no similarity number in any rendering, any surface.

## The 27a→33 boundary

The quality ledger stays read-only measurement. The policy's
abstain/speak counts flow into the EXISTING counters (fired per surface);
no new analysis machinery — Sprint 33 reads, this sprint only feeds.
