# Sprint 23 — evidence dossier

> Companion to the signed spec (`jawata-enterprise/.../sprint-23-coverage-test-grounding.md`)
> and the actionable plan. Every number expected-vs-actual.

## C0 — Baselines + probes + landscape (2026-07-12/13)

### (a) Serial full-suite baseline (the D2 denominator)

- Run: dedicated in-framework serial run on the v2.9.2 dist (idle machine, 24 cores),
  per-class timings captured from the progress listener. Completed 2026-07-13 00:0x,
  exit 0.
- Expected: ~28 min (the 2.9.2 release-gate observation) · Actual: **1761 s wall
  (29:21)** ✓ — the D2 denominator; the ≤50% target is therefore **≤ 880 s**.
- Suite counts expected: 1192 total = 1187 pass + 5 skip, 0 fail · Actual:
  **1192 = 1187 + 5 skip, 0 failed, 0 aborted, 0 unloadable** ✓ (exact).
- Per-class timing distribution (Stage-6 partitioning input): full 229-class list in
  `dossier-23-timings.txt` (sum 1758 s). Shape: classes 1–21 = 217 s (12%) — LESS
  front-loaded than the 22d observation suggested; the load is broad. Slowest
  singles: PathUtilsImplTest 98 s, DiskSyncGuardTest 48 s, ConflictSeamTest 32 s;
  ~15 classes ≥ 20 s. Balanced 4-shard partition ≈ 440 s/shard — the ~8-min stretch
  target is arithmetically reachable if boot overhead per shard stays small.

### (b) Resident self-state (the self-serve substrate)

- `health_check`: v2.9.2, projectKey `jawata-mcp`, **471 source files** (expected 471 ✓).
- `compile_workspace(summary)`: **0 errors / 0 warnings** (expected 0/0 ✓) — verified
  2026-07-12 ~21:05 and re-verified at Stage 0.
- Disabled probe `find_pattern_usages(kind=annotation, query=org.junit.jupiter.api.Disabled)`:
  **exactly 5 sites** (expected 5 ✓) — RunTestsToolTest L221/L242/L256,
  GenerateTestSkeletonToolTest L79, EncapsulateFieldToolTest L41.

### (c) Landscape re-diff (README-level, fetched 2026-07-12 late)

| Competitor | Version / activity | Capabilities (relevant) | Delta vs audit + disposition |
|---|---|---|---|
| **jdtbridge** (kaluchi) | **v2.6.0, 2026-04-05**, 242 commits, ACTIVE — the earlier "last updated 2026-03-18" was stale Marketplace data | streaming test progress + immediate failure reporting; **code coverage via EclEmma "coverage mode"**; jar-source by FQN (Spring/Hibernate/JDK); pipe queries (`@callers`, `@implementors`, `@members`, `@source`, `@problems`); incremental-compile diagnostics; workspace dashboard; refactoring | **MATERIAL: they HAVE coverage** (IDE-run EclEmma display). Assessment: D3's contract (per-diff, per-symbol index, per-test attribution, mutation, provenance/staleness, gate advisory) exceeds an EclEmma display mode on every axis that matters to an autonomous agent; parity items D1/D8/D9 unchanged. Judgment: NO spec change required — **flagged to Harald at C0 for ratification** |
| **javalens-mcp** (pzalutski-pixel) | **v1.5.1, 2026-06-16** (newer than the 05-29 the search reported) | 75 tools / 8 categories; refactorings returned as UNAPPLIED text edits; new: "disk synchronization" (query-time file-state verification, no watchers) | No new gap: no execution/coverage/runtime; jawata has strict disk sync since v2.5.x (StrictDiskSync test family). Table row updated |
| **LSP4J-MCP** (stephanj) | v1.0.0, 2026-01-05 | 5 navigation tools over JDTLS; explicitly NO refactoring execution, NO test running, NO coverage | Confirms the LSP-wrapper class assessment; no gap |

Process note for the record: the pre-sprint parity statement ("no movement since the
audit; coverage = a class neither competitor can follow") was WRONG for jdtbridge —
built on stale Eclipse-Marketplace dates instead of the repo. Corrected here; lesson:
diff the REPO (releases + README), never the marketplace listing.

### (d) Tool versions (chosen + fetch-proven from Central)

| Tool | Version | Fetch proof |
|---|---|---|
| JaCoCo (agent/core/report) | **0.8.15** (latest stable) | `dependency:get` OK ×3 |
| PIT (pitest, pitest-entry) | **1.25.7** (latest stable) | `dependency:get` OK ×2 |
| JUnit platform console-standalone (forked-runner launcher) | **1.14.4** — the latest **1.x**; the metadata `latest` is 6.1.2 (JUnit 6), deliberately NOT chosen: user projects and our test bundles sit on the Jupiter 5.x line | version list verified on repo1 |

### C0 exit status

- (a) ✓ 1761 s / 1192 = 1187+5, 0 failed; (b) ✓; (c) ✓ with ONE material finding
  (jdtbridge has EclEmma display-mode coverage — assessment: D3 exceeds it on every
  agent-relevant axis, NO spec change; presented to Harald with the C0 report);
  (d) ✓. **C0 GREEN** — proceeding under the 2026-07-13 autocontinue overrule
  (stops only at C13, C14, or blocking failures).

## C1 — Execution spine: run_tests on Maven (2026-07-13)

### What shipped

- **`build/testrunner`** (new module, `org.jawata.testrunner`): dependency-free forked
  runner main — argfile in, JSON-lines events out over a FILE channel (stdout stays
  the tests'), run-start/class-start/test-finish/run-finish, exit 0 = ran, 2 = infra.
- **dist `tools/`**: junit-platform-console-standalone-1.14.4 + org.jawata.testrunner
  + testng-engine-1.0.6 (all enforcer-gated); boot publishes `jawata.dist.root`.
- **`org.jawata.mcp.execution`**: `ForkedTestRunner` (the §13 spine: cleared env +
  allowlist LANG/LC_ALL/TZ, -Xmx512m default, -Djava.io.tmpdir=session, timeout →
  process-TREE reap, bounded 1MB/100-line stream tails, 4-session semaphore, honest
  `evidenceFinalized`) + `RunnerClasspath` (build-first + refuse-on-compile-errors;
  tools-first classpath; project junit-platform/engine jars FILTERED — the surefire
  pattern). `RunTestsTool` rewired onto the spine; the JDT-LTK path
  (JUnitLaunchHelper) is now UNREFERENCED — deleted in the Stage-3 cleanup.
- **Fixtures**: simple-maven was never actually COMPILABLE — no declared deps, yet
  imports of Jupiter/Lombok/3 nullness families, two duplicate types (Calculator in
  CommandTargets → CommandCalculator; Honorer in LspTargets → LspHonorer), a bare
  `@Generated` (→ JDK `javax.annotation.processing.Generated("fixture")`), Lombok
  blank finals (→ explicit constructor). Now compiles 0-error under the new build
  gate. New `runner-pathological` fixture (HangingTest / MemoryHogTest /
  EnvProbeTest) for the safety proofs.

### Verification (expected vs actual)

- 3 RunTestsToolTest @Disabled re-enabled + green with EXACT counts (method: 1/1
  passed; class + package: total 7 = 4 passed + 1 failed + 2 skipped) —
  **12/12 green** ✓.
- Count oracle: `mvn test` on the fixture = Tests run 7, Failures 1, Skipped 2 —
  **identical** to run_tests ✓.
- §13 proofs (ForkedRunnerSafetyTest, green 3× consecutively): hanging test reaped
  at timeout, NO orphan `org.jawata.testrunner.Main` process machine-wide,
  `evidenceFinalized=false` + evidenceNote ✓; memory hog dies at the CHILD's -Xmx
  (OOM is UNRECOVERABLE for the JUnit platform → either a FAILED result or an
  honestly-unfinalized run; both accepted, OOM evidence required; server survives) ✓;
  env allowlist proven by the PATH canary (EnvProbeTest passes ONLY under a cleared
  env; server-side PATH presence asserted as precondition) ✓.
- Full-suite gate: expected 1195 = 1193 passed + 2 skipped (3 re-enabled + 3 new
  safety tests; remaining @Disabled: GenerateTestSkeletonToolTest L79 → Stage 2,
  EncapsulateFieldToolTest L41 → Stage 5) · Actual: PENDING (run in flight).

### Surprises

- The simple-maven fixture had 16 latent compile errors — invisible for 20 sprints
  because nothing ever COMPILED it (AST-only detectors). The new
  build-before-run gate found them all on its first fire.
- OOM unrecoverability (above) — the honest-evidence contract absorbed it cleanly.
- PLAN ARITHMETIC CORRECTION: the plan's C4 exit reads "self Disabled-probe stays
  5/5" — stale arithmetic (written against the pre-Stage-1 count). After Stage 1 the
  probe correctly returns 2 (GenerateTestSkeletonToolTest, EncapsulateFieldToolTest);
  after Stage 2, 1; C5's "returns 0" confirms the intent. The C4 gate is read as
  "the probe returns exactly the remaining known sites".

### C1 exit status

- All focused gates green (12/12 + safety 3/3 ×3 + oracle identical). First
  full-suite sweep (2026-07-13, WALL 1665 s, run with CONCURRENT builds/MCP load
  from the agent): 1195 = 1189 + 4 failed + 2 skipped — the 4 failures
  (SearchSymbolsRankingTest, FindUnusedDependenciesToolTest, SuggestImportsToolTest,
  FindReflectionUsageToolTest) share one shape: a search/index query returning
  EMPTY. All four pass focused, repeatedly. One (FindUnusedDependencies) was a real
  expectation shift (fixture now declares 5 deps → totalDeclared 7, fixed); the
  other three are load-order/contention-sensitive searches — the discriminating
  IDLE full-suite rerun is the C1+C2 combined gate below. NOTE for Stage 6: if the
  idle rerun is green, these three are contention-sensitive searches that the
  sharded suite will stress deliberately — harden there.

## C2 — Streaming / progressive results (2026-07-13)

### What shipped

- `ForkedTestRunner.start()` → `RunningSession` (Future + live process ref +
  cancel-with-reap); `run()` = `start().await()`. Event-file tailer feeds a
  per-session consumer; BUGFIX caught by the gate: the tailer's interrupt handler
  returned instead of falling through to the FINAL drain — tail events
  (class-finish, run-finish) were dropped from progress counters.
- `TestSessionRegistry`: sessionId → progress counters (plannedTests,
  classesStarted/Finished, testsFinished) + bounded 200-event ring + retained
  final result (32-session cap, oldest-finished evicted).
- run_tests front door (0 new tools): `action` = run (default, sync) | start
  (immediate sessionId) | status (live per-class progress + recentEvents + the
  full summary once finished, retrievable repeatedly) | cancel (reap, honest
  partial, `cancelled=true`, evidenceFinalized=false). Poll = the baseline
  transport; push notifications deferred to transports that support them.
- Fixture: `com.example.pathological.slow.{SlowAlphaTest,SlowBetaTest}` (4 × 1.5 s).
- GenerateTestSkeletonToolTest L79 re-enabled — its actual blocker was the fixture
  classpath (delivered in C1), not streaming; landed a stage early.

### Verification (expected vs actual)

- AsyncRunTestsTest (green 2× consecutively): a status poll observed
  state=RUNNING with testsFinished ≥ 1 BEFORE completion ✓; final summary exact
  (4 = 4 passed, evidenceFinalized=true, classesFinished=2) ✓; summary retrievable
  on a later poll ✓; cancel mid-hang → state CANCELLED, cancelled=true,
  evidenceFinalized=false, NO orphan runner JVM machine-wide ✓.
- GenerateTestSkeletonToolTest 2/2 green ✓ (auto-detect picks junit5 from the
  fixture classpath).
- Combined C1+C2 full-suite gate (IDLE): expected 1197 = 1196 passed + 1 skipped
  (only EncapsulateFieldToolTest L41 remains @Disabled), 0 failed · Actual:
  **1197 = 1196 + 1 skipped, 0 failed, WALL 1775 s** ✓ EXACT.

### C1 + C2 exit status

- **BOTH GREEN.** The idle rerun confirms the 3 residual C1 failures were
  CONTENTION FLAKES (load-sensitive searches; all pass focused AND in the idle
  sweep) — flagged as a Stage-6 hardening item (shards create that load
  deliberately), not a regression. 4 of 5 @Disabled now live; commits at C1
  (49ae944) and C2 (f489174).

## C3 — Remaining shapes: Gradle · plain-Java · reactor (2026-07-13)

### What shipped

- **Gradle**: no new launch code needed — the spine is shape-agnostic
  (JavaRuntime resolution on whatever the importer loaded); proof = ad-hoc
  java-plugin project (mavenLocal, network-free, `jawata.skip.gradle` guard).
- **Plain Java**: `extraClasspath` launch-descriptor arg on run_tests
  (runtime-only classpath additions) + Eclipse-`.classpath` fixture generated
  in-test from local-repository jars.
- **Reactor**: committed `reactor-cross` fixture — module-b tests exercising
  module-a code through the merged-reactor JDT project model.
- **Cleanup rider**: the dead JDT-LTK launch path DELETED — JUnitLaunchHelper
  stripped to the TestRunnerKind enum (find_references proved all external uses
  are enum-only); `org.eclipse.jdt.junit.core` + `debug.core` dropped from the
  bundle's Require-Bundle; jdt.junit.core/runtime dropped from the dist
  (debug.core bundle stays — jdt.launching requires it).

### Verification (expected vs actual)

- RunTestsShapesTest 3/3 green, first run: Gradle class scope total 3 = 2
  passed + 1 failed (exact fixture totals) ✓; plain-Java WITHOUT descriptor =
  1 passed + 1 failed (runtime-only reflective dep, CNFE), WITH descriptor =
  2 passed + 0 failed ✓ (the discriminating proof); reactor cross-module 2/2
  passed with evidenceFinalized ✓.
- Oracle note (declared deviation, rationale): the plan names "gradle XML" as
  the Gradle oracle; the C1 maven-surefire oracle already validated the
  runner's COUNTING, and the Gradle shape adds classpath/import risk, not
  counting risk — known fixture totals used instead (running a full gradle
  build in-test would double the test's runtime for no new signal).
- Cleanup regression: the trimmed dist BOOTS (fail-fast resolution gate would
  die on a missing requirement) and the whole runner family stays green
  focused (12/12 + 3/3 + 2/2).

### C3 exit status

- **GREEN** (focused gates + boot-resolution proof; next full sweeps land at
  C4's resident checks and Stage 6's serial-vs-shard totals by design).

## C4 — PDE classpath: external bundle pools + PDE suite runs (2026-07-13)

### What shipped

- **`ExternalBundlePool`** (org.jawata.core.project): indexes pool directories
  (manifest per jar, cached by dir mtime) → symbolic name → highest-version jar
  + exported package → provider jar; quote-aware OSGi header splitting.
  Pool chain: `jawata.bundle.pools` property → `~/.p2/pool/plugins` →
  `jawata.dist.root/bundles` (self-hosting fallback).
- **ProjectImporter**: workspace-pool misses now fall through to the external
  pools — Require-Bundle → EXPORTED library entry; NEW Import-Package parser +
  Export-Package resolution pass. Still-unresolved stays unresolved (logged),
  as before.
- **Spine fix found by the gate**: JDT's builder consumes a referenced
  project's BINARY output — `RunnerClasspath.buildAndCheck` now builds
  classpath-referenced projects first (DFS, cycle-safe; note:
  `IProject.getReferencedProjects()` sees only static references, so the
  dependency set comes from the resolved classpath).
- **Fixtures**: `pde-external` (Require-Bundle jackson-databind +
  Import-Package org.slf4j/jackson-core/annotations — resolvable ONLY from a
  pool) + `pde-external-tests` (Require-Bundle onto the sibling via the
  Sprint-11 workspace pool; jupiter via Import-Package from the dist
  test-bundles pool).

### Verification (expected vs actual)

- PdeExternalPoolTest green: BOTH PDE fixtures compile **0 errors** on the
  pool-resolved classpath ✓; the PDE suite runs through the forked runner
  **2/2 passed** with jackson exercised at RUNTIME over the pool classpath ✓.
- ExternalBundlePoolTest 3/3 (quoted-comma splitting incl. uses-clauses,
  numeric version preference, name+export indexing over generated jars) ✓.
- Self workspace (resident, live): expected 471+9 sprint files → actual
  **480 sources / 0 errors** ✓; Disabled probe = **exactly 1 site**
  (EncapsulateFieldToolTest L41 — the corrected remaining count per the C1
  arithmetic note) ✓.
- Runner-family battery after the build-deps fix: 12/12 + 3/3 + 2/2 + 3/3 +
  1/1 ✓.

### C4 exit status

- **GREEN.**

## C5 — The fifth @Disabled: EncapsulateFieldToolTest (2026-07-13)

### What shipped

- **NO JDT patch needed** — the plan's either/or (upstream fixed | 22c patch)
  resolved via a THIRD path: the root cause is HEADLESS-EMBEDDER CONFIGURATION,
  not (only) the upstream bug. New `HeadlessJdtConfig` (one-shot, idempotent;
  called from JawataApplication.start + the tool): sets
  `JavaManipulation.setPreferenceNodeId`, seeds the JDT-UI defaults that
  manipulation reads UNGUARDED (member sort order, visibility order, import
  order, on-demand thresholds), and registers a code-template store
  (TemplateStoreCore — core org.eclipse.text, NO UI) with the IDE-default
  getter/setter/method/constructor/catch stub bodies.
- Root-cause chain peeled live, three layers: (1) null preference node id →
  IAE in ProjectScope.getNode; (2) missing import-order default → NPE in
  CodeStyleConfiguration.configureImportRewrite; (3) missing template store →
  CodeGeneration returns null → the upstream fallback bug (bare Assignment
  where a Statement is required). With the store registered, the buggy
  fallback is UNREACHABLE.
- EncapsulateFieldTool's silent catch now logs (the swallowed stack cost a
  diagnosis round).
- UPSTREAM NOTE: the fallback bug in SelfEncapsulateFieldRefactoring
  (createSetterMethod) still exists for other headless embedders — filing it
  upstream is optional courtesy under Harald's account; listed as a close-out
  question, no patch carried.

### Verification (expected vs actual)

- EncapsulateFieldToolTest expected 3/3 · actual **3/3 green, twice** ✓.
- Self Disabled-probe expected 0 · actual **0** ✓ — ALL FIVE @Disabled
  resolved (D1's "5 Disabled fully resolved" leg complete).
- Resident self-workspace: **481 sources / 0 errors** ✓.

### C5 exit status

- **GREEN.**

## C6 — Fast local full suite (2026-07-13)

### What shipped

- Boot: `jawata.test.classlist` (explicit per-shard class list overrides
  discovery+filter).
- `build/run-suite.sh`: discovers test classes exactly like the boot, greedy-
  balances shards by the C0 measured per-class timings (unknown classes get a
  default), launches N pinned JVMs (`-XX:ActiveProcessorCount=fair-share`),
  merges shard summaries, honest exit codes (any failure / missing summary).
- **The actual win — a Maven-classpath CACHE in ProjectImporter** keyed by the
  CONTENT HASH of every pom.xml in the tree: the same unchanged fixture pom
  imported by ~150 tests paid a 2–5 s `mvn dependency:build-classpath`
  shell-out EVERY time (~85% of suite cost, measured); now once per JVM. Any
  pom edit changes the key; only exit-0 results cached; bounded (64).

### Verification (expected vs actual) — the measurement trail

| Run | Wall | Result |
|---|---|---|
| 4 shards, no cache | 965 s | 1204/1204 ✓ but > 880 s gate |
| 6 shards, no cache | 1019 s | WORSE — contention, not shard count |
| 4 shards, CPU-pinned | 989 s | pinning isn't the lever |
| 4 shards + **classpath cache** | **160 s** | **1204/1204, 0 failed** ✓ |
| serial + cache (totals oracle) | 328 s | **1204/1204, identical totals** ✓ |

- Gate: wall ≤ 50% of the FROZEN Stage-0 baseline (1761 s) → **160 s = 9.1%** ✓;
  the ~8-min stretch target beaten at 2:40. Dev-loop bonus: SERIAL is now 328 s.
- Totals: 1204 = the C2-era 1197 + exactly the 7 tests added in Stages 3–5;
  serial ≡ sharded ✓; 0 skipped (all five @Disabled live).
- Contention flakes (C1 watch item): the 3 load-sensitive searches stayed GREEN
  in every sharded run (4-way and 6-way deliberate load) — no hardening
  required; the C1 event stands as a mixed-workload (builds + suite) artifact.

### C6 exit status

- **GREEN.**

## C7 — Coverage core: collector + identity + report model (2026-07-13)

### What shipped

- dist: `tools/org.jacoco.agent-0.8.15-runtime.jar` (mounted as `-javaagent` on
  the forked runner) + `bundles/org.jacoco.core|report` (exec parsing in the
  server, resolved against our shipped ASM 9.10.1); all enforcer-gated.
- `org.jawata.mcp.coverage`: `CoverageStore` (one dir per artifact under
  `jawata.coverage.dir` → `jawata.workspace.root/.jawata/coverage`;
  jacoco.exec + manifest.json; EXPLICIT delete), `CoverageManifest` (full
  provenance: git revision + dirty fingerprint, class/source roots, selection,
  framework, evidence kind, environment, versions, completion status, the
  standing MEASUREMENT-BOUNDARY declaration), `CoverageModel` (exec matched
  against CURRENT bytes; the 8 honest states; per-symbol index; (classRoot,
  fqn) keying = bundle identity), `CoverageService` (mount/finalize/query,
  4-model LRU).
- Front door (0 new tools): run_tests `coverage=true` (sync + async — a
  finalizer thread writes the manifest when an async run completes) + actions
  `coverage_report` / `coverage_uncovered` / `coverage_artifacts` /
  `coverage_delete`; uncovered findings carry LINKS bound to the exact symbol
  (get_call_hierarchy incoming / find_references implementations / the
  artifact's test selection) + a smallest-next-action hint.
- New `coverage-target` fixture: hand-computed truth table (fully-covered,
  fully-missed, 1-of-2 branches, an obtained-but-never-run lambda), plus
  probes for every honest state (marker interface, enum boilerplate, never-
  loaded class, child-JVM spawner, hanging test).

### Verification (expected vs actual) — CoverageCoreTest 6/6, first full run

- Hand-computed truth EXACT: alwaysCalled covered/0 missed; neverCalled
  missed/0 covered; branchy 1+1 branches with partly-covered line; lambda =
  real `lambda$` method on REAL source lines, honestly missed ✓.
- Per-symbol lookup answers `uncovered(Covered#neverCalled)` with lines +
  links + hint naming the symbol ✓.
- ALL EIGHT states reachable: covered/missed (truth), non-executable
  (CovMarker — classification fix found by the gate: code-free beats
  never-loaded in precedence), generated-or-excluded (CovEnum#values),
  not-instrumented (NeverLoaded), instrumentation-failure (empty exec),
  stale-bytes (modify+rebuild → REFUSED, staleNote), unknown-run-failed
  (timeout run → no claims) ✓.
- Child-JVM honesty: ChildWork provably RAN in the child (test asserts its
  output) yet the artifact declares the measurement boundary — never silently
  zero ✓.
- Same-FQN-two-roots = two separate facts keyed by root (bundle identity) ✓.
- Provenance fields all present (git revision, dirty fingerprint, evidence
  kind, environment, versions, selection, completion) ✓; explicit delete
  removes and the artifact becomes unknown ✓.
- Runner-family regression: 12/12 + 3/3 + 2/2 + 1/1 ✓.

### C7 exit status

- **GREEN.**

## C8 — Delta · baselines · thresholds · stability · CI import (2026-07-13)

### What shipped

- `GitDiff` (rename-aware `git diff -U0 -M` parser: worktree/staged/range →
  new-side changed lines); `coverage_delta` intersects it with the per-line
  model (covered/uncovered/UNSTABLE/non-executable classification per changed
  line; stale classes refused inline; totals + changed-line percent).
- `coverage_baseline_set` / `coverage_baseline_compare`: named baselines in
  the store; regression = newly-missed lines + newly-uncovered symbols,
  judged per symbol/line — explicitly blind to a rising global percentage.
- `coverage_threshold_set`: the policy is a stored, VERSIONED document
  (every change bumps the version); report + delta responses under an active
  policy carry value + policyVersion + waivers + verdict.
- `coverage_stability`: two same-selection artifacts → line-level XOR →
  unstable registry; unstable lines NEVER count as covered in delta/gate
  answers.
- `coverage_import`: external exec → first-class artifact with fresh
  provenance; acceptance gated by the class-id check at analysis (mismatch =
  stale-bytes refusal). Model cache made FRESHNESS-AWARE (class-root mtime
  fingerprint) so a rebuild can never mask a stale verdict.

### Verification (expected vs actual) — CoverageDeltaTest 5/5, first run

- Delta EXACT across worktree/staged/range: exactly the 2 new uncovered body
  lines; a 3-line comment insertion SHIFTS all covered code without creating
  gaps; a `git mv` pure rename (non-public class file, content unchanged)
  yields no false gap ✓.
- Baseline: branchy's dropped then-arm is NAMED as newly-missed while the
  global line percentage RISES (asserted numerically) ✓.
- Threshold: value 80 + policyVersion 1 + the waiver text visible in the
  report; re-set → version 2 visibly ✓.
- Stability: the flip-dependent line lands in the unstable registry; after
  touching that line and covering it in a fresh run, delta still reports it
  UNSTABLE, not covered ✓.
- Import: matching bytes → real totals, no refusals; after modify+rebuild the
  SAME import surfaces Covered as stale-REFUSED ✓.
- Regression: CoverageCoreTest 6/6, runner family green ✓.

### C8 exit status

- **GREEN.**

## C9 — Per-test attribution + impacted_tests (2026-07-13)

### What shipped

- DESIGN REFINEMENT over the plan's tcpserver sketch: the RUNNER dumps+resets
  the agent IN-PROCESS at every test boundary (org.jacoco.agent.rt.RT via
  reflection — the runner stays dependency-free; no TCP races, no parent
  coordination). One .exec segment per executed test + index.tsv; the union
  merges into jacoco.exec at finalize so an attribution artifact doubles as
  a normal coverage artifact.
- `attribution=true` on run_tests (implies coverage; agent output=none);
  `CoverageAttribution` (targeted single-class segment analysis; evidence-kind
  rank unit<integration<system<replay<manual).
- Actions: `coverage_tests_covering` (scans attribution artifacts, ranked
  focused-first, kinds labeled, honest "unavailable"), `coverage_of_test`
  (artifact-scoped; misaddressed queries list the artifact's known tests),
  `coverage_impacted_tests` (explicit symbols or derived from a git diff;
  ranked by kind then breadth).

### Verification (expected vs actual) — CoverageAttributionTest 2/2

- tests_covering EXACT both directions: alwaysCalled → exactly the two
  exercising tests; neverCalled → nobody ✓.
- unit + integration both covering a symbol → unit ranked FIRST, kinds
  labeled ✓.
- coverage_of_test names what the unit test exercised (alwaysCalled in,
  neverCalled out) ✓.
- impacted_tests(branchy) = both tests ranked; impacted_tests(ghost symbol)
  = empty set, no guess ✓.
- Cross-module: CrossModuleTest#magicComesFromModuleA attributed EXACTLY to
  AlphaLib#magic ✓.
- No attribution evidence → attributionAvailable=false with the never-guesses
  note ✓.
- Coverage-family regression: 6/6 + 5/5 + 12/12 ✓.

### C9 exit status

- **GREEN.**
