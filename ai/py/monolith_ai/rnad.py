"""S5 learner: Regularized Nash Dynamics (Perolat et al. 2022), ported from the structure of
OpenSpiel's open_spiel/python/algorithms/rnad (JAX) to PyTorch, adapted to variable legal-action
sets. Reads trajectory shards from <dir>/shards, writes weights to <dir>/net for the JVM actors.

Simplifications vs the reference (documented in research/LOG.md):
- pi_reg is replaced by the target network at each regularization-iteration boundary (no
  interpolation between the last two fixed points);
- trajectories only contain decisions with more than one legal action;
- optional zero-sum agenda-point shaping reward.
"""
import argparse
import copy
import glob
import json
import os
import random
import time

import numpy as np
import torch

from .model import Net, export
from .traj import read_shard


def build_batch(games, S, D, device, shaping):
    """Flat tensors for B games padded to T steps and A actions."""
    B = len(games)
    T = max(len(g["steps"]) for g in games)
    A = max(len(st["actions"]) for g in games for st in g["steps"])
    N = B * T
    xs_n, xs_i, xs_v = [], [], []
    as_n, as_j, as_i, as_v = [], [], [], []
    amask = np.zeros((N, A), bool)
    mu = np.ones((N, A), np.float32)
    chosen = np.zeros(N, np.int64)
    side = np.zeros(N, np.int64)
    valid = np.zeros(N, np.float32)
    rew = np.zeros((2, B, T), np.float32)
    for b, g in enumerate(games):
        steps = g["steps"]
        for t, st in enumerate(steps):
            n = b * T + t
            idx, val = st["state"]
            xs_n.append(np.full(len(idx), n)); xs_i.append(idx); xs_v.append(val)
            for j, (ai, av) in enumerate(st["actions"]):
                as_n.append(np.full(len(ai), n)); as_j.append(np.full(len(ai), j)); as_i.append(ai); as_v.append(av)
            k = len(st["actions"])
            amask[n, :k] = True
            mu[n, :k] = np.maximum(st["mu"], 1e-6)
            chosen[n] = st["chosen"]
            side[n] = st["side"]
            valid[n] = 1.0
            # zero-sum shaping: change in (corp AP - runner AP) attributed to the step that preceded it
            if shaping and t + 1 < len(steps):
                d0 = st["corp_ap"] - st["runner_ap"]
                d1 = steps[t + 1]["corp_ap"] - steps[t + 1]["runner_ap"]
                r = shaping * (d1 - d0) / 7.0
                rew[0, b, t] += r
                rew[1, b, t] -= r
        last = len(steps) - 1
        if g["winner"] in (0, 1):
            rew[g["winner"], b, last] += 1.0
            rew[1 - g["winner"], b, last] -= 1.0
    x = torch.zeros(N, S, device=device)
    x[torch.from_numpy(np.concatenate(xs_n)).to(device), torch.from_numpy(np.concatenate(xs_i)).to(device)] = \
        torch.from_numpy(np.concatenate(xs_v)).to(device)
    a = torch.zeros(N, A, D, device=device)
    a[torch.from_numpy(np.concatenate(as_n)).to(device), torch.from_numpy(np.concatenate(as_j)).to(device),
      torch.from_numpy(np.concatenate(as_i)).to(device)] = torch.from_numpy(np.concatenate(as_v)).to(device)
    to = lambda z: torch.from_numpy(z).to(device)
    return dict(B=B, T=T, A=A, x=x, a=a, amask=to(amask), mu=to(mu), chosen=to(chosen),
                side=to(side), valid=to(valid), rew=to(rew))


def v_trace(v, valid, pid, cs, inv_mu, a_oh, pi, mlogp, reward, player, eta, c=1.0, rho=float("inf"), lam=1.0):
    """Backward V-trace from one player's perspective (shapes [B,T] / [B,T,A])."""
    B, T = v.shape
    others = (2.0 * (pid == player).float() - 1.0) * valid  # +1 own step, -1 opponent step
    eta_reg_entropy = -others * (pi * mlogp).sum(-1) * eta
    eta_log_policy = -others[..., None] * mlogp * eta
    z = torch.zeros(B, device=v.device)
    c_rew, c_unc, c_nv, c_nvt, c_is = z.clone(), z.clone(), z.clone(), z.clone(), torch.ones(B, device=v.device)
    v_target = torch.zeros_like(v)
    learn = torch.zeros_like(pi)
    for t in range(T - 1, -1, -1):
        ok = valid[:, t] > 0
        own = ok & (pid[:, t] == player)
        opp = ok & ~own
        r = reward[:, t]
        unc = r + c_unc + eta_reg_entropy[:, t]
        disc = r + c_rew
        is_t = cs[:, t] * c_is
        our_vt = v[:, t] + torch.clamp(is_t, max=rho) * (unc + c_nv - v[:, t]) + lam * torch.clamp(is_t, max=c) * (c_nvt - c_nv)
        our_lo = v[:, t, None] + eta_log_policy[:, t] + a_oh[:, t] * inv_mu[:, t, None] * (disc + c_is * c_nvt - v[:, t])[:, None]
        v_target[:, t] = torch.where(own, our_vt, torch.zeros_like(our_vt))
        learn[:, t] = torch.where(own[:, None], our_lo, torch.zeros_like(our_lo))
        # carries
        n_rew = torch.where(own, z, torch.where(opp, eta_reg_entropy[:, t] + cs[:, t] * disc, z))
        n_unc = torch.where(own, z, torch.where(opp, unc, z))
        n_nv = torch.where(own, v[:, t], torch.where(opp, c_nv, z))
        n_nvt = torch.where(own, our_vt, torch.where(opp, c_nvt, z))
        n_is = torch.where(own, torch.ones_like(c_is), torch.where(opp, cs[:, t] * c_is, torch.ones_like(c_is)))
        c_rew, c_unc, c_nv, c_nvt, c_is = n_rew, n_unc, n_nv, n_nvt, n_is
    return v_target, learn


def neurd_loss(logits, pi, q, legal, mask, threshold=2.0, clip=10000.0):
    adv = q - (pi * q).sum(-1, keepdim=True)
    adv = torch.clamp(adv, -clip, clip).detach()
    lg = torch.where(legal, logits, torch.zeros_like(logits))
    centered = lg - (lg * legal).sum(-1, keepdim=True) / legal.sum(-1, keepdim=True).clamp(min=1)
    can_dec = centered > -threshold
    can_inc = centered < threshold
    force = can_dec * torch.clamp(adv, max=0.0) + can_inc * torch.clamp(adv, min=0.0)
    per = (legal * centered * force.detach()).sum(-1)
    return -(per * mask).sum() / mask.sum().clamp(min=1)


def train_step(net, target, reg, opt, bt, eta, tau):
    """One R-NaD learner step on a built batch; returns (value loss, policy loss, pi, valid)."""
    B, T, A = bt["B"], bt["T"], bt["A"]
    logits, v = net(bt["x"], bt["side"], bt["a"], bt["amask"])
    with torch.no_grad():
        lt, vt = target(bt["x"], bt["side"], bt["a"], bt["amask"])
        lr_, _ = reg(bt["x"], bt["side"], bt["a"], bt["amask"])
        pi_t = torch.softmax(lt, -1)
        mlogp = (torch.log_softmax(lt, -1) - torch.log_softmax(lr_, -1)) * bt["amask"]
        a_oh = torch.nn.functional.one_hot(bt["chosen"], A).float()
        mu_a = (bt["mu"] * a_oh).sum(-1).clamp(min=1e-6)
        pi_a = (pi_t * a_oh).sum(-1)
        cs = (pi_a / mu_a).view(B, T)
        inv_mu = (1.0 / mu_a).view(B, T)
    pi = torch.softmax(logits, -1)
    valid = bt["valid"].view(B, T)
    pid = bt["side"].view(B, T)
    vl, pl = 0.0, 0.0
    for player in (0, 1):
        with torch.no_grad():
            v_target, learn = v_trace(vt.view(B, T), valid, pid, cs, inv_mu, a_oh.view(B, T, A),
                                      pi_t.view(B, T, A), mlogp.view(B, T, A), bt["rew"][player], player, eta)
        own = ((pid == player).float() * valid).view(-1)
        vl = vl + (((v - v_target.view(-1)) ** 2) * own).sum() / own.sum().clamp(min=1)
        pl = pl + neurd_loss(logits, pi, learn.view(B * T, A), bt["amask"], own)
    loss = vl + pl
    opt.zero_grad()
    loss.backward()
    torch.nn.utils.clip_grad_norm_(net.parameters(), 10000.0)
    opt.step()
    with torch.no_grad():
        for pt, po in zip(target.parameters(), net.parameters()):
            pt.mul_(1 - tau).add_(po, alpha=tau)
    return vl, pl, pi, valid


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--S", type=int, required=True)
    ap.add_argument("--D", type=int, required=True)
    ap.add_argument("--hours", type=float, default=24.0)
    ap.add_argument("--batch", type=int, default=32)
    ap.add_argument("--lr", type=float, default=5e-5)
    ap.add_argument("--eta", type=float, default=0.2)
    ap.add_argument("--tau", type=float, default=0.001)
    ap.add_argument("--reg-steps", type=int, default=2000)
    ap.add_argument("--export-every", type=int, default=25)
    ap.add_argument("--shaping", type=float, default=0.5)
    ap.add_argument("--buffer", type=int, default=3000)
    ap.add_argument("--reuse", type=float, default=2.0, help="target times each game is sampled")
    ap.add_argument("--device", default="mps" if torch.backends.mps.is_available() else "cpu")
    args = ap.parse_args()

    torch.manual_seed(0)
    dev = torch.device(args.device)
    d = args.dir
    os.makedirs(os.path.join(d, "shards"), exist_ok=True)
    ckpt_path = os.path.join(d, "ckpt.pt")
    net = Net(args.S, args.D).to(dev)
    target = copy.deepcopy(net)
    reg = copy.deepcopy(net)
    opt = torch.optim.Adam(net.parameters(), lr=args.lr, betas=(0.0, 0.999))
    step, version, games_seen = 0, 0, 0
    if os.path.exists(ckpt_path):
        ck = torch.load(ckpt_path, map_location=dev)
        net.load_state_dict(ck["net"]); target.load_state_dict(ck["target"]); reg.load_state_dict(ck["reg"])
        opt.load_state_dict(ck["opt"]); step, version, games_seen = ck["step"], ck["version"], ck["games_seen"]
    export(target, os.path.join(d, "net"), version)

    deadline = time.time() + args.hours * 3600
    buf, consumed_since = [], 0
    metrics = open(os.path.join(d, "metrics.jsonl"), "a")
    last_ckpt = time.time()
    while time.time() < deadline:
        # ingest finished shards
        for p in sorted(glob.glob(os.path.join(d, "shards", "*.bin")), key=os.path.getmtime):
            try:
                gs = [g for g in read_shard(p) if g["steps"]]
            except Exception as e:  # partially written or corrupt: drop it
                print("bad shard", p, e, flush=True)
                gs = []
            os.remove(p)
            buf.extend(gs)
            games_seen += len(gs)
            consumed_since += len(gs)
        buf = buf[-args.buffer:]
        if len(buf) < args.batch * 4 or consumed_since * args.reuse < args.batch:
            time.sleep(1.0)
            continue
        consumed_since -= args.batch / args.reuse
        games = random.sample(buf, args.batch)
        bt = build_batch(games, args.S, args.D, dev, args.shaping)
        vl, pl, pi, valid = train_step(net, target, reg, opt, bt, args.eta, args.tau)
        step += 1
        if step % args.reg_steps == 0:
            reg.load_state_dict(target.state_dict())
        if step % args.export_every == 0:
            version += 1
            export(target, os.path.join(d, "net"), version)
            ent = -(pi * torch.log(pi.clamp(min=1e-9)) * bt["amask"]).sum(-1)
            m = dict(t=time.time(), step=step, version=version, games_seen=games_seen, buffer=len(buf),
                     v_loss=float(vl), pi_loss=float(pl), entropy=float((ent * valid.view(-1)).sum() / valid.sum()),
                     T=T, A=A)
            metrics.write(json.dumps(m) + "\n"); metrics.flush()
        if time.time() - last_ckpt > 600:
            torch.save(dict(net=net.state_dict(), target=target.state_dict(), reg=reg.state_dict(), opt=opt.state_dict(),
                            step=step, version=version, games_seen=games_seen), ckpt_path + ".tmp")
            os.replace(ckpt_path + ".tmp", ckpt_path)
            last_ckpt = time.time()
    torch.save(dict(net=net.state_dict(), target=target.state_dict(), reg=reg.state_dict(), opt=opt.state_dict(),
                    step=step, version=version, games_seen=games_seen), ckpt_path)


if __name__ == "__main__":
    main()
