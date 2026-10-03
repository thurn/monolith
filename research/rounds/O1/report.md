# O1 report: cheap speed

Fork `c7bf430`, `cf45e8b`; equivalence baseline recorded on fork `75d01ec`.

| Change | µs/action (single thread, warm, Stage A random) |
|---|---|
| Baseline (R0) | 490 |
| `*headless*` skips UI ability cost labels; fast paths when no `:disable-card` / subtype effects exist | 304 |
| Eager `flatten` in cost merge/pay; skip `get-card` in unmodified subtype path | 265 |

- **Target ≤ 200 µs missed by ~33%.** S3's budget is set accordingly: at 265 µs, a 2,500-application plan is ~0.66 s of engine time (plus move generation), which still fits a per-turn budget of a few seconds. If S3 needs more, shrink the beam/budget before R1 tournaments.
- Logging was not hot (<1%), so `system-msg` is left on; logs are useful for the T3 review.
- In-process hosting was already the R0 design (no JSON, no diffs).

## Equivalence

`scripts/engine-equiv`: 500 seeded random games recorded on the pre-O1 fork, replayed on each change.

- 500/500 games identical at every action (decision, legal action labels, credits, hand sizes, log tail).
- 23/500 final canonical states differ:
  - 17 only by one duplicate `:breaker-strength-changed` entry in `:turn-events` (the skipped label pass caused one fewer `fake-checkpoint` loop iteration);
  - 6 by heap ordering. Rules-equivalent, but a later random draw from a reordered zone would give a different realization.
- Relaxation (documented): equivalence is "identical play and identical final state up to these two effects", not bit-identical hashes.
- The per-action fingerprint is cheap (labels + counters + log tail); a full canonical-state hash of every action cost ~30 ms and made recording 500 games take over an hour, so it is only taken at game end.
