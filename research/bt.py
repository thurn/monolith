#!/usr/bin/env -S bash -c 'exec "$(dirname "$0")/../ai/py/.venv/bin/python" "$0" "$@"'
"""Joint Bradley-Terry side-rating fit for a paired A/B games log (monolith.ai.ab :games-log).

Model: per matchup m a null Corp-win logit t_m (from the null variant: the agent playing the
opponent itself), the candidate's Corp gain g_c and Runner gain g_r:
  null games         P(corp wins) = s(t_m)
  candidate as Corp  P(corp wins) = s(t_m + g_c)
  candidate as Runner P(corp wins) = s(t_m - g_r)
T2 needs g_c >= 0.85 and g_r >= 0.85. 95% intervals by bootstrap over seeds.

Usage: research/bt.py <games.jsonl> [tag] [--null-variant a] [--boot 300]
"""
import json, sys, random, math
import numpy as np

def load(path, tag):
    rows = [json.loads(l) for l in open(path)]
    return [r for r in rows if (tag is None or r.get("tag") == tag) and not r.get("stall")]

def fit(rows, null_variant):
    ms = sorted({r["corp-deck"] for r in rows})
    mi = {m: i for i, m in enumerate(ms)}
    M = len(ms)
    # design: columns t_0..t_{M-1}, g_c, g_r
    X, y = [], []
    for r in rows:
        x = np.zeros(M + 2)
        x[mi[r["corp-deck"]]] = 1
        if r["variant"] != null_variant:
            if r["side"] == "corp": x[M] = 1
            else: x[M + 1] = -1
        X.append(x); y.append(1.0 if r.get("winner") == "corp" else 0.0)
    X, y = np.array(X), np.array(y)
    w = np.zeros(M + 2)
    prior = np.full(M + 2, 1 / 4.0); prior[M:] = 1e-6   # N(0, 2^2) on matchup logits
    for _ in range(50):
        p = 1 / (1 + np.exp(-X @ w))
        g = X.T @ (y - p) - prior * w
        H = -(X.T * (p * (1 - p))) @ X - np.diag(prior)
        step = np.linalg.solve(H, g)
        w -= step
        if np.abs(step).max() < 1e-8: break
    return w[M], w[M + 1]

def main():
    args = sys.argv[1:]
    boot = 300
    null_variant = "a"
    if "--boot" in args:
        i = args.index("--boot"); boot = int(args[i + 1]); del args[i:i + 2]
    if "--null-variant" in args:
        i = args.index("--null-variant"); null_variant = args[i + 1]; del args[i:i + 2]
    path, tag = args[0], (args[1] if len(args) > 1 else None)
    rows = load(path, tag)
    gc, gr = fit(rows, null_variant)
    seeds = sorted({r["seed"] for r in rows})
    by_seed = {}
    for r in rows: by_seed.setdefault(r["seed"], []).append(r)
    rng = random.Random(0)
    bs = []
    for _ in range(boot):
        sample = [r for s in (rng.choice(seeds) for _ in seeds) for r in by_seed[s]]
        bs.append(fit(sample, null_variant))
    bs = np.array(bs)
    lo, hi = np.percentile(bs, [2.5, 97.5], axis=0)
    def rate(v, side, win):
        rs = [r for r in rows if r["variant"] == v and r["side"] == side]
        return sum(1 for r in rs if r.get("winner") == win) / max(1, len(rs)), len(rs)
    cand = "b" if null_variant == "a" else "a"
    print(f"games={len(rows)} seeds={len(seeds)} matchups={len({r['corp-deck'] for r in rows})}")
    for v in (null_variant, cand):
        (c, n), (rr, _) = rate(v, "corp", "corp"), rate(v, "runner", "runner")
        print(f"  {v}: corp-win {c:.3f}  runner-win {rr:.3f}  (n={n} per side)")
    print(f"  Corp gain   g_c = {gc:+.3f}  [{lo[0]:+.3f}, {hi[0]:+.3f}]  T2 needs >= +0.85")
    print(f"  Runner gain g_r = {gr:+.3f}  [{lo[1]:+.3f}, {hi[1]:+.3f}]  T2 needs >= +0.85")

if __name__ == "__main__":
    main()
