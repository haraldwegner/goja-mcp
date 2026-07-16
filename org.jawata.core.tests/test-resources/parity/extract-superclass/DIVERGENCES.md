# extract_superclass — parity record (Sprint 25, spec D1a item 7)

Golden `greeter-identical.golden` archived from the pre-migration implementation
at 87f4e87 and gated by `ExtractSuperclassParityTest` (staged mode, Greet
fixtures).

## Conservative mode: NO divergence (verbatim preservation)

The Sprint-18 implementation (byte-identical + self-contained methods only,
string-built parent, composite undo) was preserved UNCHANGED behind
**`mode=identical`** — the addressing chosen at implementation per the decision
record (sprint-24-final-tool-overview.md #19: JDT as the default, ours renamed,
no deferral). The parity battery reproduces the golden byte-for-byte; the mode
parameter routes to the preserved path (the pre-migration tool ignored the
parameter, so the recorded invocation is identical on both sides).

## Default-addressing change (decided, not a parity divergence)

`extract(kind=superclass)` WITHOUT `mode` previously ran the conservative
contract; it now runs JDT's `ExtractSupertypeProcessor`
(org.eclipse.jdt.core.manipulation, jar-verified — the tool's old claim that
the engine "lives in jdt.ui" was false and died in this rewrite). The JDT
default is gated by its own capability tests, not by the conservative golden:

- `jdtDefault_extractsAndDedupsSiblings_undoRestores` — reparents caret +
  siblings, pulls the shared method, DELETES the sibling's matching duplicate
  (`getMatchingElements` → `setDeletedMethods`, the wizard's checked-by-default
  list), compiles, one undo restores everything.
- `jdtDefault_pullsUpFieldAndNonSharedMember` — a FIELD and a member the
  sibling lacks (Shape fixtures), the general case the conservative mode
  cannot do; `identicalMode_refusesFieldAndNonSharedMember` proves the
  conservative refusal without touching disk.

## Headless note

The engine requires `MembersOrderPreferenceCacheCommon().install()` (member
insertion reads the ordering cache); added to `HeadlessJdtConfig` — in the IDE,
jdt.ui installs it on activation.
