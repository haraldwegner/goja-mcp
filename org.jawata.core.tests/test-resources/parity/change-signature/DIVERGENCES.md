# change_method_signature parity — recorded divergences (Sprint 25, D1b)

`change_method_signature` migrated from a hand-rolled signature/call-site string
builder onto an **atomic** design: JDT's `ChangeSignatureProcessor` for clean
changes, with the retained hand-rolled apply-and-report editor as the REPORT
fallback for coupled changes (a return-type change a value-returning body can't
satisfy, or removing a parameter the body still uses). `retargetCallsTo` stays
hand-rolled (no JDT equivalent). The composed *always-green* signature migration
(extract → change → migrate → inline) is deferred to the architect seat (Stage 11)
as a `refactoring(action=plan)` recipe — not baked into this atomic tool (audit
decision, 2026-07-16).

Goldens archived pre-migration (`7ecba3a`); the `git diff` old→JDT is the parity
evidence.

## Summary

| Case | Divergence | Class |
|---|---|---|
| `rename-to-format` | **none** — byte-identical | — |
| `reorder-count-message` | **none** — byte-identical | — |
| `add-suffix-param` | JDT adds a `@param suffix TODO` Javadoc line | (a) JDT more thorough |

## rename / reorder — no divergence

Byte-identical old-vs-JDT: the declaration and both call sites are rewritten the
same way. True parity.

## add-suffix-param — class (a), JDT documents the new parameter

Both tools add `String suffix` to the declaration and thread the default value
(`"!"`) to the two call sites identically. JDT additionally inserts a
`@param suffix TODO` line into the method's Javadoc — a parameter the old tool left
undocumented. JDT wins (the signature and its doc stay in sync). Golden refreshed.

## Coupled changes (not a parity divergence — a design behaviour)

`remove-used-param`, `return-String→void`, and `incompatible-param-type` are
COUPLED: JDT refuses them (they would leave the body inconsistent), so they route
to the REPORT fallback, which applies the signature and returns every introduced
compiler error as the `coupledChange` worklist. This preserves the "change the
signature, then fix the body/callers by the compile failures" workflow —
`ChangeMethodSignatureToolTest` asserts the applied result, not a refusal. These
have no golden here (they touch different fixtures / assert on-disk state in the
regression suite), but they are the reason the tool keeps the hand-rolled editor.
