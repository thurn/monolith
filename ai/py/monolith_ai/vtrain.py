"""Fit the R4 value model: P(Corp wins | position) from dense card-agnostic features
(monolith.ai.vdata JSONL). Validates on whole dev matchups held out from training, so the
score measures transfer to unseen decks. Exports JSON for monolith.ai.knowledge.vmodel.

Usage: python -m monolith_ai.vtrain out.json data.jsonl [data2.jsonl ...] [--hidden 64] [--epochs 30]
       [--val-matchups k] [--names names.txt]
"""
import json, sys, random, math
import numpy as np
import torch
import torch.nn as nn

def load(paths):
    X, y, m, s = [], [], [], []
    for p in paths:
        for line in open(p):
            r = json.loads(line)
            X.append(r["x"]); y.append(r["y"]); m.append(r["m"]); s.append(r["seed"])
    return np.array(X, dtype=np.float32), np.array(y, dtype=np.float32), np.array(m), np.array(s)

def main():
    args = sys.argv[1:]
    opt = {"--hidden": 64, "--epochs": 30, "--val-matchups": 8, "--l2": 1e-4}
    for k in list(opt):
        if k in args:
            i = args.index(k); opt[k] = type(opt[k])(args[i + 1]); del args[i:i + 2]
    out, paths = args[0], args[1:]
    X, y, m, s = load(paths)
    ms = sorted(set(m))
    rng = random.Random(0)
    val_ms = set(rng.sample(ms, min(opt["--val-matchups"], len(ms) // 4)))
    tr = np.array([mm not in val_ms for mm in m])
    print(f"positions {len(y)}  corp-win {y.mean():.3f}  matchups {len(ms)}  val-matchups {sorted(val_ms)}")
    mu, sd = X[tr].mean(0), X[tr].std(0) + 1e-6
    Z = (X - mu) / sd
    Zt, yt, Zv, yv = map(torch.tensor, (Z[tr], y[tr], Z[~tr], y[~tr]))
    mi = {mm: i for i, mm in enumerate(ms)}
    mt = torch.tensor([mi[mm] for mm in m[tr]]); mv = torch.tensor([mi[mm] for mm in m[~tr]])
    def refit_bias(z, yy, mm):
        """Per-matchup intercepts on held-out matchups (1-D Newton), so validation scores the
        state-dependent part only: matchup strength is invisible to card-agnostic features."""
        b = torch.zeros(len(ms))
        for _ in range(25):
            p = torch.sigmoid(z + b[mm])
            g = torch.zeros(len(ms)).index_add_(0, mm, yy - p)
            h = torch.zeros(len(ms)).index_add_(0, mm, p * (1 - p)) + 1e-3
            b = b + g / h
        return b
    def fit(hidden):
        torch.manual_seed(0)
        d = X.shape[1]
        net = nn.Sequential(nn.Linear(d, hidden), nn.ReLU(), nn.Linear(hidden, 1)) if hidden else nn.Sequential(nn.Linear(d, 1))
        bias = nn.Embedding(len(ms), 1); nn.init.zeros_(bias.weight)
        optm = torch.optim.Adam(list(net.parameters()) + list(bias.parameters()), lr=1e-3, weight_decay=opt["--l2"])
        lossf = nn.BCEWithLogitsLoss()
        best, best_state = 1e9, None
        for ep in range(opt["--epochs"]):
            perm = torch.randperm(len(yt))
            for i in range(0, len(yt), 512):
                idx = perm[i:i + 512]
                optm.zero_grad()
                l = lossf(net(Zt[idx]).squeeze(1) + bias(mt[idx]).squeeze(1), yt[idx]); l.backward(); optm.step()
            with torch.no_grad():
                lt = lossf(net(Zt).squeeze(1) + bias(mt).squeeze(1), yt).item()
                zv = net(Zv).squeeze(1)
                bv = refit_bias(zv, yv, mv)
                lv = lossf(zv + bv[mv], yv).item()
                av = (((zv + bv[mv]) > 0).float() == yv).float().mean().item()
            if lv < best: best, best_state = lv, {k: v.clone() for k, v in net.state_dict().items()}
            if ep % 5 == 4 or ep == opt["--epochs"] - 1:
                print(f"  h={hidden} ep {ep+1}: train {lt:.4f}  val {lv:.4f}  val-acc {av:.3f}")
        net.load_state_dict(best_state)
        return net, best
    with torch.no_grad():
        bv = refit_bias(torch.zeros(len(yv)), yv, mv)
        base = nn.BCEWithLogitsLoss()(bv[mv], yv).item()
    print(f"  matchup-intercept-only val logloss {base:.4f}")
    lin, lv_lin = fit(0)
    mlp, lv_mlp = fit(opt["--hidden"])
    net, kind = (mlp, "mlp") if lv_mlp < lv_lin - 0.002 else (lin, "linear")
    print(f"chosen {kind}: val logloss linear {lv_lin:.4f} mlp {lv_mlp:.4f}")
    layers = [{"W": l.weight.detach().numpy().tolist(), "b": l.bias.detach().numpy().tolist()}
              for l in net if isinstance(l, nn.Linear)]
    w = lin[0].weight.detach().numpy()[0]
    names = None
    json.dump({"kind": kind, "mean": mu.tolist(), "std": sd.tolist(), "layers": layers,
               "val_logloss": min(lv_lin, lv_mlp), "positions": int(len(y))}, open(out, "w"))
    order = np.argsort(-np.abs(w))
    print("linear weights (standardized), largest first:", [(int(i), round(float(w[i]), 3)) for i in order[:20]])

if __name__ == "__main__":
    main()
