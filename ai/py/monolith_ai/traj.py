"""Reads trajectory shards written by monolith.ai.selfplay (see its docstring for the format)."""
import struct
import numpy as np

MAGIC = 0x4E52474D


_ENTRY = np.dtype([("i", ">i2"), ("v", ">f4")])


def _sparse(buf, off):
    (n,) = struct.unpack_from(">h", buf, off)
    off += 2
    e = np.frombuffer(buf, dtype=_ENTRY, count=n, offset=off)
    return (e["i"].astype(np.int64), e["v"].astype(np.float32)), off + 6 * n


def read_shard(path):
    """Returns a list of games: {winner, seed, steps:[{side, corp_ap, runner_ap, chosen, state, actions, mu}]}"""
    buf = open(path, "rb").read()
    off = 0
    games = []
    while off < len(buf):
        magic, nsteps, winner, seed = struct.unpack_from(">iiii", buf, off)
        off += 16
        assert magic == MAGIC, "bad shard"
        steps = []
        for _ in range(nsteps):
            side, cap, rap, A, chosen = struct.unpack_from(">bhhhh", buf, off)
            off += 9
            state, off = _sparse(buf, off)
            acts = []
            for _ in range(A):
                a, off = _sparse(buf, off)
                acts.append(a)
            mu = np.frombuffer(buf, dtype=">f4", count=A, offset=off).astype(np.float32)
            off += 4 * A
            steps.append(dict(side=side, corp_ap=cap, runner_ap=rap, chosen=chosen, state=state, actions=acts, mu=mu))
        games.append(dict(winner=winner, seed=seed, steps=steps))
    return games
