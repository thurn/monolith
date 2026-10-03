"""Tournament analysis: win rates with Wilson 95% CIs per pairing-side, per-agent null rates
(self-play Corp win share), and a Bradley-Terry fit per side: P(corp i beats runner j) =
sigmoid(c_i - r_j). Stalled games are excluded from ratings and reported separately."""
import argparse
import collections
import json
import math


def wilson(k, n, z=1.96):
    if n == 0:
        return (0.0, 1.0)
    p = k / n
    d = 1 + z * z / n
    c = (p + z * z / (2 * n)) / d
    w = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / d
    return (c - w, c + w)


def bradley_terry(games, iters=3000, lr=0.05, l2=0.01):
    corps = sorted({g["corp"] for g in games})
    runners = sorted({g["runner"] for g in games})
    c = {a: 0.0 for a in corps}
    r = {a: 0.0 for a in runners}
    for _ in range(iters):
        gc = {a: -l2 * c[a] for a in corps}
        gr = {a: -l2 * r[a] for a in runners}
        for g in games:
            p = 1 / (1 + math.exp(-(c[g["corp"]] - r[g["runner"]])))
            y = 1.0 if g["winner"] == "corp" else 0.0
            gc[g["corp"]] += y - p
            gr[g["runner"]] -= y - p
        n = len(games)
        for a in corps:
            c[a] += lr * gc[a] / n * len(corps)
        for a in runners:
            r[a] += lr * gr[a] / n * len(runners)
    # anchor: mean corp rating 0
    m = sum(c.values()) / len(c)
    return {a: v - m for a, v in c.items()}, {a: v - m for a, v in r.items()}


def load(paths):
    out = []
    for p in paths:
        for line in open(p):
            if line.strip():
                out.append(json.loads(line))
    return out


def summarize(games):
    lines = []
    ok = [g for g in games if not g.get("stall")]
    stalls = [g for g in games if g.get("stall")]
    lines.append(f"games {len(games)}, stalled {len(stalls)} ({100.0 * len(stalls) / max(1, len(games)):.2f}%)")
    causes = collections.Counter((g["stall"] or {}).get("cause") for g in stalls)
    if causes:
        lines.append("stall causes: " + ", ".join(f"{k}={v}" for k, v in causes.items()))
    by = collections.defaultdict(list)
    for g in ok:
        by[(g["corp"], g["runner"])].append(g)
    lines.append("")
    lines.append("| Corp | Runner | n | Corp win | 95% CI | turns | win types |")
    lines.append("|---|---|---|---|---|---|---|")
    null = {}
    for (c, r), gs in sorted(by.items()):
        k = sum(1 for g in gs if g["winner"] == "corp")
        lo, hi = wilson(k, len(gs))
        types = collections.Counter(f"{g['winner']}/{g['reason']}" for g in gs)
        turns = sum(g.get("turn") or 0 for g in gs) / len(gs)
        lines.append(f"| {c} | {r} | {len(gs)} | {k / len(gs):.3f} | [{lo:.3f}, {hi:.3f}] | {turns:.1f} | "
                     + ", ".join(f"{t} {n}" for t, n in types.most_common()) + " |")
        if c == r:
            null[c] = k / len(gs)
    if null:
        lines.append("")
        lines.append("Null rates (self-play Corp win share): " + ", ".join(f"{a} {v:.3f}" for a, v in sorted(null.items())))
    if len({g["corp"] for g in ok}) > 1:
        cr, rr = bradley_terry(ok)
        lines.append("")
        lines.append("| Agent | Corp rating | Runner rating |")
        lines.append("|---|---|---|")
        for a in sorted(set(cr) | set(rr), key=lambda a: -(cr.get(a, 0) + rr.get(a, 0))):
            lines.append(f"| {a} | {cr.get(a, float('nan')):+.2f} | {rr.get(a, float('nan')):+.2f} |")
    think = collections.defaultdict(lambda: [0, 0])
    for g in ok:
        for side in ("corp", "runner"):
            t = (g.get("think-ms") or {}).get(side)
            d = (g.get("decisions") or {}).get(side)
            if t is not None and d:
                think[g[side]][0] += t
                think[g[side]][1] += d
    if think:
        lines.append("")
        lines.append("Mean think time per decision (ms): " + ", ".join(f"{a} {t / max(1, n):.1f}" for a, (t, n) in sorted(think.items())))
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("paths", nargs="+")
    args = ap.parse_args()
    print(summarize(load(args.paths)))


if __name__ == "__main__":
    main()
