"""Trains the shared Net's Corp-perspective value head on valuegen positions (outcome labels),
splitting train/validation by game. Reports MSE and win-prediction accuracy; exports in the JVM
layout (action heads are untrained and unused)."""
import argparse
import glob
import json
import os
import struct
import time

import numpy as np
import torch
import torch.nn.functional as F

from .model import Net, export

ENTRY = np.dtype([("i", ">i2"), ("v", ">f4")])


def load(dir_):
    games, actives, labels, rows, cols, vals = [], [], [], [], [], []
    n = 0
    for p in sorted(glob.glob(os.path.join(dir_, "*.bin"))):
        buf = open(p, "rb").read()
        off = 0
        while off + 11 <= len(buf):
            g, a, lab, nnz = struct.unpack_from(">ibfh", buf, off)
            off += 11
            if off + 6 * nnz > len(buf):
                break
            e = np.frombuffer(buf, dtype=ENTRY, count=nnz, offset=off)
            off += 6 * nnz
            games.append(g); actives.append(a); labels.append(lab)
            rows.append(np.full(nnz, n)); cols.append(e["i"].astype(np.int64)); vals.append(e["v"].astype(np.float32))
            n += 1
    return (np.array(games), np.array(actives), np.array(labels, np.float32),
            np.concatenate(rows), np.concatenate(cols), np.concatenate(vals), n)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--S", type=int, required=True)
    ap.add_argument("--D", type=int, required=True)
    ap.add_argument("--epochs", type=int, default=20)
    ap.add_argument("--batch", type=int, default=1024)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--device", default="mps" if torch.backends.mps.is_available() else "cpu")
    args = ap.parse_args()
    torch.manual_seed(0)
    dev = torch.device(args.device)
    games, actives, labels, r, c, v, n = load(args.data)
    X = torch.sparse_coo_tensor(np.vstack([r, c]), v, (n, args.S)).to_dense()
    y = torch.tensor(labels)
    ug = np.unique(games)
    rng = np.random.default_rng(0)
    val_games = set(rng.choice(ug, size=max(1, len(ug) // 10), replace=False).tolist())
    is_val = torch.tensor([g in val_games for g in games])
    Xtr, ytr, Xva, yva = X[~is_val], y[~is_val], X[is_val], y[is_val]
    print(f"{n} positions, {len(ug)} games, train {len(ytr)} val {len(yva)}, corp win rate {float((y > 0).float().mean()):.3f}", flush=True)
    net = Net(args.S, args.D).to(dev)
    opt = torch.optim.Adam(net.parameters(), lr=args.lr, weight_decay=1e-5)
    dummy_a = torch.zeros(1, 1, args.D, device=dev)

    def value(xb):
        B = xb.shape[0]
        _, vv = net(xb, torch.zeros(B, dtype=torch.long, device=dev), dummy_a.expand(B, 1, args.D),
                    torch.ones(B, 1, dtype=torch.bool, device=dev))
        return vv

    best, best_state, bad = 1e9, None, 0
    for ep in range(args.epochs):
        t0 = time.time()
        perm = torch.randperm(len(ytr))
        net.train()
        for i in range(0, len(perm), args.batch):
            idx = perm[i:i + args.batch]
            xb, yb = Xtr[idx].to(dev), ytr[idx].to(dev)
            loss = F.mse_loss(value(xb), yb)
            opt.zero_grad(); loss.backward(); opt.step()
        net.eval()
        with torch.no_grad():
            pv = torch.cat([value(Xva[i:i + 4096].to(dev)).cpu() for i in range(0, len(yva), 4096)])
        mse = float(F.mse_loss(pv, yva)); acc = float(((pv > 0) == (yva > 0)).float().mean())
        print(json.dumps(dict(epoch=ep, val_mse=mse, val_acc=acc, secs=time.time() - t0)), flush=True)
        if mse < best - 1e-4:
            best, bad = mse, 0
            best_state = {k: t.detach().clone() for k, t in net.state_dict().items()}
        else:
            bad += 1
            if bad >= 3:
                break
    net.load_state_dict(best_state)
    export(net, args.out, int(time.time()))
    print("exported", args.out, "best val mse", best, flush=True)


if __name__ == "__main__":
    main()
