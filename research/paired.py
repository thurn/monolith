"""Paired one-side comparison: research/paired.py <games.jsonl> <side> <base-tag> <tag>...
Wins per tag and discordant pairs vs base on shared (seed, matchup), with exact McNemar p."""
import json, sys
from math import comb
path, side, base, *tags = sys.argv[1:]
res = {}
for l in open(path):
    r = json.loads(l)
    if r.get('side') != side or r.get('stall'): continue
    res.setdefault(r['tag'], {})[(r['seed'], r.get('corp-deck'))] = (r.get('winner') == side)
def p(b, c):
    n = b + c
    return 1.0 if n == 0 else min(1.0, 2 * sum(comb(n, k) for k in range(min(b, c) + 1)) / 2 ** n)
for t in tags:
    ks = set(res.get(base, {})) & set(res.get(t, {}))
    b = sum(1 for k in ks if res[t][k] and not res[base][k]); c = sum(1 for k in ks if res[base][k] and not res[t][k])
    print(f"{t:12s} n={len(ks)} base {sum(res[base][k] for k in ks)/max(1,len(ks)):.3f} tag {sum(res[t][k] for k in ks)/max(1,len(ks)):.3f} +{b}/-{c} p={p(b,c):.3f}")
