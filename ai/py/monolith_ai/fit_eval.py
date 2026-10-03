"""Fits evaluator feature weights by logistic regression of the game outcome (Corp win) on the
evaluator's named features at turn starts. Prints weights normalised so :credits = 1, i.e. in
credit units, ready for weights.edn :eval multipliers."""
import json
import sys
import numpy as np
import torch

rows = json.load(open(sys.argv[1]))
skip = {"y", "turn", "clicks"}
names = sorted(k for k in rows[0] if k not in skip)
X = np.array([[float(r.get(k) or 0.0) for k in names] for r in rows], dtype=np.float32)
y = np.array([r["y"] for r in rows], dtype=np.float32)
mu, sd = X.mean(0), X.std(0) + 1e-6
Xt = torch.tensor((X - mu) / sd)
yt = torch.tensor(y)
w = torch.zeros(len(names), requires_grad=True)
b = torch.zeros(1, requires_grad=True)
opt = torch.optim.LBFGS([w, b], max_iter=500)

def closure():
    opt.zero_grad()
    logit = Xt @ w + b
    loss = torch.nn.functional.binary_cross_entropy_with_logits(logit, yt) + 1e-3 * (w ** 2).sum()
    loss.backward()
    return loss

opt.step(closure)
raw = (w.detach().numpy() / sd)
with torch.no_grad():
    p = torch.sigmoid(Xt @ w + b).numpy()
acc = ((p > 0.5) == (y > 0.5)).mean()
ll = -np.mean(y * np.log(p + 1e-9) + (1 - y) * np.log(1 - p + 1e-9))
print(f"rows {len(rows)} acc {acc:.3f} logloss {ll:.3f} base rate {y.mean():.3f}")
c = raw[names.index("credits")]
for n, v in zip(names, raw):
    print(f"{n:28s} logit/unit {v:+.4f}   in credits {v / c:+.3f}")
