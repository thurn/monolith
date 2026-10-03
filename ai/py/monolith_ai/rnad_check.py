"""Sanity check for the R-NaD port on a tiny imperfect-information game.

Asymmetric matching pennies played sequentially with the first move hidden: P0 picks H/T,
P1 picks H/T without seeing it. P0 gets +2 for HH, +1 for TT, -1 otherwise (zero-sum).
The unique Nash equilibrium is P(H) = 0.4 for both players."""
import copy
import numpy as np
import torch
from .model import Net
from .rnad import build_batch, train_step

PAY = {(0, 0): 2.0, (1, 1): 1.0, (0, 1): -1.0, (1, 0): -1.0}


def policy(net, side):
    x = torch.zeros(1, 2); x[0, side] = 1.0
    a = torch.eye(2)[None]
    logits, _ = net(x, torch.tensor([side]), a, torch.ones(1, 2, dtype=torch.bool))
    return torch.softmax(logits, -1)[0].detach().numpy()


def play(net, rng):
    steps, moves = [], []
    for side in (0, 1):
        p = policy(net, side)
        m = int(rng.random() > p[0])
        moves.append(m)
        acts = [(np.array([0]), np.array([1.0], np.float32)), (np.array([1]), np.array([1.0], np.float32))]
        steps.append(dict(side=side, corp_ap=0, runner_ap=0, chosen=m, state=(np.array([side]), np.array([1.0], np.float32)),
                          actions=acts, mu=p.astype(np.float32)))
    u = PAY[(moves[0], moves[1])]
    # winner semantics: reward +-1 scaled by |u|; emulate by duplicating terminal reward via winner and scale
    return dict(winner=0 if u > 0 else 1, seed=0, steps=steps, scale=abs(u))


def main(lr=1e-3, reg_steps=1000, tau=0.01, steps=8000, eta=0.2):
    import sys
    if len(sys.argv) > 1:
        lr, reg_steps, tau, steps, eta = float(sys.argv[1]), int(sys.argv[2]), float(sys.argv[3]), int(sys.argv[4]), float(sys.argv[5])
    torch.manual_seed(1)
    rng = np.random.default_rng(1)
    net = Net(2, 2, H=16, HA=8, HZ=8)
    target, reg = copy.deepcopy(net), copy.deepcopy(net)
    opt = torch.optim.Adam(net.parameters(), lr=lr, betas=(0.0, 0.999))
    for step in range(1, steps + 1):
        games = [play(target, rng) for _ in range(64)]
        bt = build_batch(games, 2, 2, torch.device("cpu"), 0.0)
        for b, g in enumerate(games):
            bt["rew"][:, b, :] *= g["scale"]
        train_step(net, target, reg, opt, bt, eta=eta, tau=tau)
        if step % reg_steps == 0:
            reg.load_state_dict(target.state_dict())
        if step % 1000 == 0:
            print(step, "P0 P(H)=%.3f  P1 P(H)=%.3f" % (policy(target, 0)[0], policy(target, 1)[0]), flush=True)


if __name__ == "__main__":
    main()
