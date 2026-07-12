# Sprint 22d — De-Tycho spike: evidence dossier

Executed 2026-07-12 on branch `spike/22d-de-tycho` (from main @ 093e064 = v2.8.1).
Spec: `jawata-enterprise/docs/sprints/jawata-mcp/sprint-22d-de-tycho-spike.md`.
The decision this dossier feeds (spec D5) is Harald's.

## C0 — Tycho baselines (main @ 093e064)

| Baseline | Value | Provenance |
|---|---|---|
| Full `mvn clean verify` | **28:46 min**, 1179/0/0 (5 skip) | local, 2026-07-11 |
| Clean build, no tests | **48 s** (warm caches) | measured 2026-07-12 |
| Dist | archive **51.6 MB** (release asset 53.4 MB), tree **60 MB** | product build / gh |
| tools/list | **43** | live resident snapshot |
| Sentinel | 4/4 | surefire + CI |

## C1 — Dependency map + gap decisions (spec: m2e carry-vs-replace)

**25 of 26 target bundles are on Maven Central at the 2026-06 versions** (verified via
repo1 metadata; Central publishes each train in its release week). Adjudications:

| Gap / decision | Verdict | Why |
|---|---|---|
| **m2e trio** (core, jdt, maven.runtime) | **DROPPED — not carried, not replaced** | zero imports, zero MANIFEST requirements anywhere in jawata; pinned since the original JavaLens commit, never used (our importer does its own pom parsing). ALSO removable from the Tycho target (separate main-branch decision). |
| gson 2.14.0 | **DROPPED** | zero imports (jackson is the JSON layer) |
| jdt.apt.core | **DROPPED** | zero imports |
| osgi.services / osgi.util facades | **DROPPED** | pure re-export facades dragging the whole org.osgi.service.* tree; nothing requires them |
| jdt.junit5.runtime | **DROPPED (parity)** | the Tycho product itself does not contain it |
| **compiler.batch** | **CARRY: the #5188 patched jar is the ONLY compiler.batch in the dist** | Central has no bundle-form compiler.batch (only plain `ecj`); precedence mechanism = presence (official excluded entirely) |
| **org.commonmark (+ ext-gfm-tables)** | **CARRY (p2-wrapped)** | Central commonmark is not an OSGi bundle; jdt.core requires it (markdown javadoc). Durable mechanism for adoption: commit like patched-bundles or bnd-wrap |
| **org.junit 4.13.2 + org.hamcrest + jsvg** | **CARRY (p2-wrapped)** | jdt.junit.runtime hard-requires org.junit; JUnit 4 has no OSGi metadata on Central |
| jdt.junit trio availability | surprise: **junit.core/runtime ARE on Central** | the spec's "likely p2-only" fear was wrong |

**The hidden tree p2 always resolved silently** (found across 10 boot iterations, now
explicit): per-spec OSGi API bundles (service.component, service.prefs, util.promise,
util.function) · jna + jna-platform · icu4j · core.commands · **aries spifly + 5 ASM
bundles** (slf4j-api mandatorily requires the ServiceLoader extender) · jdt.debug ·
core.variables · core.filebuffers · **swt + swt.gtk.linux.x86_64 + swt.svg**
(jface.text hard-requires jface which requires SWT — the reason the product weighs
what it weighs) · search.core · simpleconfigurator (+ manipulator, frameworkadmin ×2,
required by jdt.junit.core) · jface. Final dist: **60 bundles**.

## C2 — The boot glue (spec D1 + D7)

- **Plain Maven compiled ALL of jawata's source verbatim on the first attempt — zero
  compile errors, zero source changes** (spike poms point at the existing src/ trees
  and reuse the checked-in MANIFESTs + plugin.xml).
- `org.jawata.boot` (~100 lines): EclipseStarter boot over a `bundles/` dir —
  builds the config.ini-equivalent bundle list, sets start levels (scr@1,
  common/app/registry/prefs@2:start, core.runtime@start), instance location from
  `-data`, `eclipse.application=org.jawata.mcp.application`, publishes
  jawata.workspace.root like the launcher. Session isolation (UUID subdir) NOT
  replicated — trivial to port, deliberate spike omission.
- **READY on iteration 10**; the 9 failed iterations were all missing-bundle
  resolution, zero code problems — Equinox's unresolved-constraint log names each.
- **D7 spike-half: the #5188 patch is live** — the dist's only compiler.batch is
  `-jawata5188`, and the end-to-end probe (`type_argument` on `java.lang.String`)
  answers (NPEs on stock 2026-06). Main-half re-proven at CA (sentinel 4/4 after the
  Stage-A edits).
- **The patch rule never fired: ZERO platform bugs encountered.**

## C3 — Parity (spec D2)

| Gate | Result |
|---|---|
| tools/list | **43, names diff-empty** vs the Tycho snapshot |
| compose_method plan→apply→undo | applied+undone, **tree byte-identical, BOTH builds** |
| Search probe set (search_symbols H* · find_references HelloWorld#getGreeting · find_pattern_usages type_argument java.lang.String · call hierarchy incoming) | **identical result sets both builds** (offsets/lengths/order byte-equal; only the workspace-handle path prefix differs, normalized) |

## C4 — Test runtime (spec D3)

Full suite (both test trees, verbatim sources) under plain surefire, flat classpath,
no platform booted: **1180 run = 394 pass · 777 error · 9 fail · 5 skip.**

- **All 777 errors share ONE cause:** `Workspace not available` — the workspace-bound
  tests need a running platform; a flat classpath has none. Zero other error kinds.
- Of the 9 failures: 8 are the same no-platform family asserting instead of erroring;
  1 (`patchedBundleActive`) is a harness-shape artifact — the flat test classpath
  resolves the official ~/.m2 jdt.core, not the patched dist jar (adoption fix:
  substitute the patched GAV on the test classpath).
- **Explicit probe #1 — IScanner: YES, the failure class is GONE.** A new probe test
  resolves `org.eclipse.jdt.core.compiler.IScanner`, obtains a scanner from
  ToolFactory, and lexes — passes. (The documented reason FindDuplicateCodeTool's
  tokenizer was hand-rolled no longer exists in the plain runtime.)
- **Explicit probe #2 — run_tests: parity, precondition unreproducible.** Both builds
  fail identically at the importer-classpath stage (the Sprint-23 gap) BEFORE the
  historic OSGi-NPE point; neither build can reach it on the fixture. The repair
  remains Sprint 23's, as planned.
- **The migration path for the 777 is mechanical and already built:** boot Equinox
  once per test JVM via the boot module (a JUnit extension) — follow-up-sprint work,
  not a spike blocker.

## C5 — Economics (spec D4)

| Measure | Tycho | Spike | Factor |
|---|---|---|---|
| Clean build, no tests | 48 s (warm) | **9 s** | ~5× |
| Full verify incl. tests | 28:46 | n/a until test migration (394-test share runs in ~1 min; the fork hit its 20-min cap on the unmigrated workspace share) | — |
| Dist archive | 51.6 MB | **50.9 MB** | ≈parity |
| Dist tree | 60 MB (plugins + p2 metadata + launcher) | **53 MB** (60 flat bundle jars + boot) | −12% |
| Train re-base | 22c-style target event | version-property bump | qualitative |

## Boot-glue ownership list (what we own if adopted)

`org.jawata.boot` (~100 lines today): bundle-list assembly + start levels · instance
location · app launch delegation · workspace-root publication. To productize: session
isolation port, config-area placement, dist assembly (tar.gz + per-OS SWT fragment
selection), test-JVM boot extension, patched-artifact GAV substitution for tests,
CI pipeline swap. Plus the standing carry maintenance: 5 p2-wrapped jars re-checked
per train (commonmark, org.junit, hamcrest, jsvg + the #5188 patch until 4.41).

## THE SWITCH (Harald's verdict 2026-07-12: "if it works we do this immediately")

Completed same-day on the branch — the head IS the plain build now:

- **Tests without Tycho, full parity:** both test trees compile verbatim as OSGi
  FRAGMENTS of their host bundles; a ~60-line in-framework JUnit launcher
  (`SpikeTestMain`) + the boot module's `-runTests` mode replace tycho-surefire.
  **First full run: 1179 = 1174 passed + 5 skipped, 0 failed — exact baseline parity**
  (the 20-min wall-clock is the tests' own JDT work, not build overhead; parallel
  local runs recorded as a Sprint-23 requirement).
- **Drop-in dist:** `build/dist/target/dist/jawata.jar` boots exactly like the old
  product jar (`java -jar jawata.jar -data <ws>`), session isolation included. ONE
  universal dist (all five platform SWT fragments ship; Equinox resolves the match) —
  replaces Tycho's five per-OS builds; release CI keeps the five asset names for
  consumer compatibility (identical content). Known layout change: the macOS asset is
  no longer a `Jawata.app` — same jawata-<tag>/jawata.jar layout as Linux.
- **Tycho retired:** root `pom.xml` = plain aggregator → `build/`; removed: all Tycho
  module poms, `org.jawata.product`, the `.target` file, `.mvn/maven.config`
  (p2 mirror machinery — obsolete without p2), `build.properties` files,
  `org.jawata.launcher` (superseded by boot). KEPT: `org.jawata.target/patched-bundles`
  (the #5188 jar, now consumed by the dist assembly), the checked-in MANIFESTs
  (the bundle identities), `org.jawata.jdtpatch` sources (the patch factory —
  no longer in the reactor; its output is the committed jar, rebuilt one-off if ever
  needed).
- **CI swapped:** ci.yml + release.yml = plain Maven build + in-framework suite;
  p2 preflight/mirror steps deleted.
- **Switched-head verification:** root `mvn install` **10 s** → drop-in boots →
  **43 tools** answer.

## Surprises

1. m2e, gson, jdt.apt.core: bundled dead weight, never imported (target hygiene
   finding for main, independent of the verdict).
2. jface.text → jface → SWT: the full SWT stack rides along for text presentation
   infrastructure — in BOTH worlds (it's in the product too).
3. jdt.junit.core/runtime ARE on Central; the product doesn't ship junit5.runtime.
4. slf4j's ServiceLoader wiring needs spifly+ASM — present in the product too (p2
   pulled it silently); with it, the spike build may actually BIND slf4j-simple
   (untested) where the product notoriously logs nothing.
5. Zero platform bugs; zero source changes; the patch rule never needed.
