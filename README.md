# GOJA — compiler-accurate Java intelligence for AI agents

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

**An Eclipse-JDT–backed MCP server that gives AI agents IDE-grade Java analysis, navigation,
and behaviour-preserving refactoring** on real-world workspaces — multi-project workspaces,
auto-applying refactorings with one-call undo, code generation, dependency management
(Maven + Gradle), workspace-wide compile verification, null-safety + naming + Javadoc
analysis, and duplicate-code detection. The depth a human gets in Eclipse or IntelliJ,
packaged for agents over the Model Context Protocol.

> GOJA pairs with **[goja-studio](https://github.com/haraldwegner/goja-studio)**, the desktop
> control plane that manages workspaces and deploys the MCP server into Cursor / Claude /
> Antigravity / IntelliJ-style configs.

---

## Why Java, why now — the renaissance

For twenty years languages trended terse — Python, then Kotlin, Scala, Go — because the cost
that mattered was *human* authoring: boilerplate is friction a person pays. Java's verbosity
was its cardinal sin. **The agent era moves that cost.** The agent writes the ceremony
tirelessly; GOJA reads the codebase through the compiler instead of your eyes. The verbosity
tax is now paid by something that never tires — and the comprehension dividend is still yours.

What's left of "verbose" is *signal*: explicit types, explicit contracts, names that mean what
they say. That signal is legible twice over — to GOJA's compiler-accurate tools, and to you
when you audit. In the agent era **you are no longer the author; you are the auditor** — and
auditing favours explicit over clever. That's why explicit Java beats terse-cryptic
Kotlin/Scala for the job the human actually does now.

And the types aren't ceremony — **they are the substrate GOJA stands on.** You cannot
compiler-accurately resolve a call graph, prove a refactoring is behaviour-preserving, or
detect a smell with certainty on code you cannot statically type. That is why GOJA lets an
agent **fully engineer** Java — refactor, remove smells, enforce SOLID, manage null contracts
— and not merely generate it. On a dynamically-typed language the agent loses the compiler
oracle and is back to guessing.

**GOJA is what flips Java's verbosity from liability to asset.** For engineered backend systems
— where correctness, refactorability, and explicit contracts matter more than notebook brevity
— that is the most agent-ready combination in software.

---

## Install

The recommended path is the **[goja-studio](https://github.com/haraldwegner/goja-studio)**
desktop app: it manages named workspaces, downloads the GOJA runtime, and writes the MCP
server entry into your agent's config. See that repo for one-line installers.

To wire the MCP server directly, point your client at the `goja.jar` runtime with a workspace
data directory (`-data <path>`); goja-studio automates this.

## Configuration

| Env var | Purpose | Default |
|---|---|---|
| `GOJA_TIMEOUT_SECONDS` | Per-operation timeout. | `30` |
| `GOJA_LOG_LEVEL` | `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR`. | `INFO` |
| `GOJA_ABSOLUTE_PATHS` | Return absolute paths instead of workspace-relative. | `false` |

---

## Heritage

GOJA began as a fork of the MIT-licensed **javalens-mcp** by Peter Zalutski, and has since been
substantially extended (multi-project workspaces, auto-applying refactorings with undo,
modernisation, null-safety, naming/Javadoc analysis, and more). The retained MIT base is
credited in [`NOTICE`](NOTICE); the combined work is distributed under AGPL-3.0.

## Licence

**AGPL-3.0** (see [`LICENSE`](LICENSE)). Contributions are accepted under the
[Contributor License Agreement](CLA.md) — see [`CONTRIBUTING.md`](CONTRIBUTING.md). For
commercial licensing enquiries, contact the maintainer.
