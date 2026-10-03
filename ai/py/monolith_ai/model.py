"""Policy/value net mirrored by ai/java/monolith/Mlp.java, plus export to manifest.json + weights.bin."""
import json
import os
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F


class Net(nn.Module):
    def __init__(self, S, D, H=192, HA=96, HZ=96):
        super().__init__()
        self.dims = dict(S=S, D=D, H=H, HA=HA, HZ=HZ)
        self.l1 = nn.Linear(S, H)
        self.l2 = nn.Linear(H, H)
        self.la = nn.Linear(D, HA)
        self.vh = nn.ModuleList([nn.Linear(H, 1) for _ in range(2)])
        self.zh = nn.ModuleList([nn.Linear(H, HZ) for _ in range(2)])
        self.za = nn.ModuleList([nn.Linear(HA, HZ, bias=False) for _ in range(2)])
        self.zo = nn.ModuleList([nn.Linear(HZ, 1) for _ in range(2)])

    def forward(self, x, side, a, amask):
        """x [N,S], side [N] long, a [N,A,D], amask [N,A] bool -> logits [N,A] (masked -inf), value [N]."""
        h = F.relu(self.l2(F.relu(self.l1(x))))
        a1 = F.relu(self.la(a))
        vs, ls = [], []
        for s in range(2):
            vs.append(torch.tanh(self.vh[s](h)).squeeze(-1))
            z = F.relu(self.zh[s](h)[:, None, :] + self.za[s](a1))
            ls.append(self.zo[s](z).squeeze(-1))
        sel = side.bool()
        v = torch.where(sel, vs[1], vs[0])
        logits = torch.where(sel[:, None], ls[1], ls[0])
        logits = logits.masked_fill(~amask, -1e9)
        return logits, v


def export(net, outdir, version):
    """Atomically writes outdir/manifest.json and outdir/weights.bin in the Java layout."""
    os.makedirs(outdir, exist_ok=True)
    t = lambda w: w.detach().cpu().float().numpy()
    tensors = [("w1", t(net.l1.weight).T), ("b1", t(net.l1.bias)), ("w2", t(net.l2.weight).T), ("b2", t(net.l2.bias)),
               ("wa", t(net.la.weight).T), ("ba", t(net.la.bias))]
    for s in range(2):
        tensors += [(f"wv{s}", t(net.vh[s].weight)[0]), (f"bv{s}", t(net.vh[s].bias)),
                    (f"wzh{s}", t(net.zh[s].weight).T), (f"bz{s}", t(net.zh[s].bias)),
                    (f"wza{s}", t(net.za[s].weight).T), (f"wz{s}", t(net.zo[s].weight)[0]), (f"bzz{s}", t(net.zo[s].bias))]
    blob = b"".join(np.ascontiguousarray(a, dtype="<f4").tobytes() for _, a in tensors)
    tmpw = os.path.join(outdir, "weights.bin.tmp")
    with open(tmpw, "wb") as f:
        f.write(blob)
    os.replace(tmpw, os.path.join(outdir, "weights.bin"))
    man = dict(dims=net.dims, version=version, tensors=[[n, int(np.asarray(a).size)] for n, a in tensors])
    tmpm = os.path.join(outdir, "manifest.json.tmp")
    with open(tmpm, "w") as f:
        json.dump(man, f)
    os.replace(tmpm, os.path.join(outdir, "manifest.json"))
