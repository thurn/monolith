"""Compare agent tags on a fixed evaluation set (monolith.ai.evalset games log).

For each tag: win rates per side, and Bradley-Terry side-rating gains over the null (opponent
self-play): per matchup m a null Corp-win logit t_m shared by all tags, and per tag
  as Corp   P(corp wins) = s(t_m + g_c)      as Runner P(corp wins) = s(t_m - g_r).
T2 needs g_c >= 0.85 and g_r >= 0.85. Intervals: bootstrap over seeds.
With two or more tags, the first is the baseline: paired McNemar on (seed, side) for each other tag.

Usage: ai/py/.venv/bin/python research/cmp.py <games.jsonl> <tag> [tag ...] [--boot N]
"""
import json, sys, random, math
import numpy as np

def mcnemar_p(b, c):
    n, k = b + c, min(b, c)
    if n == 0: return 1.0
    return min(1.0, 2 * sum(math.comb(n, i) for i in range(k + 1)) / 2 ** n)

def fit(rows, tags):
    ms = sorted({r["corp-deck"] for r in rows})
    mi = {m: i for i, m in enumerate(ms)}
    M, T = len(ms), len(tags)
    ti = {t: i for i, t in enumerate(tags)}
    X = np.zeros((len(rows), M + 2 * T)); y = np.zeros(len(rows))
    for j, r in enumerate(rows):
        X[j, mi[r["corp-deck"]]] = 1
        if r["tag"] != "null":
            k = ti[r["tag"]]
            if r["side"] == "corp": X[j, M + 2 * k] = 1
            else: X[j, M + 2 * k + 1] = -1
        y[j] = 1.0 if r.get("winner") == "corp" else 0.0
    w = np.zeros(X.shape[1])
    prior = np.full(X.shape[1], 1 / 4.0); prior[M:] = 1e-4
    for _ in range(60):
        p = 1 / (1 + np.exp(-X @ w))
        g = X.T @ (y - p) - prior * w
        H = X.T @ (X * (p * (1 - p))[:, None]) + np.diag(prior)
        step = np.linalg.solve(H, g)
        w += step
        if np.abs(step).max() < 1e-8: break
    return w[M:].reshape(T, 2)

def main():
    args = sys.argv[1:]
    boot = 200
    if "--boot" in args:
        i = args.index("--boot"); boot = int(args[i + 1]); del args[i:i + 2]
    path, tags = args[0], args[1:]
    rows = [json.loads(l) for l in open(path)]
    rows = [r for r in rows if r["tag"] in tags or r["tag"] == "null"]
    stalls = [r for r in rows if r.get("stall")]
    rows = [r for r in rows if not r.get("stall")]
    # keep only seeds present for every tag and the null, so tags are compared on the same games
    have = {}
    for r in rows: have.setdefault(r["seed"], set()).add(r["tag"])
    seeds = sorted(s for s, ts in have.items() if set(tags) | {"null"} <= ts)
    sset = set(seeds)
    rows = [r for r in rows if r["seed"] in sset]
    print(f"seeds={len(seeds)} matchups={len({r['corp-deck'] for r in rows})} stalls(excluded)={len(stalls)}")
    nulls = [r for r in rows if r["tag"] == "null"]
    print(f"  null: corp-win {sum(r.get('winner') == 'corp' for r in nulls) / max(1, len(nulls)):.3f}")
    g = fit(rows, tags)
    by_seed = {}
    for r in rows: by_seed.setdefault(r["seed"], []).append(r)
    rng = random.Random(0)
    bs = np.array([fit([r for s in (rng.choice(seeds) for _ in seeds) for r in by_seed[s]], tags) for _ in range(boot)])
    lo, hi = np.percentile(bs, [2.5, 97.5], axis=0)
    won = {(r["tag"], r["side"], r["seed"]): r.get("winner") == r["side"] for r in rows if r["tag"] != "null"}
    for k, t in enumerate(tags):
        c = [won[(t, "corp", s)] for s in seeds if (t, "corp", s) in won]
        rr = [won[(t, "runner", s)] for s in seeds if (t, "runner", s) in won]
        print(f"  {t}: corp {np.mean(c):.3f} runner {np.mean(rr):.3f} | g_c {g[k,0]:+.2f} [{lo[k,0]:+.2f},{hi[k,0]:+.2f}]"
              f"  g_r {g[k,1]:+.2f} [{lo[k,1]:+.2f},{hi[k,1]:+.2f}]")
        if k > 0:
            base = tags[0]
            for side in ("corp", "runner", None):
                keys = [(sd, s) for sd in (("corp", "runner") if side is None else (side,)) for s in seeds
                        if (base, sd, s) in won and (t, sd, s) in won]
                b = sum(1 for sd, s in keys if not won[(base, sd, s)] and won[(t, sd, s)])
                a = sum(1 for sd, s in keys if won[(base, sd, s)] and not won[(t, sd, s)])
                print(f"      vs {base} [{side or 'both'}]: +{b} -{a}  p={mcnemar_p(a, b):.3g}")

if __name__ == "__main__":
    main()
