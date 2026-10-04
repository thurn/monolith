#!/usr/bin/env -S bash -c 'exec "$(dirname "$0")/../ai/py/.venv/bin/python" "$0" "$@"'
"""One-table summary of an evalset/run-many games log: every tag vs a baseline tag, paired on
(seed, side) over seeds present for both, with win rates, discordant pairs and McNemar p per side,
and Bradley-Terry side-ratings over the null (shared fit, see cmp.py).

Usage: research/summary.py <games.jsonl> <baseline-tag> [--min-seeds N]
"""
import json, sys, math, collections
sys.path.insert(0, __file__.rsplit("/", 1)[0])
from cmp import fit, mcnemar_p

def main():
    args = sys.argv[1:]
    min_seeds = 0
    if "--min-seeds" in args:
        i = args.index("--min-seeds"); min_seeds = int(args[i + 1]); del args[i:i + 2]
    path, base = args
    rows = [json.loads(l) for l in open(path)]
    rows = [r for r in rows if not r.get("stall")]
    tags = sorted({r["tag"] for r in rows} - {"null", base})
    won = {(r["tag"], r["side"], r["seed"]): r.get("winner") == r["side"] for r in rows}
    seeds_of = collections.defaultdict(set)
    for r in rows: seeds_of[r["tag"]].add(r["seed"])
    print(f"{'tag':10s} {'seeds':>5s} {'corp':>6s} {'runner':>6s} {'dC':>8s} {'pC':>6s} {'dR':>8s} {'pR':>6s} {'g_c':>6s} {'g_r':>6s}")
    for t in [base] + tags:
        common = sorted(seeds_of[t] & seeds_of[base] & seeds_of["null"])
        if len(common) < min_seeds: continue
        sub = [r for r in rows if r["seed"] in set(common) and r["tag"] in (t, "null")]
        g = fit(sub, [t])[0]
        cells = []
        for side in ("corp", "runner"):
            keys = [s for s in common if (t, side, s) in won and (base, side, s) in won]
            rate = sum(won[(t, side, s)] for s in keys) / max(1, len(keys))
            b = sum(1 for s in keys if won[(t, side, s)] and not won[(base, side, s)])
            a = sum(1 for s in keys if won[(base, side, s)] and not won[(t, side, s)])
            cells.append((rate, f"+{b}/-{a}", mcnemar_p(a, b) if t != base else 1.0, len(keys)))
        print(f"{t:10s} {len(common):5d} {cells[0][0]:6.3f} {cells[1][0]:6.3f} {cells[0][1]:>8s} {cells[0][2]:6.3f} {cells[1][1]:>8s} {cells[1][2]:6.3f} {g[0]:+6.2f} {g[1]:+6.2f}")

if __name__ == "__main__":
    main()
