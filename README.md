# GOJA — surgical, risk-free Java refactoring for autonomous agents

[![GitHub release](https://img.shields.io/github/v/release/haraldwegner/goja-mcp)](https://github.com/haraldwegner/goja-mcp/releases)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

**GOJA lets an AI agent refactor and maintain Java *surgically* — compiler-accurate,
behaviour-preserving, and reversible in one call.** Every change runs through Eclipse JDT and the
Eclipse LTK refactoring engine (the same machinery behind the Eclipse IDE), so the agent edits
*real* code with IDE-grade guarantees instead of guessing from text — then checks it against the
compiler and can undo it instantly. That precision is what makes **fully autonomous** Java
maintenance safe: surgical improvements across an entire codebase, without the risk of a text-based
tool getting it subtly wrong.

**Why an autonomous agent can trust it**

- **Compiler-accurate** — every answer comes from Eclipse JDT's resolved model, not text matching.
  GOJA *knows* which `save()` you meant.
- **Behaviour-preserving** — refactorings pass the LTK pre/post-condition checks the IDE enforces,
  so the transformation provably preserves semantics.
- **Verified** — `compile_workspace` + `get_diagnostics` are a real compiler gate the agent runs
  after every edit.
- **Reversible** — every mutating tool returns an `undoChangeId`; one call rolls the change back.

**85 MCP tools** cover the whole loop — navigate, analyse, refactor-and-apply, modernise, generate,
manage dependencies (Maven + Gradle), compile the workspace, and detect + remove duplicate code.

```bash
# Linux — installs goja-studio, which fetches and runs the GOJA engine for you
curl -sSL https://raw.githubusercontent.com/haraldwegner/goja-studio/main/install.sh | bash
```

GOJA is driven by **[goja-studio](https://github.com/haraldwegner/goja-studio)** — the desktop
control plane that downloads the engine, manages workspaces, and wires GOJA into your agent's MCP
config. macOS / Windows / manager-free setup are in **[Installation](#installation)** below.

---

## Why Java, why now — the renaissance

For twenty years languages trended terse — Python, then Kotlin, Scala, Go — because the cost that
mattered was *human* authoring. The agent era moves that cost: the agent writes the ceremony
tirelessly, and GOJA reads the codebase through the compiler instead of your eyes. The verbosity
tax is now paid by something that never tires — and the comprehension dividend is still yours. In
the agent era **you are the auditor, not the author**, and auditing favours explicit over clever;
that's why explicit, statically-typed Java beats terse Kotlin/Scala (harder to audit) and dynamic
Python (no compiler oracle). The types aren't ceremony — **they're the substrate GOJA stands on**:
you can't compiler-accurately refactor, prove behaviour-preservation, or detect a smell on code you
can't statically type. **GOJA is what flips Java's verbosity from liability to asset** — letting an
agent *fully engineer* Java, not merely generate it.

---

## Precision in practice

When an agent reaches for `grep` or plain file-reading to understand Java, it cannot distinguish:

- a real method call from a same-named method on an unrelated class,
- a field read from a field write,
- an interface implementation from any other reference to the type,
- a cast from any other mention of that type.

The result is wrong refactorings, missed usages, and confident-but-incorrect reasoning. GOJA drives
the same compiler the Eclipse IDE does, so the agent gets type resolution across inheritance,
method overloading/overriding, generics, classpath + import resolution, cross-bundle (OSGi)
navigation, and LTK-backed refactorings that genuinely preserve behaviour.

| Task: find every call to `UserService.save()` | Result |
|---|---|
| `grep "save("` | 47 hits — including `orderService.save()`, `saveButton`, comments |
| GOJA `find_references` | exactly 12 — the real calls |

---

## Installation

### Prerequisites

- **Java 21+** on the `PATH` — the GOJA engine runs as a JVM process.

### Recommended — install goja-studio

[goja-studio](https://github.com/haraldwegner/goja-studio) is the desktop control plane. It
downloads the matching GOJA engine, manages named workspaces of Java projects, runs one resident
JVM per workspace, and writes the MCP entry into Cursor / Claude Desktop / Antigravity /
IntelliJ-style configs for you.

| Platform | Install |
|---|---|
| **Linux** (x86_64 / aarch64) | `curl -sSL https://raw.githubusercontent.com/haraldwegner/goja-studio/main/install.sh \| bash` — pulls the matching `.AppImage` and adds a desktop entry |
| **macOS** (Apple Silicon) | download the `.dmg` from [goja-studio releases](https://github.com/haraldwegner/goja-studio/releases/latest) (unsigned — right-click → Open once to clear Gatekeeper) |
| **Windows** (x64 / ARM64) | download the `.msi` or `-setup.exe` from [goja-studio releases](https://github.com/haraldwegner/goja-studio/releases/latest) (unsigned — one-time SmartScreen "More info → Run anyway") |

Add your Java projects in the goja-studio window (or point its autoscan at a parent folder), pick
your MCP client, and it deploys the config. Done.

### Manager-free — wire GOJA into an MCP client yourself

Prefer to run the engine directly? Download the `goja-<platform>` archive from the
[releases](https://github.com/haraldwegner/goja-mcp/releases/latest), unpack it, and launch:

```bash
./goja/goja -data /path/to/workspace-dir
```

GOJA speaks MCP over HTTP/SSE. On startup it prints a ready line with the endpoint and a
session token:

```
READY url=http://127.0.0.1:8800/mcp token=ab12cd34…
```

Point your MCP client at that URL (token in the `Authorization: Bearer` header). For a client
config block:

```jsonc
{
  "mcpServers": {
    "goja": {
      "url": "http://127.0.0.1:8800/mcp",
      "headers": { "Authorization": "Bearer ab12cd34…" }
    }
  }
}
```

Define the workspace's projects by writing `<data-dir>/workspace.json` (see
[How workspaces work](#how-workspaces-work)). goja-studio automates all of this — the manual path
is for custom setups and CI.

---

## What GOJA does

**Multi-project workspaces.** A *workspace* is a named group of Java projects loaded into one GOJA
process and exposed as a single MCP service. The agent sees the combined symbol set of every
project; navigation, find-references, and refactorings work across the whole group. Every analysis
tool also takes an optional `projectKey` to scope down to one project.

**Live, no-restart updates.** GOJA watches `<data-dir>/workspace.json`. Add or remove a project and
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

## The 85 tools

**Workspace & navigation**
- *Workspace* — `add_project`, `remove_project`, `list_projects`, `load_project`, `refresh_workspace`
- *Navigate* — `go_to_definition`, `find_references`, `find_method_references`,
  `find_implementations`, `get_call_hierarchy_incoming` / `_outgoing`, `get_super_method`,
  `get_type_hierarchy`, `get_type_members`, `get_document_symbols`
- *Search* — `search_symbols`, `find_pattern_usages` *(annotation / instantiation / type-argument /
  cast / instanceof)*, `find_field_writes`, `get_type_usage_summary`

**Understand**
- *Analyse* — `analyze_type`, `analyze_method`, `analyze_file`, `analyze_control_flow`,
  `analyze_data_flow`, `analyze_change_impact`, `analyze_naming`, `analyze_javadocs`,
  `analyze_nullness`, `find_quality_issue` *(naming / bugs / unused / large-classes / circular-deps /
  reflection / throws / catches)*
- *Inspect* — `get_symbol_info`, `get_hover_info`, `get_signature_help`, `get_javadoc`,
  `get_type_at_position`, `get_method_at_position`, `get_field_at_position`, `get_enclosing_element`,
  `get_complexity_metrics`, `get_project_structure`, `get_classpath_info`, `get_di_registrations`

**Change**
- *Refactor* — `rename_symbol`, `extract_method`, `extract_variable`, `extract_constant`,
  `extract_interface`, `inline_method`, `inline_variable`, `move_class`, `move_package`, `pull_up`,
  `push_down`, `encapsulate_field`, `change_method_signature`, `convert_anonymous_to_lambda`,
  `override_methods` · `apply_refactoring` · `inspect_refactoring` · `undo_refactoring`
- *Imports & modernise* — `organize_imports`, `optimize_imports_workspace`, `suggest_imports`,
  `find_modernization`, `apply_cleanup`
- *Null-safety* — `analyze_nullness`, `apply_null_annotations`
- *Generate* — `generate_constructor`, `generate_getters_setters`, `generate_equals_hashcode`,
  `generate_tostring`, `generate_test_skeleton`, `format`
- *Quick fixes* — `get_quick_fixes`, `apply_quick_fix`, `validate_syntax`
- *Duplicates* — `find_duplicate_code`, `replace_duplicates`

**Build & verify**
- *Dependencies* — `add_dependency`, `update_dependency`, `find_unused_dependencies`,
  `get_dependency_graph`
- *Verify* — `compile_workspace`, `get_diagnostics`, `find_tests`, `run_tests`
- *Health* — `health_check`

---

## How workspaces work

A workspace is described by a JSON file the engine watches:

```json
{ "version": 1, "name": "example", "projects": ["/abs/path/project-a", "/abs/path/project-b"] }
```

GOJA loads every listed project into one process and serves them as a single MCP endpoint.
`java.nio.file.WatchService` picks up edits to this file and reconciles the live project set within
about a second. goja-studio writes and maintains this file as you add and remove projects in the
UI; when running manager-free you write it yourself.

---

## Configuration

| Variable | Purpose |
|---|---|
| `GOJA_HTTP_PORT` | bind port for the MCP endpoint (default: auto in 8800–8999) |
| `GOJA_HTTP_TOKEN` | session token clients must present (default: generated, printed in `READY`) |
| `GOJA_DATA_DIR` | workspace data directory (location of `workspace.json`) |

goja-studio sets these per workspace automatically and allocates a stable `(port, token)` per
workspace, so multiple agents share one engine instead of spawning a JVM each.

---

## Building from source

```bash
git clone https://github.com/haraldwegner/goja-mcp.git
cd goja-mcp
./mvnw clean verify          # Tycho reactor build — produces the product under org.goja.product/target
```

Requires JDK 21. The build is an Eclipse Tycho / OSGi reactor; `org.goja.product` materialises the
runnable engine for each platform.

---

## Architecture

GOJA is an OSGi application built on Eclipse JDT and the Eclipse Language Toolkit (LTK):

- **`org.goja.core`** — workspace + project model, JDT integration, the analysis/refactoring engine.
- **`org.goja.mcp`** — the MCP server: tool registry, protocol handler, HTTP/SSE transport.
- **`org.goja.launcher`** / **`org.goja.product`** — Equinox launcher and the packaged product.

One process hosts a whole workspace; tools operate on the shared JDT model, which is what makes
cross-project, compiler-accurate answers cheap.

---

## Heritage & license

GOJA began as a fork of the MIT-licensed
**[javalens-mcp](https://github.com/pzalutski-pixel/javalens-mcp)** by Peter Zalutski. That MIT base
is retained and credited in [`NOTICE`](NOTICE). GOJA is distributed under the
**[GNU AGPL-3.0](LICENSE)** — running a GOJA server over a network makes it covered work, so network
users are entitled to its source. Contributions are accepted under the
[Contributor License Agreement](CLA.md); see [`CONTRIBUTING.md`](CONTRIBUTING.md).
