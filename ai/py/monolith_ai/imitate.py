"""S4 v1: train a policy prior to imitate a teacher's (S3's) decisions, plus a value head on game
outcomes. The value head is exported but only used in search if it wins its own A/B."""
import argparse
import glob
import json
import os
import random
import time

import numpy as np
import torch
import torch.nn.functional as F

from .model import Net, export
from .rnad import build_batch
from .traj import read_shard


def load(dir_):
    games = []
    for p in sorted(glob.glob(os.path.join(dir_, "*.bin"))):
        games.extend(g for g in read_shard(p) if g["steps"])
    return games


def chunks(games, n):
    for i in range(0, len(games), n):
        yield games[i:i + n]


def run_epoch(net, games, S, D, dev, opt=None, batch=32):
    tot = dict(ce=0.0, acc=0.0, v=0.0, n=0)
    for gs in chunks(games, batch):
        bt = build_batch(gs, S, D, dev, 0.0)
        logits, v = net(bt["x"], bt["side"], bt["a"], bt["amask"])
        valid = bt["valid"] > 0
        ce = F.cross_entropy(logits[valid], bt["chosen"][valid])
        # outcome from the acting side's perspective: rew[side][b, last] carries +-1 at the end
        B, T = bt["B"], bt["T"]
        final = bt["rew"].sum(-1)  # [2, B]
        side = bt["side"].view(B, T)
        z = torch.where(side == 0, final[0][:, None].expand(B, T), final[1][:, None].expand(B, T)).reshape(-1)
        vl = F.mse_loss(v[valid], z[valid])
        loss = ce + 0.5 * vl
        if opt:
            opt.zero_grad(); loss.backward(); opt.step()
        k = int(valid.sum())
        tot["ce"] += float(ce) * k; tot["v"] += float(vl) * k; tot["n"] += k
        tot["acc"] += float((logits[valid].argmax(-1) == bt["chosen"][valid]).float().sum())
    n = max(1, tot["n"])
    return dict(ce=tot["ce"] / n, acc=tot["acc"] / n, v=tot["v"] / n, n=tot["n"])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--S", type=int, required=True)
    ap.add_argument("--D", type=int, required=True)
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--device", default="mps" if torch.backends.mps.is_available() else "cpu")
    args = ap.parse_args()
    torch.manual_seed(0); random.seed(0)
    dev = torch.device(args.device)
    games = load(args.data)
    random.shuffle(games)
    nval = max(1, len(games) // 10)
    val, train = games[:nval], games[nval:]
    print(f"{len(train)} train games, {len(val)} val games, "
          f"{sum(len(g['steps']) for g in train)} train decisions", flush=True)
    net = Net(args.S, args.D).to(dev)
    opt = torch.optim.Adam(net.parameters(), lr=args.lr, weight_decay=1e-5)
    best, best_state, bad = 1e9, None, 0
    log = open(os.path.join(args.out + ".log.jsonl"), "a")
    for ep in range(args.epochs):
        random.shuffle(train)
        t0 = time.time()
        net.train(); tr = run_epoch(net, train, args.S, args.D, dev, opt)
        net.eval()
        with torch.no_grad():
            va = run_epoch(net, val, args.S, args.D, dev)
        rec = dict(epoch=ep, train=tr, val=va, secs=time.time() - t0)
        print(json.dumps(rec), flush=True); log.write(json.dumps(rec) + "\n"); log.flush()
        if va["ce"] < best - 1e-3:
            best, best_state, bad = va["ce"], {k: v.detach().clone() for k, v in net.state_dict().items()}, 0
        else:
            bad += 1
            if bad >= 4:
                break
    net.load_state_dict(best_state)
    export(net, args.out, int(time.time()))
    print("exported", args.out, "best val ce", best, flush=True)


if __name__ == "__main__":
    main()
