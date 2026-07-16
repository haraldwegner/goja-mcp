# extract_interface — parity divergences (old string-builder → JDT ExtractInterfaceProcessor)

Sprint 25, spec D1a item 6. Golden `iextracttarget-default.golden` archived from the
OLD implementation at 51e277f; refreshed to the JDT output after the divergences
below were classified. Fixture: `InterfaceExtractTarget` (default selection, staged
mode). Classes per the plan: (1) JDT resolved more → JDT wins, record; (2) formatting
drift → normalize; (3) new refusal → record.

## D1 — use-where-possible retypes other declarations (class 1, JDT wins)

`setReplace(true)` runs the supertype-constraints solver across the workspace and
retypes every declaration that is provably satisfied by the new interface:

- `InterfaceUseSite.describe()`'s local: `InterfaceExtractTarget target` →
  `IExtractTarget target` (the local only calls `getName()`).
- The static factory's RETURN TYPE inside the target class itself:
  `public static InterfaceExtractTarget create(String)` →
  `public static IExtractTarget create(String)` (all callers use only
  interface members on the result).

The old implementation was single-file by construction — it string-built the
interface CU and inserted the implements clause via a brace scan; no other
declaration could ever change. (The use-site fixture `InterfaceUseSite.java` was
added with the migration; the old tool's golden diff is unaffected by its
presence.) The retype capability is asserted by
`ExtractInterfaceToolTest#useWherePossible_retypesCompatibleLocal`.

## D2 — default selection excludes supertype overrides (class 1 + a contract fix)

Old default: every public non-static method, including `toString()` (Object) and
`compareTo(InterfaceExtractTarget)` (Comparable) — copied verbatim into the
interface as redundant re-declarations.

New default: methods that OVERRIDE a supertype's are excluded
(`MethodOverrideTester.findOverriddenMethod`; `IType.findMethods` is insufficient —
it compares parameter simple names and misses generic-supertype overrides like
`Comparable<Foo>#compareTo(T)`). This is forced by engine semantics, proven live by
the compile gate during migration: the processor rewrites parameter types INSIDE
the generated interface, so an extracted override becomes
`int compareTo(IExtractTarget)` — an unimplemented abstract method the class does
not satisfy → REFACTORING_BROKE_COMPILE. Extracting an override is the supertype's
contract, not the class's own; an explicit `methodNames` selection still forces it
(and then answers to the same compile gate).

Consequence in this golden: the implements-clause hunk is unchanged, but
`extractedMethods` drops `toString`/`compareTo` (8 → 6).

## D3 — interface file content not diffed in staged mode (recorded, not a divergence)

Both implementations create the interface CU at apply time; the staged parity diff
covers edits to EXISTING files only, so the generated interface content (old:
wholesale import copy; new: proper import rewrite + `setComments(true)` javadoc
carry-over) is outside the golden. The applied-mode content is asserted by
`ExtractInterfaceToolTest#extractsInterface_appliesAndUndoRestores`.

## No new refusals (class 3): none observed on the fixture set.
