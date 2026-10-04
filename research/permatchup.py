#!/usr/bin/env -S bash -c 'exec "$(dirname "$0")/../ai/py/.venv/bin/python" "$0" "$@"'
"""Per-matchup win rates for evalset tags (and the null), on seeds every listed tag has.

Usage: research/permatchup.py <games.jsonl> <tag> [tag ...]
Prints, per matchup: null Corp-win rate, then each tag's Corp and Runner win rates, plus win
reasons and mean turns for the last tag. Matchups sorted by the last tag's Runner rate.
"""
import json, sys, collections

path, tags = sys.argv[1], sys.argv[2:]
rows = [json.loads(l) for l in open(path)]
rows = [r for r in rows if (r["tag"] in tags or r["tag"] == "null")]
have = collections.defaultdict(set)
for r in rows: have[r["seed"]].add(r["tag"])
seeds = {s for s, ts in have.items() if set(tags) | {"null"} <= ts}
rows = [r for r in rows if r["seed"] in seeds]
by = collections.defaultdict(list)
for r in rows: by[(r["corp-deck"][:-5], r["tag"], r["side"])].append(r)
ms = sorted({r["corp-deck"][:-5] for r in rows})
def rate(rs, side): return sum(r.get("winner") == side for r in rs) / max(1, len(rs))
last = tags[-1]
lines = []
for m in ms:
    nul = by[(m, "null", "corp")]
    cells = [f"null {rate(nul, 'corp'):.2f}"]
    for t in tags:
        c, rr = by[(m, t, "corp")], by[(m, t, "runner")]
        cells.append(f"{t}: C {rate(c, 'corp'):.2f} R {rate(rr, 'runner'):.2f}")
    lr = by[(m, last, "corp")] + by[(m, last, "runner")]
    reasons = collections.Counter(f"{r.get('winner')}/{r.get('reason')}" for r in lr)
    turns = sum(r.get("turn") or 0 for r in lr) / max(1, len(lr))
    lines.append((rate(by[(m, last, "runner")], "runner"), f"{m:22s} n={len(nul):2d} " + " | ".join(cells) + f" | t={turns:.0f} {dict(reasons)}"))
for _, l in sorted(lines): print(l)
print(f"seeds={len(seeds)}")
