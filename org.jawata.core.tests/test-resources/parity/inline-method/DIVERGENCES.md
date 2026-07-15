# inline_method parity — recorded divergences (Sprint 25, D1b)

`inline_method` migrated from a hand-rolled regex parameter-substitution inliner
onto JDT's `InlineMethodRefactoring` (the IDE's Refactor → Inline engine), driven
through the `RefactoringEngine` seam.

Goldens were archived from the OLD tool before the migration (commit `7ecba3a`);
the `git diff` old→JDT is the parity evidence. Both cases differ in exactly one
way, class (a) — JDT wins.

## Summary

| Case | Divergence | Class |
|---|---|---|
| `inline-doublevalue` | `(x * 2)` → `x * 2` | (a) JDT cleaner |
| `inline-formatmessage` | `("Items" + ": " + 5)` → `"Items" + ": " + 5` | (a) JDT cleaner |

## Both cases — redundant parentheses removed (JDT wins)

The old tool wrapped every inlined expression in parentheses unconditionally. JDT
adds parentheses only where operator precedence requires them; in an assignment
right-hand side (`String result = <expr>;`) none are needed, so JDT omits them.
The applied result compiles identically either way; JDT's is the cleaner form.
Regression (`InlineMethodToolTest`) stays green after the migration — the tool
still reports `methodName` / `methodClass`, performs the inline on disk, and the
undo round-trips. (The old tool's synthesized `inlinedCode` field is gone: JDT
performs the real inline rather than building the replacement text as a string,
so the test now verifies the on-disk call-site rewrite instead.)
