#!/usr/bin/env -S bash -c 'exec "$(dirname "$0")/../ai/py/.venv/bin/python" "$0" "$@"'
"""Matchup-awareness test on an evalset log whose opponent is the baseline agent (null = baseline).

1. Per (matchup, side, tag): win rate, delta vs null, paired discordant counts, McNemar p.
2. Interaction: per (side, tag), chi-square heterogeneity of discordant-pair splits across matchups
   (does the option's effect depend on the matchup?).
3. Split-half book: on half A pick the best tag (or "none") per (matchup, side) = book, and per side
   across matchups = global; score both on half B, swap halves, average. Bootstrap CI over seeds.

Usage: research/matchup_book.py <games.jsonl> [--boot N]
"""
import json, sys, math, random, collections
import numpy as np

path = sys.argv[1]
boot = int(sys.argv[sys.argv.index("--boot") + 1]) if "--boot" in sys.argv else 1000
rows = [json.loads(l) for l in open(path)]
null = {r["seed"]: r for r in rows if r["tag"] == "null"}
tags = sorted({r["tag"] for r in rows} - {"null"})
have = collections.defaultdict(set)
for r in rows: have[r["seed"]].add((r["tag"], r["side"]))
need = {(t, s) for t in tags for s in ("corp", "runner")}
seeds = sorted(s for s in null if need <= have[s])
mof = {s: null[s]["corp-deck"][:-5] for s in seeds}
ms = sorted(set(mof.values()))
win = {(r["seed"], r["tag"], r["side"]): int(r.get("winner") == r["side"]) for r in rows if r["tag"] != "null"}
base = {(s, side): int(null[s].get("winner") == side) for s in seeds for side in ("corp", "runner")}
# d[(seed, tag, side)] = candidate win - baseline win in {-1, 0, 1}
d = {(s, t, side): win[(s, t, side)] - base[(s, side)] for s in seeds for t in tags for side in ("corp", "runner")}

def mcnemar_p(b, c):
    n, k = b + c, min(b, c)
    return 1.0 if n == 0 else min(1.0, 2 * sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n)

def chi2_sf(x, k):
    # upper tail of chi-square with k dof (series for the regularized lower gamma)
    if x <= 0: return 1.0
    a, s, term = k / 2.0, 0.0, 1.0 / (k / 2.0)
    for n in range(1, 500):
        s += term; term *= (x / 2.0) / (a + n)
    return max(0.0, 1.0 - s * math.exp(-x / 2.0 + a * math.log(x / 2.0) - math.lgamma(a)))

print(f"seeds={len(seeds)} matchups={ms} tags={tags}\n")
for side in ("corp", "runner"):
    print(f"== {side}")
    for m in ms:
        ss = [s for s in seeds if mof[s] == m]
        b0 = np.mean([base[(s, side)] for s in ss])
        cells = []
        for t in tags:
            ds = [d[(s, t, side)] for s in ss]
            b, c = ds.count(1), ds.count(-1)
            p = mcnemar_p(b, c)
            cells.append(f"{t[3:]:>5} {np.mean(ds):+.3f}{'*' if p < 0.05 else ' '}")
        print(f"  {m:16s} n={len(ss)} base {b0:.3f} | " + " ".join(cells))
    print("  interaction (heterogeneity of discordant splits across matchups):")
    for t in tags:
        bc = [([d[(s, t, side)] for s in seeds if mof[s] == m].count(1), [d[(s, t, side)] for s in seeds if mof[s] == m].count(-1)) for m in ms]
        B, C = sum(b for b, _ in bc), sum(c for _, c in bc)
        if B + C == 0: continue
        pb = B / (B + C)
        x = sum((b - (b + c) * pb) ** 2 / max(1e-9, (b + c) * pb * (1 - pb)) for b, c in bc if b + c > 0)
        print(f"    {t:8s} overall {(B - C) / len([s for s in seeds]):+.3f} (+{B}/-{C}, p={mcnemar_p(B, C):.3f})  "
              f"chi2={x:.2f} dof={len(ms) - 1} p={chi2_sf(x, len(ms) - 1):.3f}")
    print()

def book_eval(train, test):
    """Returns (book gain, global gain, oracle-on-test gain) per side, averaged over test seeds."""
    out = {}
    for side in ("corp", "runner"):
        def mean_d(ss, t): return 0.0 if t is None else np.mean([d[(s, t, side)] for s in ss])
        opts = [None] + tags
        g = max(opts, key=lambda t: mean_d(train, t))
        book_ds, glob_ds = [], []
        for m in ms:
            tr = [s for s in train if mof[s] == m]
            te = [s for s in test if mof[s] == m]
            bm = max(opts, key=lambda t: mean_d(tr, t))
            book_ds += [0 if bm is None else d[(s, bm, side)] for s in te]
            glob_ds += [0 if g is None else d[(s, g, side)] for s in te]
        out[side] = (np.mean(book_ds), np.mean(glob_ds))
    return out

def split_half(ss):
    a = [s for s in ss if (s // len(ms)) % 2 == 0]
    b = [s for s in ss if (s // len(ms)) % 2 == 1]
    r1, r2 = book_eval(a, b), book_eval(b, a)
    return {side: tuple((x + y) / 2 for x, y in zip(r1[side], r2[side])) for side in r1}

def picks(ss):
    out = {}
    for side in ("corp", "runner"):
        for m in ms + ["*"]:
            sm = [s for s in ss if m == "*" or mof[s] == m]
            sc = {t: np.mean([d[(s, t, side)] for s in sm]) for t in tags}
            t = max(sc, key=sc.get)
            out[(side, m)] = f"{t[3:]} {sc[t]:+.3f}" if sc[t] > 0 else "none"
    return out

print("== best option per (side, matchup) on all seeds (in-sample, optimistic)")
pk = picks(seeds)
for side in ("corp", "runner"):
    print(f"  {side:6s} " + " | ".join(f"{m}: {pk[(side, m)]}" for m in ms + ["*"]))

pt = split_half(seeds)
rng = random.Random(0)
bs = {side: [] for side in pt}
by_m = {m: [s for s in seeds if mof[s] == m] for m in ms}
for _ in range(boot):
    # resample seeds within each matchup, keeping their split-half parity
    ss = [s for m in ms for s in rng.choices(by_m[m], k=len(by_m[m]))]
    r = split_half(ss)
    for side in r: bs[side].append(r[side])
print("\n== split-half (choose on one half, score on the other; gains vs baseline champion)")
for side in pt:
    arr = np.array(bs[side])
    diff = arr[:, 0] - arr[:, 1]
    lo, hi = np.percentile(diff, [2.5, 97.5])
    print(f"  {side:6s} book {pt[side][0]:+.3f}  global {pt[side][1]:+.3f}  book-global {pt[side][0] - pt[side][1]:+.3f} "
          f"[95% {lo:+.3f}, {hi:+.3f}]")
