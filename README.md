<img src="docs/favicon.svg" width="52" align="left" alt="the jawata arch">

# JAWATA — compiler-accurate Java analysis & refactoring for AI agents

**Agents write. jawata verifies. Java wins.** · [jawata.org](https://jawata.org)

[![GitHub release](https://img.shields.io/github/v/release/haraldwegner/jawata-mcp)](https://github.com/haraldwegner/jawata-mcp/releases)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

**JAWATA lets an AI agent refactor and maintain Java *surgically* — compiler-accurate,
behaviour-preserving, and reversible in one call.** Every change runs through Eclipse JDT and the
Eclipse LTK refactoring engine (the same machinery behind the Eclipse IDE), so the agent edits
*real* code with IDE-grade guarantees instead of guessing from text — then checks it against the
compiler and can undo it instantly. That precision is what makes **fully autonomous** Java
maintenance safe: surgical improvements across an entire codebase, without the risk of a text-based
tool getting it subtly wrong.

**Why an autonomous agent can trust it**

- **Compiler-accurate** — every answer comes from Eclipse JDT's resolved model, not text matching.
  JAWATA *knows* which `save()` you meant.
- **Behaviour-preserving** — refactorings pass the LTK pre/post-condition checks the IDE enforces,
  so the transformation provably preserves semantics.
- **Verified** — `compile_workspace` + `get_diagnostics` are a real compiler gate the agent runs
  after every edit.
- **Reversible** — every mutating tool returns an `undoChangeId`; one call rolls the change back.

**43 MCP tools** cover the whole loop — navigate, analyse, refactor-and-apply, detect code smells
(Fowler / SOLID / Kerievsky), apply pattern-targeted refactorings, modernise, generate, manage
dependencies (Maven + Gradle), compile the workspace, and detect + remove duplicate code.

```bash
# Linux — installs jawata-studio, which fetches and runs the JAWATA engine for you
curl -sSL https://raw.githubusercontent.com/haraldwegner/jawata-studio/main/install.sh | bash
```

JAWATA is driven by **[jawata-studio](https://github.com/haraldwegner/jawata-studio)** — the desktop
control plane that downloads the engine, manages workspaces, and wires JAWATA into your agent's MCP
config. macOS / Windows / manager-free setup are in **[Installation](#installation)** below.

---

## Why Java, why now — the renaissance

For twenty years languages trended terse — Python, then Kotlin, Scala, Go — because the cost that
mattered was *human* authoring. The agent era moves that cost: the agent writes the ceremony
tirelessly, and JAWATA reads the codebase through the compiler instead of your eyes. The verbosity
tax is now paid by something that never tires — and the comprehension dividend is still yours. In
the agent era **you are the auditor, not the author**, and auditing favours explicit over clever;
that's why explicit, statically-typed Java beats terse Kotlin/Scala (harder to audit) and dynamic
Python (no compiler oracle). The types aren't ceremony — **they're the substrate JAWATA stands on**:
you can't compiler-accurately refactor, prove behaviour-preservation, or detect a smell on code you
can't statically type. **JAWATA is what flips Java's verbosity from liability to asset** — letting an
agent *fully engineer* Java, not merely generate it.

---

## Precision in practice

When an agent reaches for `grep` or plain file-reading to understand Java, it cannot distinguish:

- a real method call from a same-named method on an unrelated class,
- a field read from a field write,
- an interface implementation from any other reference to the type,
- a cast from any other mention of that type.

The result is wrong refactorings, missed usages, and confident-but-incorrect reasoning. JAWATA drives
the same compiler the Eclipse IDE does, so the agent gets type resolution across inheritance,
method overloading/overriding, generics, classpath + import resolution, cross-bundle (OSGi)
navigation, and LTK-backed refactorings that genuinely preserve behaviour.

There is a deeper reason this works: **an agent's prior is verifiability-seeking, not
shortcut-seeking.** It grinds toward whatever it can verify — which is a liability only while
design properties are expensive to check. Make them cheap and the prior flips sign: the agent
pursues `find_field_writes = 0` with the same stubbornness it pursues green tests. That is what
compiler-accurate tooling is *for* in agent hands — it turns structural facts (writers per field,
reference audits, SOLID and smell detection) into two-minute checks, so design erosion shows up
as a number under surveillance instead of an incident in production.

| Task: find every call to `UserService.save()` | Result |
|---|---|
| `grep "save("` | 47 hits — including `orderService.save()`, `saveButton`, comments |
| JAWATA `find_references` | exactly 12 — the real calls |

JAWATA ships this bias with the connection itself: the MCP `instructions` field injects a trigger→tool
guide at connect, so every client is told to reach for JAWATA first — not `grep`, not a hand-edit —
for anything semantic ([jawata-studio](https://github.com/haraldwegner/jawata-studio) adds a hard
try-first hook on top). And "understand this symbol" is one call: `analyze(kind=symbol)` returns the
definition, the type, and the references together, so there's no multi-step detour to defect from.

### Strict disk sync — every answer reflects current disk (v2.4.0)

Agents, `git checkout`, and other editors all change files outside JAWATA's JVM. A staleness guard
runs once per tool call, before the tool computes anything: a millisecond scan detects external
edits by evidence (mtime/size, content hash inside the timestamp-granularity window), then exactly
the changed files are reconciled and the affected project rebuilt — only when something changed.
Search, analysis, refactor change computation, and the knowledge store's pointer judgments all
answer about the tree as of the call. There is no off switch: the only skip is the earned one —
no edit detected, no work.

Measured on this repository (~500 source files, Linux, warm):

| Case | Cost |
|---|---|
| Unchanged tree (the common case) | ~10 ms scan; tool calls total 22–28 ms |
| 1 file changed externally | ~0.03 s on the next call (reconcile + rebuild) |
| 100 files changed (branch switch shape) | ~32 ms scan + one rebuild on the next call |
| Newly loaded project (once) | one whole-project reconcile, ~4 s |

### Memory that doesn't live in a chat product (v2.5.0)

Chat clients have memories — opaque ones: not listable, not exportable, not writable on
command, gone when you switch tools. JAWATA's knowledge store is the opposite by design:
**portable markdown files in your git plus a grounded H2 store anchored to your
symbols.** Since v2.5.0 the anchoring is automatic — a lesson whose text names its code
(`SlotManager.freeSlot`) is resolved through the compiler at ingest and anchored to the
one real type it denotes; dead names, library types and ambiguous names never anchor
(absence beats a wrong pointer). Recall is terminal and classification-steered: ask by
symbol, get the lesson that was written for it — with the mandate to *match evidence
against the closed set, not invent a cause*. Written from any MCP client, recalled from
any MCP client: a lesson recorded in Cursor today answers by symbol in Claude Code
tomorrow. Promote it, prune it, export it — it's yours.

**Honest client parity:** the tools and the guard are identical on every MCP client. The
memory *auto-push* (session primer, prompt-boundary recall, on-edit recall) is **full on
Claude Code** and **best-effort on Cursor** — Cursor's hook surface can't inject context
at every seam, so there the recalled facts reach the agent via the deployed rule block
and the steering that rides on every tool result. Same knowledge, different delivery —
we'd rather tell you exactly what each client gets than claim "identical everywhere."

> The agent's prior is verifiability-seeking, not shortcut-seeking — it is a liability
> only while design properties are expensive to verify. Make them cheap and it flips
> sign: the agent grinds toward `find_field_writes = 0` with the same stubbornness it
> grinds toward green tests.

---

## Installation

### Prerequisites

- **Java 21+** on the `PATH` — the JAWATA engine runs as a JVM process.

### Recommended — install jawata-studio

[jawata-studio](https://github.com/haraldwegner/jawata-studio) is the desktop control plane. It
downloads the matching JAWATA engine, manages named workspaces of Java projects, runs one resident
JVM per workspace, and writes the MCP entry into Cursor / Claude Desktop / Antigravity /
IntelliJ-style configs for you.

| Platform | Install |
|---|---|
| **Linux** (x86_64 / aarch64) | `curl -sSL https://raw.githubusercontent.com/haraldwegner/jawata-studio/main/install.sh \| bash` — pulls the matching `.AppImage` and adds a desktop entry |
| **macOS** (Apple Silicon) | download the `.dmg` from [jawata-studio releases](https://github.com/haraldwegner/jawata-studio/releases/latest) (unsigned — right-click → Open once to clear Gatekeeper) |
| **Windows** (x64 / ARM64) | download the `.msi` or `-setup.exe` from [jawata-studio releases](https://github.com/haraldwegner/jawata-studio/releases/latest) (unsigned — one-time SmartScreen "More info → Run anyway") |

Add your Java projects in the jawata-studio window (or point its autoscan at a parent folder), pick
your MCP client, and it deploys the config. Done.

### Manager-free — wire JAWATA into an MCP client yourself

Prefer to run the engine directly? Download the `jawata-<platform>` archive from the
[releases](https://github.com/haraldwegner/jawata-mcp/releases/latest), unpack it, and launch:

```bash
./jawata/jawata -data /path/to/workspace-dir
```

JAWATA speaks MCP over HTTP/SSE. On startup it prints a ready line with the endpoint and a
session token:

```
READY url=http://127.0.0.1:8800/mcp token=ab12cd34…
```

Point your MCP client at that URL (token in the `Authorization: Bearer` header). For a client
config block:

```jsonc
{
  "mcpServers": {
    "jawata": {
      "url": "http://127.0.0.1:8800/mcp",
      "headers": { "Authorization": "Bearer ab12cd34…" }
    }
  }
}
```

Define the workspace's projects by writing `<data-dir>/workspace.json` (see
[How workspaces work](#how-workspaces-work)). jawata-studio automates all of this — the manual path
is for custom setups and CI.

---

## What JAWATA does

**Multi-project workspaces.** A *workspace* is a named group of Java projects loaded into one JAWATA
process and exposed as a single MCP service. The agent sees the combined symbol set of every
project; navigation, find-references, and refactorings work across the whole group. Every analysis
tool also takes an optional `projectKey` to scope down to one project.

**Live, no-restart updates.** JAWATA watches `<data-dir>/workspace.json`. Add or remove a project and
the running process reconciles its project list in about a second — no process restart, no MCP
reload, no agent-session refresh.

**Every build layout, resolved correctly.** Maven, Maven-Tycho, Eclipse PDE, and Gradle (through
the Tooling API) — source roots and dependencies are resolved per layout, including OSGi
`Require-Bundle` so cross-bundle navigation just works between projects in the same workspace.

**Behaviour-preserving refactoring, applied.** Refactorings run through the Eclipse LTK engine, so
they pass the same pre/post-condition checks the IDE enforces. Every mutating tool **applies the
change by default** and returns the diff plus an `undoChangeId` — one call to reverse it. Rename,
extract, inline, move, pull-up/push-down, encapsulate-field, and change-signature all rewrite every
affected reference across the workspace.

**Verification built in.** `compile_workspace` + `get_diagnostics` give the agent a real compiler
gate to check its own edits before moving on — the post-edit loop a careful developer runs by hand.

---

## The tools

> Front doors consolidate many operations behind a `kind` parameter — e.g. `extract(kind)`,
> `inline(kind)`, `find_quality_issue(kind)`, `refactor_to_pattern(kind)` — so the loaded surface
> stays small (41) while the capability behind it keeps growing by registration.


**Workspace & navigation**
- *Workspace* — `load_project`, `project` *(list / add / remove)*, `refresh_workspace`
- *Navigate* — `go_to_definition`, `find_references` *(references / implementations /
  method_references)*, `get_call_hierarchy` *(incoming / outgoing)*, `inspect` *(type_hierarchy /
  document_symbols / type_members / …)*, `get_at_position` *(type / method / field / hover /
  javadoc / signature / enclosing / super / symbol)*
- *Search* — `search_symbols`, `find_pattern_usages` *(annotation / instantiation / type_argument /
  cast / instanceof)*, `find_field_writes`

**Understand**
- *Analyse* — `analyze` *(file / type / method / control_flow / data_flow / change_impact / naming /
  javadocs / nullness / symbol — one call: definition + type + references)*, `find_quality_issue` *(quality: naming / bugs / unused / large-classes /
  circular-deps / reflection / throws / catches · 18 Fowler smells · SOLID: dip / isp / srp_cohesion
  / lsp · Kerievsky: singleton / type_code — with a `family` filter: quality / fowler / solid /
  kerievsky)*
- *Inspect* — `inspect` *(complexity / project_structure / classpath / type_usage /
  dependency_graph / di_registrations)*

**Change**
- *Refactor* — `rename_symbol`, `extract` *(method / variable / constant / interface / superclass)*,
  `inline` *(method / variable)*, `move` *(class / package)*, `move_in_hierarchy` *(pull-up /
  push-down)*, `encapsulate_field`, `change_method_signature`, `convert_anonymous_to_lambda`,
  `generate` *(constructor / getters_setters / equals_hashcode / tostring / test_skeleton /
  override_methods / copy_class)* · `refactoring` *(single change: apply / undo / inspect ·
  multi-step, parity-gated: plan / apply_plan / inspect_plan / undo_plan)*
- *Multi-step orchestration* — `refactoring(action=plan → apply_plan)` walks a refactoring as
  atomic, behaviour-preserving steps with a **parity gate** (compile 0/0 + a purity check) after
  each; a broken build or a smuggled control-flow change rolls the whole plan back atomically.
  `copy_class` + `extract(kind=superclass)` are the compiler-writes-the-code primitives (copy a
  class, then lift the shared members into a parent) — reuse instead of re-authoring.
- *Refactor to patterns (Kerievsky)* — `refactor_to_pattern` *(inline_singleton / compose_method /
  replace_type_code_with_class / refactor_to_state / refactor_to_command_dispatcher /
  form_template_method / refactor_to_visitor / replace_pattern_with_idiom)* — behaviour-preserving,
  reversible, compiling; toward a pattern when complexity warrants and away from one that has
  outlived its use. OCP cure: `divergent_change` / `shotgun_surgery` point at a runnable
  `refactoring(action=plan, kind=refactor_to_state | refactor_to_command_dispatcher |
  form_template_method)`.
- *Imports & modernise* — `organize_imports`, `optimize_imports_workspace`, `find_modernization`
  *(8 idioms)*, `apply_cleanup` *(add_final / redundant_modifiers)*
- *Null-safety* — `apply_null_annotations` *(add / migrate)*
- *Format* — `format`
- *Quick fixes* — `quick_fix` *(suggest_imports / list / apply)*, `validate_syntax`
- *Duplicates* — `find_duplicate_code`, `replace_duplicates`

**Build & verify**
- *Dependencies (Maven)* — `dependency` *(add / update / find_unused)*
- *Verify* — `compile_workspace`, `get_diagnostics`, `find_tests`, `run_tests`
- *Health* — `health_check`

**Learn (knowledge store)**
- *Experience* — `experience` *(record / recall / primer / load / refresh / wipe / promote)* — the
  local, workspace-scoped knowledge store. Record a lesson / hazard / domain fact; **recall** it
  TERMINALLY for a cue (one fitting node, pointer resolved to current code, or an authoritative
  absence — never a similarity pile); prime the domain layer at session start; seed from memory
  files and keep pointers honest against the compiler.

*43 tools total; front doors (`analyze`, `inspect`, `extract`, `inline`, `move`, `generate`,
`find_quality_issue`, `find_pattern_usages`, `find_modernization`, `refactor_to_pattern`,
`dependency`, `quick_fix`, `refactoring`, `project`) each dispatch a `kind`/action, so the loaded
surface stays small while capability grows by registration.*

---

## How workspaces work

A workspace is described by a JSON file the engine watches:

```json
{ "version": 1, "name": "example", "projects": ["/abs/path/project-a", "/abs/path/project-b"] }
```

JAWATA loads every listed project into one process and serves them as a single MCP endpoint.
`java.nio.file.WatchService` picks up edits to this file and reconciles the live project set within
about a second. jawata-studio writes and maintains this file as you add and remove projects in the
UI; when running manager-free you write it yourself.

---

## Configuration

| Variable | Purpose |
|---|---|
| `JAWATA_HTTP_PORT` | bind port for the MCP endpoint (default: auto in 8800–8999) |
| `JAWATA_HTTP_TOKEN` | session token clients must present (default: generated, printed in `READY`) |
| `JAWATA_DATA_DIR` | workspace data directory (location of `workspace.json`) |

jawata-studio sets these per workspace automatically and allocates a stable `(port, token)` per
workspace, so multiple agents share one engine instead of spawning a JVM each.

---

## Building from source

```bash
git clone https://github.com/haraldwegner/jawata-mcp.git
cd jawata-mcp
mvn install                                                # plain Maven, ~10 s — dist under build/dist/target/dist
java -jar build/dist/target/dist/jawata.jar -data <ws>     # run it
java -jar build/dist/target/dist/jawata.jar -runTests      # full suite, in-framework
```

Requires JDK 21. Since Sprint 22d the build is **plain Maven** — Eclipse's JDT/platform bundles come
straight from Maven Central, and a small boot module starts the embedded OSGi framework
programmatically (the same pattern Eclipse's own Java language server uses). One universal dist runs
on every platform.

---

## Architecture

JAWATA is an OSGi application built on Eclipse JDT and the Eclipse Language Toolkit (LTK):

- **`org.jawata.core`** — workspace + project model, JDT integration, the analysis/refactoring engine.
- **`org.jawata.mcp`** — the MCP server: tool registry, protocol handler, HTTP/SSE transport.
- **`org.jawata.launcher`** / **`org.jawata.product`** — Equinox launcher and the packaged product.

One process hosts a whole workspace; tools operate on the shared JDT model, which is what makes
cross-project, compiler-accurate answers cheap.

---

## Heritage & license

JAWATA began as a fork of the MIT-licensed
**[javalens-mcp](https://github.com/pzalutski-pixel/javalens-mcp)** by Peter Zalutski. That MIT base
is retained and credited in [`NOTICE`](NOTICE). JAWATA is distributed under the
**[GNU AGPL-3.0](LICENSE)** — running a JAWATA server over a network makes it covered work, so network
users are entitled to its source. Contributions are accepted under the
[Contributor License Agreement](CLA.md); see [`CONTRIBUTING.md`](CONTRIBUTING.md).
