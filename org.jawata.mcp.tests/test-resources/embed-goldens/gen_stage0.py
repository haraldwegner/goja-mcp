#!/usr/bin/env python3
"""Sprint 27 Stage 0 — the REFERENCE generator behind every C0 number.

Committed so the goldens and the derived constants are reproducible (C0 audit
finding F-C: measurements that cannot be re-run are prose, not evidence).

Runs sentence-transformers all-MiniLM-L6-v2 (apache-2.0) to emit:
  1. golden-tokenizations.json  — the C1 parity fixture
  2. golden-vectors.json        — the C2 parity fixture (fp32, normalized, mean-pooled)
  3. stage0-distributions.json  — the three score distributions + the LABELED
     pair lists the derived floor/dedup thresholds come from
  4. checkpoint-manifest.json   — bundle sizes (fp32 measured, fp16 MEASURED by
     real conversion) + sha256 + license

Dev-time only. The PRODUCT never fetches; it runs our own Java embedder against
these same goldens.

Usage:
  python3 -m venv /tmp/jawata-embed-venv && . /tmp/jawata-embed-venv/bin/activate
  pip install sentence-transformers
  python gen_stage0.py <path-to-experience-export.json> <output-dir>
"""
import json, os, sys, hashlib, statistics, time
import numpy as np
from sentence_transformers import SentenceTransformer

EXPORT = sys.argv[1] if len(sys.argv) > 1 else "export.json"
OUT = sys.argv[2] if len(sys.argv) > 2 else "."
MODEL = "sentence-transformers/all-MiniLM-L6-v2"
MAXLEN = 256

# ---------------------------------------------------------------- corpus
ents = json.loads(open(EXPORT).read())["data"]["entries"]

def entry_text(e):
    """What production embeds: summary + details (C0-F3 — a summary-only index
    scores true paraphrase pairs near noise)."""
    b = e.get("body") or {}
    parts = [e.get("summary") or ""]
    if b.get("details"):
        parts.append(str(b["details"]))
    return " ".join(parts).strip()

rows = [(e["id"][:8], entry_text(e), (e.get("summary") or "")[:150])
        for e in ents if entry_text(e)]
ids = [r[0] for r in rows]
texts = [r[1] for r in rows]
summ = {r[0]: r[2] for r in rows}
print(f"corpus: {len(rows)} entries", flush=True)

m = SentenceTransformer(MODEL)

# ---------------------------------------------------------------- 1+2 goldens
GOLDEN_CASES = [
    # domain prose (non-Java business knowledge)
    "Never act on a cancel until the broker confirms via the callback.",
    "The drift watchdog inverts short positions and false-alarms on half the book.",
    "Opening range breakout uses the first fifteen minutes of the session.",
    # code symptoms
    "the webview content area stays blank and no error is printed to stderr",
    "database file already locked at startup",
    "NullPointerException in ProblemReporter.previewAPIUsed during a search",
    # FQNs / short targets (the choke's retrieve(target) shape)
    "org.jawata.mcp.learn.PrecedentRetriever",
    "com.jats2.gateways.alpol.alpaca.orders.OrderProcessor#getAllPositions",
    "AlgoManager",
    "Widget.java",
    "rename_symbol",
    # mixed / operational
    "compile_workspace reports zero errors after the refactoring",
    "extract was reverted twice in a case like this",
    # unicode / edge
    "Grüße aus München — Umlaute und ß",
    "日本語のテキストも扱えるはずです",
    "emoji ⚠ 🏛 ✅ mixed with text",
    "   leading and trailing whitespace   ",
    "",
    "a",
    "x" * 2000,                      # long single token -> [UNK] (NOT truncation)
    # C0-F8: a case that genuinely EXCEEDS max_length=256 real tokens, so the
    # truncation path is actually exercised by the parity gate.
    ("the broker confirmation arrived after the slot had already been reused "
     "which routed the stale callback to the wrong algorithm and orphaned the "
     "position without a stop loss or a take profit order in place ") * 12,
]

tok = m.tokenizer
golden_tokens = []
for c in GOLDEN_CASES:
    enc = tok(c, padding=False, truncation=True, max_length=MAXLEN)
    golden_tokens.append({"text": c, "n_tokens": len(enc["input_ids"]),
                          "truncated": len(enc["input_ids"]) >= MAXLEN,
                          "input_ids": enc["input_ids"],
                          "tokens": tok.convert_ids_to_tokens(enc["input_ids"])})
n_trunc = sum(1 for g in golden_tokens if g["truncated"])
print(f"goldens: {len(GOLDEN_CASES)} cases, {n_trunc} exercise truncation", flush=True)

gv = m.encode(GOLDEN_CASES, normalize_embeddings=True, show_progress_bar=False)
meta = {"model": MODEL, "dim": int(gv.shape[1]), "normalized": True,
        "pooling": "mean", "max_length": MAXLEN}
json.dump({**meta, "cases": golden_tokens},
          open(f"{OUT}/golden-tokenizations.json", "w"), indent=1, ensure_ascii=False)
json.dump({**meta, "cases": [{"text": c, "vector": [round(float(x), 8) for x in v]}
                             for c, v in zip(GOLDEN_CASES, gv)]},
          open(f"{OUT}/golden-vectors.json", "w"), indent=1, ensure_ascii=False)

# ---------------------------------------------------------------- corpus embed
t0 = time.time()
E = m.encode(texts, normalize_embeddings=True, batch_size=64, show_progress_bar=False)
bulk = time.time() - t0
singles = []
for s in texts[:40]:
    t = time.time(); m.encode([s], normalize_embeddings=True, show_progress_bar=False)
    singles.append((time.time() - t) * 1000)
singles.sort()

# ---------------------------------------------------------------- 3a calibration
# keyword_baseline: MEASURED live through the production recall path
# (experience(kind=recall, ...)) — hit = returned the designated entry,
# wrong = returned entries but not the designated one, miss = honest nothing.
CALIB = [
    ("the position monitor alarms about a mismatch whenever we bet against a stock",       "d20ce2c6", "domain-prose",  "symptom", "miss"),
    ("removing the record from the lookup table too early meant a late confirmation reached the wrong owner", "8932f5b6", "domain-prose", "symptom", "miss"),
    ("a lost websocket acknowledgement leaves an order that can never be called off",      "5d48ce19", "domain-prose",  "symptom", "miss"),
    ("never cancel before broker confirms",                                                "5e07e60f", "21c-carried",   "symptom", "miss"),
    ("browser window comes up empty",                                                      "7646de22", "21c-carried",   "symptom", "miss"),
    ("the app starts but nothing paints inside the frame",                                 "7646de22", "code-symptom",  "symptom", "miss"),
    ("how close should two vectors be before we call them the same thing",                 "fef49c17", "process-prose", "symptom", "miss"),
    ("opening a new trade killed the protective stop on the other side and left shares unguarded", "1999c6a8", "domain-prose", "symptom", "miss"),
    ("which long term java release do we move the trading platform to and why",            "9437dccd", "domain-prose",  "symptom", "miss"),
    ("is the tick recorder costing us anything while trading live",                        "47465012", "domain-prose",  "symptom", "miss"),
    ("com.jats2.gateways.alpol.alpaca.orders.OrderProcessor#getAllPositions",              "d20ce2c6", "short-target",  "symbol",  "hit"),
    ("AlgoManager",                                                                        "8932f5b6", "short-target",  "symptom", "wrong"),
]
# Literal controls: cues quoting a STORED symptom string. They license the
# reading that a keyword "miss" above is a true absence, not a broken tool.
CONTROLS = [("blank tauri webview on linux", "7646de22"),
            ("broker_position_drift on short position", "d20ce2c6"),
            ("cosine threshold", "fef49c17"),
            ("algomanager broker-confirm cleanup", "8932f5b6")]

def row_of(prefix):
    for k, i in enumerate(ids):
        if i.startswith(prefix):
            return k
    return None

CE = m.encode([c[0] for c in CALIB], normalize_embeddings=True, show_progress_bar=False)
designated_scores, results = [], []
for (cue, pref, cls, field, kw), cv in zip(CALIB, CE):
    k = row_of(pref)
    sims = E @ cv
    order = np.argsort(-sims)
    rank = int(np.where(order == k)[0][0]) + 1
    designated_scores.append(float(sims[k]))
    top = order[0]
    results.append({
        "cue": cue, "class": cls, "keyword_cue_field": field,
        "keyword_baseline": kw,
        "designated": pref, "designated_summary": summ[ids[k]],
        "score": round(float(sims[k]), 4), "rank": rank,
        "top1_id": ids[top], "top1_score": round(float(sims[top]), 4),
        "top1_summary": summ[ids[top]],           # F-F: the winner is CHECKABLE
        "top1_is_designated": bool(top == k),
    })

# ---------------------------------------------------------------- 3b distributions
rng = np.random.default_rng(27)
unrelated = []
for ci, cv in enumerate(CE):
    sims = E @ cv
    dk = row_of(CALIB[ci][1])
    for j in rng.choice(len(ids), size=400, replace=False):
        if j != dk:
            unrelated.append(float(sims[j]))

# ONE near-duplicate scan (F-A: the two earlier scans used different sample
# sizes and exclusions, so their counts disagreed). Identical-text pairs are
# separated out rather than silently mixed in.
SAMPLE_N = 900
s = rng.choice(len(rows), size=min(SAMPLE_N, len(rows)), replace=False)
S = E[s]; M = S @ S.T; np.fill_diagonal(M, -1)
identical, distinct = [], []
for a in range(len(s)):
    b = int(np.argmax(M[a])); sc = float(M[a][b])
    if sc < 0.78:
        continue
    ia, ib = ids[s[a]], ids[s[b]]
    rec = {"score": round(sc, 4), "a": ia, "b": ib,
           "a_summary": summ[ia], "b_summary": summ[ib]}
    (identical if texts[s[a]] == texts[s[b]] else distinct).append(rec)

def band(lo, hi):
    return [p for p in distinct if lo <= p["score"] < hi]

def pct(v, q):
    return round(float(np.percentile(v, q)), 4)

report = {
 "model": MODEL, "dim": int(E.shape[1]), "corpus_entries": len(ids),
 "embedded_text": "summary + details", "sample_n": SAMPLE_N, "rng_seed": 27,
 "latency_ms_reference_python": {
     "single_p50": round(singles[len(singles)//2], 1),
     "single_p95": round(singles[int(.95*len(singles))], 1),
     "batched_per_entry": round(1000*bulk/len(texts), 2),
     "corpus_total_s": round(bulk, 1)},
 "calibration": results,
 "controls": [{"cue": c, "expect": e} for c, e in CONTROLS],
 "designated": {
     "n": len(designated_scores), "min": round(min(designated_scores), 4),
     "median": round(statistics.median(designated_scores), 4),
     "max": round(max(designated_scores), 4),
     "distinct_entries": len({c[1] for c in CALIB}),
     "rank1": sum(1 for r in results if r["top1_is_designated"]),
     "rank_le_4": sum(1 for r in results if r["rank"] <= 4),
     "rank_le_12": sum(1 for r in results if r["rank"] <= 12)},
 "unrelated": {"n": len(unrelated), "median": pct(unrelated, 50),
               "p95": pct(unrelated, 95), "p99": pct(unrelated, 99),
               "max": round(max(unrelated), 4)},
 "near_duplicates": {
     "scan": f"nearest-neighbour over a {SAMPLE_N}-entry sample, score >= 0.78",
     "identical_text_pairs": len(identical),
     "distinct_text_pairs": len(distinct),
     "bands": {b: len(band(lo, hi)) for b, (lo, hi) in
               {"0.78-0.80": (.78, .80), "0.80-0.85": (.80, .85),
                "0.85-0.90": (.85, .90), "0.90-0.95": (.90, .95),
                "0.95-1.00": (.95, 1.01)}.items()},
     "labeled_pairs_distinct_text": sorted(distinct, key=lambda p: -p["score"]),
     "labeled_pairs_identical_text": identical[:20]},
 "derived": {
     "nomination_floor": 0.15,
     "nomination_floor_basis": ("below the observed designated minimum; the "
        "designated and unrelated distributions OVERLAP, so no floor separates "
        "them - the floor is a volume cap only, the score ranks"),
     "top_k": 12,
     "dedup_threshold": 0.92,
     "dedup_threshold_basis": (
        "CONSERVATIVE, and the corpus does NOT support a clean separation. "
        "Reading the labeled distinct-text pairs: true re-phrasings and false "
        "positives INTERLEAVE across 0.87-0.90 (true: 'Fix (landed)'/'Fix "
        "(applied)' 0.9208, the AI-attribution rule 0.8728, 'Task 39' 0.8724; "
        "false: 'ORB Sprint 5'/'ORB Sprint 7' 0.8970, 'Sources'/'Architecture' "
        "0.8937). A short-text guard does not fix it - one false pair is "
        "heading-only (43/23 chars) but the other carries 567/1012 chars. "
        "D5 only PROPOSES a merge (a human confirms), so the threshold is set "
        "HIGH where the sampled evidence is clean rather than tuned for recall. "
        "Evidence is THIN at that height (1 content pair >= 0.92), so Stage 6 "
        "MUST hand-label a duplicate set to calibrate, and D6's per-criterion "
        "gate counters measure it in production."),
     "dedup_threshold_confidence": "low - thin evidence, mixed band, revisit at Stage 6",
     "retired_placeholders": {"floor": 0.35},
     "corrections": {
        "dedup_0.85": ("WRONG, self-corrected. Derived from the top-2 pairs of "
          "each band, which were unrepresentative. The committed labeled pair "
          "list (this file) shows false positives inside 0.85-0.90. The C0 "
          "audit's demand that the labeled data be committed is what exposed "
          "it - the number moved because the evidence became checkable.")}},
}
json.dump(report, open(f"{OUT}/stage0-distributions.json", "w"), indent=1, ensure_ascii=False)

# ---------------------------------------------------------------- 4 manifest
try:
    import torch
    from huggingface_hub import snapshot_download, model_info
    d = snapshot_download(MODEL)
    files, tot = [], 0
    for w in ("model.safetensors", "pytorch_model.bin", "tokenizer.json", "vocab.txt",
              "config.json", "tokenizer_config.json", "special_tokens_map.json",
              "sentence_bert_config.json"):
        p = os.path.join(d, w)
        if not os.path.exists(p):
            continue
        b = os.path.getsize(p)
        files.append({"file": w, "bytes": b,
                      "sha256": hashlib.sha256(open(p, "rb").read()).hexdigest()})
    bundle = [f for f in files if f["file"] != "pytorch_model.bin"]
    fp32 = sum(f["bytes"] for f in bundle)
    # F-D: MEASURE fp16 by real conversion, do not halve arithmetically.
    sd = m[0].auto_model.state_dict()
    half = {k: v.half() for k, v in sd.items()}
    tmp = f"{OUT}/.fp16probe.pt"
    torch.save(half, tmp); fp16_w = os.path.getsize(tmp); os.remove(tmp)
    fp32_w = next(f["bytes"] for f in bundle if f["file"] == "model.safetensors")
    json.dump({"model": MODEL,
               "license": (model_info(MODEL).card_data or {}).get("license"),
               "files": files,
               "bundle_fp32_bytes": fp32,
               "bundle_fp16_bytes": fp32 - fp32_w + fp16_w,
               "fp16_weights_bytes_measured": fp16_w,
               "fp16_method": "state_dict().half() serialized and measured"},
              open(f"{OUT}/checkpoint-manifest.json", "w"), indent=1)
    print(f"bundle fp32={fp32/1e6:.1f} MB  fp16={(fp32-fp32_w+fp16_w)/1e6:.1f} MB (measured)")
except Exception as ex:                                    # never fake a number
    print("manifest step FAILED (no number written):", ex)

print("\ndesignated:", report["designated"])
print("unrelated :", report["unrelated"])
print("near-dupes:", report["near_duplicates"]["bands"],
      "identical:", report["near_duplicates"]["identical_text_pairs"])
print("\n=== CALIBRATION (rank over the FULL corpus) ===")
for r in results:
    mark = "OK " if r["top1_is_designated"] else ("<=4" if r["rank"] <= 4 else "MISS")
    print(f" [{mark}] kw={r['keyword_baseline']:<5} rank={r['rank']:<5} "
          f"score={r['score']:.4f} {r['class']:<14} {r['cue'][:52]}")
    if not r["top1_is_designated"]:
        print(f"        winner {r['top1_id'][:8]} @{r['top1_score']:.4f}: {r['top1_summary'][:95]}")
