#!/usr/bin/env python3
# Sprint 27a Stage 4b (D10) — derive the admission-routing patterns from the
# OBSERVED misplaced content in the live corpus export, never from guesses.
#
# Usage:
#   python3 derive_admission.py /path/to/export.json            # counts + samples
#   python3 derive_admission.py /path/to/export.json cleaned.json  # also emit a
#       CLEANED export (misplaced symptom items moved into body.details under an
#       "[artifacts]" note; heading-shaped summaries reported, NOT rewritten —
#       a summary rewrite is authorship, not routing, and stays manual).
#
# The export is PRIVATE (Harald's corpus). This script is committed; its input
# and outputs are not. The dossier records the input's sha256 beside the counts
# so the derivation is re-runnable and pinned.
#
# Classification (derived 2026-07-23 from export sha 96b128af…, 2080 entries,
# 17077 symptom items — counts in dossier-27a):
#   MISPLACED (moved by the clean; refused-with-redirect at admission):
#     path      — filesystem/glob/URL-ish references            (observed 1195)
#     flag      — command-line switches                         (observed   49)
#     heading   — section headers: '#'-prefixed or ':'-suffixed (observed 1436)
#     code      — symbols, dotted identifiers, calls, backticks (observed ~5655)
#     id        — bare hashes / numeric ids                     (observed ~419)
#     tag       — hyphenated slug tags (memory-file names etc.) (observed ~449)
#   KEPT (legitimate symptom content):
#     prose     — multi-word natural-language observations      (observed ~6163)
#     word      — a single plain word ("deadlock"): ambiguous but harmless and
#                 load-bearing for keyword matching             (observed ~1614)
#
# The Java admission gate mirrors these rules; its fixtures are INVENTED
# lookalikes of each class — no corpus row is copied into the repo.

import json
import re
import sys
import hashlib
import collections

PATH_RE = re.compile(
    r'(^~|^\.{0,2}/|[\\/].*[\\/]|^\*\*/'
    r'|\.(md|java|rs|py|json|xml|yml|yaml|sh|log|jar|toml|txt|html|properties|parquet|class|db|gz)$)'
)
CODE_RE = re.compile(
    r'(`[^`]+`'                                 # backticked anything
    r'|\w+\([^)]*\)'                            # a call: name(...)
    r'|\b[a-z][a-zA-Z0-9]*[A-Z]\w*'             # camelCase
    r'|\b[A-Za-z_][\w$]*\.[A-Za-z_][\w$(]'      # dotted identifier
    r'|\b[A-Z][a-z]+[A-Z]\w*)'                  # PascalCase compound
)
HEX_ID_RE = re.compile(r'^[0-9a-f]{7,40}$')
NUMERIC_ID_RE = re.compile(r'^[\d.,%"\'\s–-]+$')


def classify(item: str) -> str:
    t = item.strip()
    if not t:
        return 'word'
    if PATH_RE.search(t):
        return 'path'
    if t.startswith('-'):
        return 'flag'
    if t.startswith('#') or t.endswith(':'):
        return 'heading'
    if CODE_RE.search(t):
        return 'code'
    if HEX_ID_RE.match(t) or NUMERIC_ID_RE.match(t):
        return 'id'
    if ' ' not in t:
        return 'tag' if ('-' in t or '_' in t) else 'word'
    return 'prose'


MISPLACED = {'path', 'flag', 'heading', 'code', 'id', 'tag'}
HEADING_SUMMARY_RE = re.compile(r'(^#)|(^.{0,60}:\s*$)')


def main():
    export_path = sys.argv[1]
    out_path = sys.argv[2] if len(sys.argv) > 2 else None
    raw = open(export_path, 'rb').read()
    print(f"input sha256: {hashlib.sha256(raw).hexdigest()}")
    root = json.loads(raw)
    entries = root['data']['entries']

    counts = collections.Counter()
    rows_touched = 0
    heading_summaries = 0
    for e in entries:
        body = e.get('body') or {}
        symptoms = [str(s) for s in (body.get('symptoms') or [])]
        moved = [s for s in symptoms if classify(s) in MISPLACED]
        kept = [s for s in symptoms if classify(s) not in MISPLACED]
        for s in symptoms:
            counts[classify(s)] += 1
        if moved:
            rows_touched += 1
            if out_path:
                body['symptoms'] = kept
                note = "[artifacts] moved from symptoms by admission clean: " \
                    + "; ".join(moved)
                body['details'] = ((body.get('details') or '') + "\n" + note).strip()
                e['body'] = body
        summary = (e.get('summary') or '').strip()
        if summary and HEADING_SUMMARY_RE.match(summary):
            heading_summaries += 1

    total = sum(counts.values())
    moved_total = sum(counts[c] for c in MISPLACED)
    print(f"entries: {len(entries)}  symptom items: {total}")
    for cls, n in counts.most_common():
        marker = 'MOVED' if cls in MISPLACED else 'kept '
        print(f"  {marker} {cls:8s} {n:6d}")
    print(f"moved total: {moved_total} ({100 * moved_total / max(1, total):.1f}%)"
          f"  rows touched: {rows_touched}/{len(entries)}")
    print(f"heading-shaped summaries (reported, not rewritten): {heading_summaries}")

    if out_path:
        with open(out_path, 'w') as f:
            json.dump(root, f, indent=1)
        print(f"cleaned export -> {out_path} "
              f"(sha256 {hashlib.sha256(open(out_path, 'rb').read()).hexdigest()})")
        # Idempotence proof: classify the cleaned output again.
        re_moved = sum(
            1 for e in entries
            for s in ((e.get('body') or {}).get('symptoms') or [])
            if classify(str(s)) in MISPLACED)
        print(f"idempotence: second pass would move {re_moved} items")


if __name__ == '__main__':
    main()
